package com.example.psbill

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.psbill.core.ActivityLog
import com.example.psbill.core.DeviceAgent
import com.example.psbill.core.DeviceIdentity
import com.example.psbill.core.LocationOutbox
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Always-on phone agent (starts after sign-in and after reboot):
 *  • continuous GPS — fixes are queued in [LocationOutbox] (offline-safe) and
 *    uploaded in batches, so the office sees km per minute / hour / day…;
 *    faster + more precise while delivering an order;
 *  • heartbeat to the server — online status, what is waiting to upload, and
 *    sync requests from the web / app, which [DeviceAgent] runs and reports.
 */
class DeliveryTrackingService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    var activeOrderId: String? = null
        private set
    var activeSessionId: String? = null
        private set
    var isTrackingPaused: Boolean = false
        private set

    private var mode = Mode.OFF
    private var lastFix: Location? = null
    private var stillSince = 0L
    private var boostUntil = 0L

    private enum class Mode { OFF, IDLE, MOVING, DELIVERY, BOOST }

    companion object {
        private const val TAG = "PhoneAgent"
        private const val CHANNEL_ID = "delivery_tracking_channel"
        private const val NOTIF_ID = 2002

        const val INTERVAL_ACTIVE_MS = 10_000L   // delivering an order
        const val INTERVAL_MOVING_MS = 20_000L   // on the move
        const val INTERVAL_IDLE_MS = 120_000L    // standing still
        const val INTERVAL_BOOST_MS = 5_000L     // short high-priority burst
        private const val UPLOAD_BATCH = 30

        const val ACTION_START_DELIVERY = "ACTION_START_DELIVERY"
        const val ACTION_STOP_DELIVERY = "ACTION_STOP_DELIVERY"
        const val ACTION_PAUSE_TRACKING = "ACTION_PAUSE_TRACKING"
        const val ACTION_RESUME_TRACKING = "ACTION_RESUME_TRACKING"
        const val ACTION_BOOST_POLLING = "ACTION_BOOST_POLLING"
        const val ACTION_SYNC_NOW = "ACTION_SYNC_NOW"
        const val ACTION_SETTINGS_CHANGED = "ACTION_SETTINGS_CHANGED"
    }

    inner class LocalBinder : Binder() {
        fun getService(): DeliveryTrackingService = this@DeliveryTrackingService
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            io.execute {
                DeviceAgent.heartbeat(applicationContext)
                flushOutboxes()
                DeviceAgent.catchUp(applicationContext, every = 15 * 60_000L)
            }
            handler.post { applyTrackingSettings() }
            handler.postDelayed(this, DeviceAgent.heartbeatSeconds(applicationContext) * 1000)
        }
    }

    /** Upload whatever was recorded while offline (GPS fixes, activity log). */
    private fun flushOutboxes() {
        if (LocationOutbox.size(applicationContext) > 0) runCatching { LocationOutbox.flush(applicationContext) }
        if (ActivityLog.size(applicationContext) > 0) runCatching { ActivityLog.flush(applicationContext) }
    }

    /** Back online: resync immediately instead of waiting for the next heartbeat. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.postDelayed({
                io.execute {
                    flushOutboxes()
                    DeviceAgent.heartbeat(applicationContext)
                    DeviceAgent.catchUp(applicationContext)   // SMS / calls recorded while offline
                }
            }, 3_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ActivityLog.init(this)
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netCallback) }
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (isTrackingPaused) return
                result.locations.forEach { onFix(it) }
            }
        }
        goForeground(statusText())
        applyTrackingSettings()
        handler.post(heartbeat)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (hasLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            try { startForeground(NOTIF_ID, n) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!DeviceIdentity.signedIn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START_DELIVERY -> startDeliverySession(intent.getStringExtra("order_id"))
            ACTION_STOP_DELIVERY -> stopDeliverySession()
            ACTION_PAUSE_TRACKING -> pauseTracking()
            ACTION_RESUME_TRACKING -> resumeTracking()
            ACTION_BOOST_POLLING -> boostPolling()
            ACTION_SYNC_NOW -> io.execute { DeviceAgent.heartbeat(applicationContext) }
            ACTION_SETTINGS_CHANGED -> applyTrackingSettings()
        }
        return START_STICKY
    }

    // ── location ─────────────────────────────────────────────────────────────
    private fun onFix(loc: Location) {
        val prev = lastFix
        val moved = prev?.distanceTo(loc) ?: 0f
        lastFix = loc
        LocationOutbox.add(applicationContext, JSONObject().apply {
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("accuracy", loc.accuracy.toDouble())
            if (loc.hasSpeed()) put("speed", loc.speed.toDouble())
            if (loc.hasBearing()) put("heading", loc.bearing.toDouble())
            put("timestamp", if (loc.time > 0) loc.time else System.currentTimeMillis())
            activeOrderId?.let { put("order_id", it) }
            activeSessionId?.let { put("session_id", it) }
        })
        if (LocationOutbox.size(applicationContext) >= UPLOAD_BATCH) io.execute { runCatching { LocationOutbox.flush(applicationContext) } }

        // adapt: moving ⇄ idle (delivery / boost keep their own pace)
        if (mode == Mode.IDLE || mode == Mode.MOVING) {
            val movingNow = (loc.hasSpeed() && loc.speed > 1.0f) || moved > 40f
            if (movingNow) {
                stillSince = 0L
                if (mode == Mode.IDLE) setMode(Mode.MOVING)
            } else {
                if (stillSince == 0L) stillSince = System.currentTimeMillis()
                if (mode == Mode.MOVING && System.currentTimeMillis() - stillSince > 3 * 60_000) setMode(Mode.IDLE)
            }
        }
    }

    private fun applyTrackingSettings() {
        val want = when {
            !hasLocationPermission() || !DeviceAgent.trackingEnabled(this) || isTrackingPaused -> Mode.OFF
            activeOrderId != null -> Mode.DELIVERY
            System.currentTimeMillis() < boostUntil -> Mode.BOOST
            mode == Mode.MOVING -> Mode.MOVING
            else -> Mode.IDLE
        }
        if (want != mode) setMode(want)
        updateNotification(statusText())
    }

    private fun setMode(m: Mode) {
        mode = m
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            if (m == Mode.OFF) return
            val (interval, priority, minMeters) = when (m) {
                Mode.DELIVERY -> Triple(INTERVAL_ACTIVE_MS, Priority.PRIORITY_HIGH_ACCURACY, 0f)
                Mode.BOOST -> Triple(INTERVAL_BOOST_MS, Priority.PRIORITY_HIGH_ACCURACY, 0f)
                Mode.MOVING -> Triple(INTERVAL_MOVING_MS, Priority.PRIORITY_HIGH_ACCURACY, 10f)
                else -> Triple(INTERVAL_IDLE_MS, Priority.PRIORITY_BALANCED_POWER_ACCURACY, 25f)
            }
            val request = LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(interval / 2)
                .setMinUpdateDistanceMeters(minMeters)
                .build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission: ${e.message}")
            mode = Mode.OFF
        } catch (e: Exception) {
            Log.e(TAG, "Location request error: ${e.message}")
        }
    }

    private fun statusText(): String = when {
        activeOrderId != null -> "Delivering order #${activeOrderId?.takeLast(6)?.uppercase()}"
        isTrackingPaused -> "Location paused"
        !hasLocationPermission() -> "Syncing · location permission needed"
        !DeviceAgent.trackingEnabled(this) -> "Syncing · tracking off"
        else -> "Tracking & syncing"
    }

    // ── delivery sessions (used by the orders module) ────────────────────────
    fun startDeliverySession(orderId: String?) {
        activeOrderId = orderId
        isTrackingPaused = false
        applyTrackingSettings()
        notifyServerSession(orderId, "start")
    }

    fun stopDeliverySession() {
        val target = activeOrderId
        activeOrderId = null
        activeSessionId = null
        isTrackingPaused = false
        applyTrackingSettings()
        notifyServerSession(target, "end")
        io.execute { runCatching { LocationOutbox.flush(applicationContext) } }
    }

    fun pauseTracking() {
        isTrackingPaused = true
        applyTrackingSettings()
    }

    fun resumeTracking() {
        isTrackingPaused = false
        applyTrackingSettings()
    }

    fun boostPolling() {
        if (isTrackingPaused) return
        boostUntil = System.currentTimeMillis() + 120_000
        applyTrackingSettings()
        handler.postDelayed({ applyTrackingSettings() }, 121_000)
    }

    private fun notifyServerSession(orderId: String?, action: String) {
        if (orderId.isNullOrEmpty()) return
        io.execute {
            try {
                val req = Request.Builder()
                    .url("https://${DeviceIdentity.server(applicationContext)}/api/v1/delivery/orders/$orderId/session")
                    .post(JSONObject().put("action", action).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Authorization", "Bearer ${DeviceIdentity.token(applicationContext)}")
                    .header("X-Device-Id", DeviceIdentity.installId(applicationContext))
                    .build()
                com.example.psbill.core.PhoneApi.http.newCall(req).execute().use { r ->
                    if (action == "start" && r.isSuccessful) {
                        activeSessionId = runCatching {
                            JSONObject(r.body?.string().orEmpty()).let { j -> j.optJSONObject("session")?.optString("id") ?: j.optString("session_id") }
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delivery session $action failed: ${e.message}")
            }
        }
    }

    // ── notification ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tracking & sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ajiriwa")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text)) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { fusedLocationClient.removeLocationUpdates(locationCallback) }
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback) }
        io.execute { flushOutboxes() }
        io.shutdown()
        super.onDestroy()
    }
}
