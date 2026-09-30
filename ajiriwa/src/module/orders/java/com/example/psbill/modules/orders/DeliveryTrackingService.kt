package com.example.psbill

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeliveryTrackingService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var activeOrderId: String? = null
        private set
    var activeSessionId: String? = null
        private set
    var isTrackingPaused: Boolean = false
        private set

    // Adaptive intervals
    private var currentIntervalMs: Long = INTERVAL_IDLE_MS

    companion object {
        private const val TAG = "DeliveryTrackingService"
        private const val CHANNEL_ID = "delivery_tracking_channel"
        private const val NOTIF_ID = 2002

        const val INTERVAL_ACTIVE_MS = 10_000L   // 10 seconds active delivery
        const val INTERVAL_IDLE_MS = 20_000L     // 20 seconds continuous agent tracking
        const val INTERVAL_BOOST_MS = 5_000L     // 5 seconds high priority event

        const val ACTION_START_DELIVERY = "ACTION_START_DELIVERY"
        const val ACTION_STOP_DELIVERY = "ACTION_STOP_DELIVERY"
        const val ACTION_PAUSE_TRACKING = "ACTION_PAUSE_TRACKING"
        const val ACTION_RESUME_TRACKING = "ACTION_RESUME_TRACKING"
        const val ACTION_BOOST_POLLING = "ACTION_BOOST_POLLING"
    }

    inner class LocalBinder : Binder() {
        fun getService(): DeliveryTrackingService = this@DeliveryTrackingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (isTrackingPaused) return
                for (location in result.locations) {
                    Log.d(TAG, "Location Ping: ${location.latitude}, ${location.longitude} (Speed: ${location.speed}m/s)")
                    sendLocationPingToServer(
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracy = location.accuracy,
                        speed = location.speed,
                        heading = location.bearing
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Adaptive GPS Idle (Ready for deliveries)"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Adaptive GPS Idle (Ready for deliveries)"))
        }
        requestLocationUpdates(INTERVAL_IDLE_MS, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DELIVERY -> {
                val orderId = intent.getStringExtra("order_id")
                startDeliverySession(orderId)
            }
            ACTION_STOP_DELIVERY -> {
                stopDeliverySession()
            }
            ACTION_PAUSE_TRACKING -> {
                pauseTracking()
            }
            ACTION_RESUME_TRACKING -> {
                resumeTracking()
            }
            ACTION_BOOST_POLLING -> {
                boostPolling()
            }
        }
        return START_STICKY
    }

    fun startDeliverySession(orderId: String?) {
        activeOrderId = orderId
        isTrackingPaused = false
        val displayOrder = orderId?.takeLast(6)?.uppercase() ?: "ACTIVE"
        updateNotification("🛵 In Transit: Order #$displayOrder")
        requestLocationUpdates(INTERVAL_ACTIVE_MS, Priority.PRIORITY_HIGH_ACCURACY)
        notifyServerSession(orderId, "start")
    }

    fun stopDeliverySession() {
        val targetOrder = activeOrderId
        activeOrderId = null
        activeSessionId = null
        isTrackingPaused = false
        updateNotification("Adaptive GPS Idle (Ready for deliveries)")
        requestLocationUpdates(INTERVAL_IDLE_MS, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        notifyServerSession(targetOrder, "end")
    }

    fun pauseTracking() {
        isTrackingPaused = true
        updateNotification("⏸ Location tracking paused by rider")
    }

    fun resumeTracking() {
        isTrackingPaused = false
        val interval = if (activeOrderId != null) INTERVAL_ACTIVE_MS else INTERVAL_IDLE_MS
        val priority = if (activeOrderId != null) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        updateNotification(if (activeOrderId != null) "🛵 Delivering Order #${activeOrderId?.takeLast(6)}" else "Adaptive GPS Active")
        requestLocationUpdates(interval, priority)
    }

    fun boostPolling() {
        if (isTrackingPaused) return
        Log.d(TAG, "Boosting location polling due to incoming event / order trigger")
        requestLocationUpdates(INTERVAL_BOOST_MS, Priority.PRIORITY_HIGH_ACCURACY)
        // Revert to normal interval after 2 minutes
        executor.submit {
            try {
                Thread.sleep(120_000)
                if (activeOrderId != null) {
                    requestLocationUpdates(INTERVAL_ACTIVE_MS, Priority.PRIORITY_HIGH_ACCURACY)
                } else {
                    requestLocationUpdates(INTERVAL_IDLE_MS, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                }
            } catch (_: Exception) {}
        }
    }

    private fun requestLocationUpdates(intervalMs: Long, priority: Int) {
        currentIntervalMs = intervalMs
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val request = LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permissions: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating location request: ${e.message}")
        }
    }

    private fun sendLocationPingToServer(lat: Double, lng: Double, accuracy: Float, speed: Float, heading: Float) {
        executor.submit {
            try {
                val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                val token = prefs.getString("auth_token", "") ?: ""
                val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
                val baseUrl = "https://${serverDomain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}"

                val json = JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lng)
                    put("accuracy", accuracy)
                    put("speed", speed)
                    put("heading", heading)
                    if (!activeOrderId.isNull_or_empty()) put("order_id", activeOrderId)
                    if (!activeSessionId.isNull_or_empty()) put("session_id", activeSessionId)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = json.toString().toRequestBody(mediaType)
                val url = "$baseUrl/api/v1/delivery/location"

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)

                if (token.isNotEmpty()) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                Log.d(TAG, "Location ping response: ${response.code}")
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send location ping to server: ${e.message}")
            }
        }
    }

    private fun notifyServerSession(orderId: String?, action: String) {
        if (orderId.isNull_or_empty()) return
        executor.submit {
            try {
                val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                val token = prefs.getString("auth_token", "") ?: ""
                val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
                val baseUrl = "https://${serverDomain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}"

                val json = JSONObject().apply {
                    put("action", action)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = json.toString().toRequestBody(mediaType)
                val url = "$baseUrl/api/v1/delivery/orders/$orderId/session"

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)

                if (token.isNotEmpty()) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                Log.d(TAG, "Server session $action response for order $orderId: ${response.code}")
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify server of session $action: ${e.message}")
            }
        }
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.isEmpty()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Last Mile Delivery Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ajiriwa Last Mile Tracking")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        executor.shutdown()
    }
}
