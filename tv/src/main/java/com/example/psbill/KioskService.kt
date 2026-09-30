package com.example.psbill

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.Image
import android.hardware.display.DisplayManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.Locale

class KioskService : Service() {

    private val TAG = "KioskService"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)  // Detect dead connections within 15s; triggers reconnect
        .build()

    private var serverDomain = "https://api.ajiriwa.gidraf.dev"
    private var partnerId = "default_partner"

    private fun normalizeServerHost(raw: String): String =
        raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    private fun apiBaseUrl(raw: String): String = "https://${normalizeServerHost(raw)}"

    private fun wsBaseUrl(raw: String): String = "wss://${normalizeServerHost(raw)}"
    
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var overlayButtonView: android.view.View? = null
    private var sleepOverlayView: ComposeView? = null
    private var isSleeping = false
    private val timerText = mutableStateOf("00:00")
    private val isOvertime = mutableStateOf(false)
    private var isOverlayAttached = false
    private var isButtonAttached = false
    private val activeBillingAmount = mutableStateOf(0.0)
    private val activeBillingMode = mutableStateOf("POSTPAID")
    private var syncStateJob: Job? = null
    private var heartbeatJob: Job? = null

    // ── Loser Pay State ──────────────────────────────────────────────────────
    private var isLoserPaySession = false
    private var loserPayToken: String? = null
    private var loserPayPlayerA = mutableStateOf("Player A")
    private var loserPayPlayerB = mutableStateOf("Player B")
    // Each entry: 'W' = won, 'L' = lost, 'D' = draw
    private val loserPayScoreA = androidx.compose.runtime.mutableStateListOf<Char>()  // e.g. ['W','L','W']
    private val loserPayScoreB = androidx.compose.runtime.mutableStateListOf<Char>()
    private var loserPayAmountA = mutableStateOf(0.0)
    private var loserPayAmountB = mutableStateOf(0.0)
    // Child session (round) tracking
    private var childSessionStartTime = 0L   // epoch ms
    private var childSessionRound = mutableStateOf(0)
    private val childSessionLogs = mutableListOf<JSONObject>() // persisted round history
    private var sessionStartTimestamp = 0L  // epoch ms, set when session starts

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var isReconnecting = false  // Guard: prevents scheduling two reconnect timers simultaneously

    // Offline state reconciliation helpers
    private var isConnected = false
    private var offlineSecondsAccumulated = 0
    private var localTickerJob: Job? = null
    private var screenshotJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Cached configuration
    private var deviceStatus = "LOCKED"
        set(value) {
            field = value
            activeDeviceStatus = value
        }
    private var localSecondsRemaining = 0
    private var localSecondsElapsed = 0
    private var localSecondsElapsedOvertime = 0
    // Local ticker is the authoritative clock while a session is live on screen.
    // When a server sync arrives during a LIVE session we never snap the on-screen
    // number to the server value (that causes a visible jump/freeze). Instead we
    // record the difference here and the ticker nudges the local value toward the
    // server value by at most 1 extra second every DRIFT_CORRECTION_INTERVAL_TICKS,
    // so the correction is imperceptible to the player.
    private var elapsedDrift = 0
    private var remainingDrift = 0
    private var overtimeDrift = 0
    private var ticksSinceDriftCorrection = 0
    private val DRIFT_CORRECTION_INTERVAL_TICKS = 4
    private var localAllowOvertime = true
    private var activeBillingKes = 0.0
    private var ratePerMinute = 3.0
    private var minCharge = 40.0
    private var billingMode = "POSTPAID"
    private var currentGame: String? = null
    private var localHdmiConnected = true
    private val isAdminMode = mutableStateOf(false)
    private var tokenVerifyJob: Job? = null
    private var screenWakeReceiver: BroadcastReceiver? = null
    private var isEndSessionDialogShowing = false
    private var endSessionDialogView: ComposeView? = null
    private var endSessionDialogWrapper: android.view.View? = null

    private var currentSessionIdempotencyKey: String?
        get() {
            val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            return prefs.getString("current_session_idempotency_key", null)
        }
        set(value) {
            val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            if (value == null) {
                prefs.edit().remove("current_session_idempotency_key").apply()
            } else {
                prefs.edit().putString("current_session_idempotency_key", value).apply()
            }
        }

    private var currentSessionStartTimeMs: Long
        get() {
            val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            return prefs.getLong("current_session_start_time_ms", 0L)
        }
        set(value) {
            val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            if (value == 0L) {
                prefs.edit().remove("current_session_start_time_ms").apply()
            } else {
                prefs.edit().putLong("current_session_start_time_ms", value).apply()
            }
        }
    private val outboxPrefs by lazy { getSharedPreferences("KioskOutboxPrefs", Context.MODE_PRIVATE) }
    private val outboxLock = Any()

    private fun normalizedStatus(data: JSONObject): String =
        data.optString("status", "").trim().uppercase(Locale.US)

    private fun isLiveSession(data: JSONObject): Boolean {
        val status = normalizedStatus(data)
        if (status in setOf("ACTIVE", "OVERTIME", "RUNNING", "STARTED", "RESUMED", "CONTINUED", "PLAYING")) {
            return true
        }
        return !data.optBoolean("has_ended_session", false) &&
            status !in setOf("LOCKED", "STOPPED", "PAUSED", "ENDED", "FINISHED", "PENDING")
    }

    companion object {
        const val CHANNEL_ID = "kiosk_channel"
        private const val NOTIFICATION_ID = 1
        private const val MAX_OUTBOX_EVENTS = 200
        private const val OUTBOX_KEY = "outbox_events"
        // Locally-initiated "start session" commands are time-sensitive. If one couldn't be
        // delivered within this window, it's stale and must be discarded rather than replayed
        // on the next reconnect (replaying it later could silently re-start a stopped session).
        private const val STALE_START_EVENT_TTL_MS = 15_000L
        var mediaProjection: MediaProjection? = null
        var streamEncoder: StreamEncoder? = null
        var activeDeviceStatus: String = "LOCKED"
        @Volatile var localSessionEndedPendingServerConfirm: Boolean = false
        // Exposed for the stop-confirmation dialog
        @Volatile var activeSecondsRemaining: Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        updateForegroundStatus()
        registerScreenWakeReceiver()
        
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        serverDomain = prefs.getString("server_domain", "https://api.ajiriwa.gidraf.dev") ?: "https://api.ajiriwa.gidraf.dev"
        localHdmiConnected = prefs.getBoolean("hdmi_connected", true)
        val tokenPartnerId = getKioskPartnerId()
        partnerId = if (tokenPartnerId != "default_partner") tokenPartnerId else (prefs.getString("partner_id", "default_partner") ?: "default_partner")
        verifyStartupRegistration(prefs)
    }

    private fun verifyStartupRegistration(prefs: android.content.SharedPreferences) {
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        if (token.isEmpty()) {
            handleRevocationOrDeactivation()
            return
        }

        val fingerprint = getDeviceFingerprint()
        val request = Request.Builder()
            .url("${apiBaseUrl(serverDomain)}/api/devices/verify-token")
            .post(RequestBody.create("application/json".toMediaType(), "{}"))
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", fingerprint)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e(TAG, "Startup device check failed: ${e.message}")
                Handler(Looper.getMainLooper()).post { handleRevocationOrDeactivation() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        Handler(Looper.getMainLooper()).post {
                            startAuthenticatedRuntime(prefs)
                        }
                    } else {
                        Log.w(TAG, "Startup device check failed with code ${resp.code}")
                        Handler(Looper.getMainLooper()).post { handleRevocationOrDeactivation() }
                    }
                }
            }
        })
    }

    private fun startAuthenticatedRuntime(prefs: android.content.SharedPreferences) {
        connectWebSocket()
        startTokenVerificationLoop()
        startSyncStateLoop()

        // Blackout recovery
        try {
            val lastSyncStr = prefs.getString("last_sync_data", null)
            val lastSyncTime = prefs.getLong("last_sync_time", 0L)
            if (lastSyncStr != null && lastSyncTime > 0) {
                val syncJson = JSONObject(lastSyncStr)
                val status = syncJson.optString("status")
                if (status == "ACTIVE" || status == "OVERTIME") {
                    val mode = syncJson.optString("billing_mode", "POSTPAID")
                    var shouldRestore = false

                    if (mode == "PREPAID") {
                        val savedRemaining = syncJson.optInt("seconds_remaining", 0)
                        if (savedRemaining > 0) {
                            deviceStatus = "ACTIVE"
                            localSecondsRemaining = savedRemaining
                            localSecondsElapsed = syncJson.optInt("seconds_elapsed", 0)
                            activeBillingKes = syncJson.optDouble("active_billing_kes", 0.0)
                            shouldRestore = true
                        } else {
                            deviceStatus = "LOCKED"
                        }
                    } else {
                        deviceStatus = status
                        localSecondsElapsed = syncJson.optInt("seconds_elapsed", 0)
                        localSecondsElapsedOvertime = syncJson.optInt("seconds_elapsed_overtime", 0)
                        val minChg = syncJson.optDouble("minimum_charge_kes", 40.0)
                        val ratePerMin = syncJson.optDouble("rate_per_minute_kes", 3.0)
                        val elapsedMins = localSecondsElapsed / 60.0
                        activeBillingKes = Math.max(minChg, elapsedMins * ratePerMin)
                        shouldRestore = true
                    }

                    if (shouldRestore) {
                        billingMode = mode
                        currentGame = if (syncJson.has("current_game") && !syncJson.isNull("current_game")) syncJson.getString("current_game") else null
                        isAdminMode.value = syncJson.optBoolean("is_admin_mode", false)
                        activeBillingAmount.value = activeBillingKes
                        activeBillingMode.value = mode

                        val mockJson = JSONObject().apply {
                            put("status", deviceStatus)
                            put("seconds_remaining", localSecondsRemaining)
                            put("seconds_elapsed", localSecondsElapsed)
                            put("seconds_elapsed_overtime", localSecondsElapsedOvertime)
                            put("is_admin_mode", isAdminMode.value)
                            put("active_billing_kes", activeBillingKes)
                            put("billing_mode", mode)
                            if (currentGame != null) put("current_game", currentGame)
                        }

                        val unlockIntent = Intent("com.example.psbill.ACTION_UNLOCK")
                        sendBroadcast(unlockIntent)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            showTimerOverlay(mockJson)
                        }
                        startScreenshotJob()
                        startLocalTicker()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state on boot: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val tokenPartnerId = getKioskPartnerId()
        val savedPartnerId = if (tokenPartnerId != "default_partner") tokenPartnerId else (prefs.getString("partner_id", "default_partner") ?: "default_partner")
        
        if (savedPartnerId != "default_partner" && savedPartnerId != partnerId) {
            Log.d(TAG, "Partner ID changed from $partnerId to $savedPartnerId. Reconnecting WebSocket...")
            partnerId = savedPartnerId
            webSocket?.close(1000, "Partner changed")
            connectWebSocket()
        }

        if (intent != null) {
            when (intent.action) {
                "START_STREAMING" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    }
                    startScreenStreaming()
                }
                "START_ONLINE_SESSION" -> {
                    val dataStr = intent.getStringExtra("device_data") ?: "{}"
                    try {
                        val deviceData = JSONObject(dataStr)
                        Log.d(TAG, "START_ONLINE_SESSION action received: $dataStr")
                        val oldStatus = deviceStatus
                        val incomingStatus = deviceData.optString("status", "")

                        // Loser Pay returns PENDING while waiting for players to scan QR.
                        // Broadcast the data to LockActivity so it can show the QR screen.
                        // Do NOT call lockDevice() — the session is in progress, just awaiting payment scan.
                        if (incomingStatus == "PENDING" && deviceData.has("qr_link")) {
                            deviceStatus = "PENDING"
                            cacheDeviceState(deviceData)
                            // Use ACTION_SYNC_DATA — the action LockActivity's syncReceiver listens on
                            val syncIntent = Intent("com.example.psbill.ACTION_SYNC_DATA").apply {
                                putExtra("sync_data", dataStr)
                            }
                            sendBroadcast(syncIntent)
                            Log.d(TAG, "Loser Pay PENDING — broadcasting QR sync to LockActivity")
                            return START_STICKY
                        }

                        cacheDeviceState(deviceData)
                        if (isLiveSession(deviceData)) {
                            if (oldStatus == "LOCKED" || oldStatus == "PENDING" || !isOverlayAttached) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    unlockDevice(deviceData)
                                } else {
                                    val unlockIntent = Intent("com.example.psbill.ACTION_UNLOCK")
                                    sendBroadcast(unlockIntent)
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        showTimerOverlay(deviceData)
                                        startScreenshotJob()
                                    }, 400)
                                }
                            } else {
                                updateTimerOverlay(deviceData)
                            }
                        } else {
                            if (oldStatus != "LOCKED" && oldStatus != "PENDING") {
                                val invoice = deviceData.optJSONObject("last_invoice") ?: deviceData.optJSONObject("invoice")
                                lockDevice(invoice)
                            } else {
                                val syncIntent = Intent("com.example.psbill.ACTION_SYNC").apply {
                                    putExtra("sync_data", dataStr)
                                }
                                sendBroadcast(syncIntent)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse START_ONLINE_SESSION device data: ${e.message}")
                    }
                }
                "START_MANUAL_SESSION" -> {
                    val mode = intent.getStringExtra("billing_mode") ?: "POSTPAID"
                    val game = intent.getStringExtra("current_game") ?: "FIFA"
                    val mins = intent.getIntExtra("base_minutes", 0)
                    
                    deviceStatus = "ACTIVE"
                    billingMode = mode
                    currentGame = game
                    localSecondsElapsed = 0
                    localSecondsElapsedOvertime = 0
                    elapsedDrift = 0
                    remainingDrift = 0
                    overtimeDrift = 0
                    
                    currentSessionIdempotencyKey = java.util.UUID.randomUUID().toString()
                    currentSessionStartTimeMs = System.currentTimeMillis()
                    
                    if (mode == "PREPAID") {
                        localSecondsRemaining = mins * 60
                        activeBillingKes = Math.max(minCharge, mins * ratePerMinute)
                    } else {
                        localSecondsRemaining = 0
                        activeBillingKes = minCharge
                    }
                    
                    // Unlock station locally
                    val unlockIntent = Intent("com.example.psbill.ACTION_UNLOCK")
                    sendBroadcast(unlockIntent)
                    
                    val mockJson = JSONObject().apply {
                        put("status", deviceStatus)
                        put("seconds_remaining", localSecondsRemaining)
                        put("seconds_elapsed_overtime", localSecondsElapsedOvertime)
                        put("is_admin_mode", isAdminMode.value)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        showTimerOverlay(mockJson)
                    }
                    startScreenshotJob()
                    
                    if (!isConnected) {
                        offlineSecondsAccumulated = 0
                        startLocalTicker()
                    } else {
                        sendStartManualSessionOverWebSocket(mode, game, mins)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun connectWebSocket() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val tokenPartnerId = getKioskPartnerId()
        partnerId = if (tokenPartnerId != "default_partner") tokenPartnerId else (prefs.getString("partner_id", "default_partner") ?: "default_partner")

        val deviceId = getKioskDeviceId()
        val identifier = if (deviceId.isNotEmpty()) deviceId else getMacAddress()
        val wsUrl = "${wsBaseUrl(serverDomain)}/kiosk/ws/tv/$partnerId/$identifier"
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        // ── Close stale socket FIRST to avoid ghost connections ───────────────
        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (e: Exception) {
            Log.w(TAG, "Could not close previous WebSocket: ${e.message}")
        }
        webSocket = null
        isReconnecting = false

        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        val fingerprint = getDeviceFingerprint()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", fingerprint)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket CONNECTED! code=${response.code} protocol=${response.header("Sec-WebSocket-Protocol")}")
                isConnected = true
                reconnectAttempts = 0
                isReconnecting = false
                updateForegroundStatus()
                startHeartbeatLoop()
                flushOutboxEvents()
                flushOfflineSessions()

                if (offlineSecondsAccumulated > 0) {
                    reconcileOfflineTime(offlineSecondsAccumulated)
                } else {
                    serviceScope.launch {
                        syncStateWithServer()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // TV is publisher-only over WebSocket in this mode.
                // Authoritative state is pulled from HTTP sync loop.
                Log.d(TAG, "WS Message ignored (publisher-only mode)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "⚠️ WebSocket CLOSED: code=$code reason='$reason'")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code ?: -1
                val httpMsg = response?.message ?: "no response"
                Log.e(TAG, "❌ WebSocket FAILED: ${t.javaClass.simpleName}: '${t.message}' | HTTP $httpCode $httpMsg")
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        if (isConnected) {
            isConnected = false
            Log.w(TAG, "⚠️ WebSocket disconnected. Starting local offline ticker...")
            startLocalTicker()
            stopHeartbeatLoop()
            updateForegroundStatus()
        }
        triggerReconnect()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                if (!isConnected) continue
                val payload = JSONObject().apply {
                    put("event", "tv_heartbeat")
                    put("data", JSONObject().apply {
                        put("device_id", getKioskDeviceId())
                        put("mac_address", getMacAddress())
                        put("status", deviceStatus)
                        put("seconds_remaining", localSecondsRemaining)
                        put("seconds_elapsed", localSecondsElapsed)
                        put("seconds_elapsed_overtime", localSecondsElapsedOvertime)
                        put("billing_mode", billingMode)
                        put("current_game", currentGame ?: "")
                        put("timestamp", System.currentTimeMillis())
                    })
                }
                val sent = webSocket?.send(payload.toString()) == true
                if (!sent) {
                    queueOutboxEvent("tv_heartbeat", payload.toString())
                    handleDisconnect()
                }
            }
        }
    }

    private fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun loadOutboxEvents(): MutableList<JSONObject> {
        val raw = outboxPrefs.getString(OUTBOX_KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) {
                list.add(array.getJSONObject(i))
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveOutboxEvents(events: List<JSONObject>) {
        val array = JSONArray()
        events.forEach { array.put(it) }
        outboxPrefs.edit().putString(OUTBOX_KEY, array.toString()).apply()
    }

    private fun queueOutboxEvent(eventType: String, payload: String) {
        synchronized(outboxLock) {
            val events = loadOutboxEvents()
            events.add(
                JSONObject().apply {
                    put("event", eventType)
                    put("payload", payload)
                    put("queued_at", System.currentTimeMillis())
                }
            )
            while (events.size > MAX_OUTBOX_EVENTS) {
                events.removeAt(0)
            }
            saveOutboxEvents(events)
        }
        updateForegroundStatus()
    }

    private fun queuedOutboxCount(): Int {
        synchronized(outboxLock) {
            return loadOutboxEvents().size
        }
    }

    private fun flushOutboxEvents() {
        if (!isConnected) return
        synchronized(outboxLock) {
            val existing = loadOutboxEvents()
            if (existing.isEmpty()) return
            val now = System.currentTimeMillis()
            val remaining = mutableListOf<JSONObject>()
            existing.forEach { event ->
                val eventType = event.optString("event")
                val queuedAt = event.optLong("queued_at", now)
                val age = now - queuedAt
                // A locally-initiated "start session" command must never be replayed after the
                // fact — if it sat unsent for more than a few seconds, the operator has almost
                // certainly moved on (or the session was stopped in the meantime). Blindly
                // resending it on reconnect would silently re-start a session the moment it's
                // stopped. Drop stale start commands instead of sending them.
                if (eventType == "start_manual_session" && age > STALE_START_EVENT_TTL_MS) {
                    Log.w(TAG, "Dropping stale queued start_manual_session (${age}ms old) — not replaying")
                    return@forEach
                }
                val payload = event.optString("payload")
                val sent = payload.isNotBlank() && (webSocket?.send(payload) == true)
                if (!sent) {
                    remaining.add(event)
                }
            }
            saveOutboxEvents(remaining)
        }
        updateForegroundStatus()
    }

    private fun sendOrQueueEvent(eventType: String, payload: JSONObject) {
        val message = payload.toString()
        val sent = isConnected && (webSocket?.send(message) == true)
        if (!sent) {
            queueOutboxEvent(eventType, message)
        }
    }

    private fun triggerReconnect() {
        // Guard: never schedule two parallel reconnect timers
        if (isReconnecting) {
            Log.d(TAG, "Reconnect already scheduled — skipping duplicate")
            return
        }
        isReconnecting = true
        val delay = 2000L
        Log.d(TAG, "⏳ Scheduling WebSocket reconnect in ${delay}ms (attempt #$reconnectAttempts)")
        reconnectAttempts++
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            connectWebSocket()
        }, delay)
    }

    private fun startLocalTicker() {
        localTickerJob?.cancel()
        if (deviceStatus == "LOCKED") return

        localTickerJob = serviceScope.launch {
            while (deviceStatus != "LOCKED") {
                delay(1000)
                if (!isConnected) {
                    offlineSecondsAccumulated++
                }
                localSecondsElapsed++

                // Apply gentle drift correction toward the server's last known value.
                // Only nudge by 1 second every DRIFT_CORRECTION_INTERVAL_TICKS so the
                // on-screen clock never appears to hurry, jump or stutter.
                ticksSinceDriftCorrection++
                val applyDriftThisTick = ticksSinceDriftCorrection >= DRIFT_CORRECTION_INTERVAL_TICKS
                if (applyDriftThisTick) ticksSinceDriftCorrection = 0

                if (applyDriftThisTick && elapsedDrift != 0) {
                    val step = if (elapsedDrift > 0) 1 else -1
                    localSecondsElapsed += step
                    elapsedDrift -= step
                }

                if (isAdminMode.value) {
                    activeBillingKes = 0.0
                } else if (deviceStatus == "ACTIVE") {
                    if (billingMode == "PREPAID") {
                        localSecondsRemaining--
                        if (applyDriftThisTick && remainingDrift != 0) {
                            val step = if (remainingDrift > 0) 1 else -1
                            localSecondsRemaining += step
                            remainingDrift -= step
                        }
                        activeSecondsRemaining = localSecondsRemaining
                        if (localSecondsRemaining <= 0) {
                            if (localAllowOvertime) {
                                deviceStatus = "OVERTIME"
                                localSecondsElapsedOvertime = Math.abs(localSecondsRemaining)
                                localSecondsRemaining = 0
                            } else {
                                deviceStatus = "LOCKED"
                                localSessionEndedPendingServerConfirm = true
                                withContext(Dispatchers.Main) {
                                    // Call the stop endpoint so server also marks session ended.
                                    // This prevents syncStateWithServer() from seeing server=ACTIVE
                                    // and re-unlocking the screen.
                                    endActiveSessionLocallyAndOnServer()
                                }
                                break
                            }
                        }
                    } else {
                        val elapsedMins = localSecondsElapsed / 60.0
                        activeBillingKes = Math.max(minCharge, elapsedMins * ratePerMinute)
                    }
                } else if (deviceStatus == "OVERTIME") {
                    localSecondsElapsedOvertime++
                    if (applyDriftThisTick && overtimeDrift != 0) {
                        val step = if (overtimeDrift > 0) 1 else -1
                        localSecondsElapsedOvertime += step
                        overtimeDrift -= step
                    }
                    if (localSecondsElapsedOvertime % 60 == 0) {
                        activeBillingKes += ratePerMinute
                    }
                }

                withContext(Dispatchers.Main) {
                    val mockJson = JSONObject().apply {
                        put("status", deviceStatus)
                        put("seconds_remaining", localSecondsRemaining)
                        put("seconds_elapsed", localSecondsElapsed)
                        put("seconds_elapsed_overtime", localSecondsElapsedOvertime)
                        put("is_admin_mode", isAdminMode.value)
                        put("active_billing_kes", activeBillingKes)
                        put("billing_mode", billingMode)
                        if (currentGame != null) put("current_game", currentGame)
                    }
                    updateOverlayData(mockJson)
                    
                    // Save local ticks to cache for blackout recovery
                    val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("last_sync_data", mockJson.toString())
                        .putLong("last_sync_time", System.currentTimeMillis())
                        .apply()
                }
            }
        }
    }

    private fun stopLocalTicker() {
        localTickerJob?.cancel()
        localTickerJob = null
    }

    private fun startScreenshotJob() {
        screenshotJob?.cancel()
        screenshotJob = serviceScope.launch {
            while (true) {
                delay(300000) // 5 minutes
                if (deviceStatus == "ACTIVE" || deviceStatus == "OVERTIME") {
                    captureAndUploadScreenshot()
                }
            }
        }
    }

    private fun stopScreenshotJob() {
        screenshotJob?.cancel()
        screenshotJob = null
    }

    private fun captureAndUploadScreenshot() {
        val proj = mediaProjection ?: return
        val deviceId = getKioskDeviceId()
        val identifier = if (deviceId.isNotEmpty()) deviceId else getMacAddress()
        val width = 640
        val height = 360
        
        try {
            val imageReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val surface = imageReader.surface
            
            val virtualDisplay = proj.createVirtualDisplay(
                "ScreenshotCapture",
                width, height, 240,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val image = imageReader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width
                        
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()
                        
                        val croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        val stream = java.io.ByteArrayOutputStream()
                        croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
                        val jpegBytes = stream.toByteArray()
                        
                        uploadScreenshotBytes(identifier, jpegBytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot draw failed: ${e.message}")
                } finally {
                    virtualDisplay.release()
                    imageReader.close()
                }
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot setup failed: ${e.message}")
        }
    }

    private fun uploadScreenshotBytes(deviceIdOrMac: String, jpegBytes: ByteArray) {
        val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$deviceIdOrMac/screenshots"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "screenshot", "screenshot.jpg",
                RequestBody.create("image/jpeg".toMediaType(), jpegBytes)
            )
            .build()
            
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        val fingerprint = getDeviceFingerprint()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", fingerprint)
            .post(requestBody)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun reconcileOfflineTime(secs: Int) {
        val mac = getMacAddress()
        val deviceId = getKioskDeviceId()
        val payload = JSONObject().apply {
            put("event", "reconcile_offline_time")
            put("data", JSONObject().apply {
                put("device_id", deviceId)
                put("mac_address", mac)
                put("offline_seconds", secs)
                put("status", deviceStatus)
                put("billing_mode", billingMode)
                put("current_game", currentGame ?: "")
                val baseMins = if (billingMode == "PREPAID") (localSecondsRemaining + localSecondsElapsed) / 60 else 0
                put("base_minutes", baseMins)
            })
        }
        sendOrQueueEvent("reconcile_offline_time", payload)
    }

    private fun sendStartManualSessionOverWebSocket(mode: String, game: String, mins: Int) {
        val mac = getMacAddress()
        val deviceId = getKioskDeviceId()
        val payload = JSONObject().apply {
            put("event", "start_manual_session")
            put("data", JSONObject().apply {
                put("device_id", deviceId)
                put("mac_address", mac)
                put("billing_mode", mode)
                put("current_game", game)
                put("base_minutes", mins)
                // Server-side defense in depth: lets the backend reject this command if it
                // sits in transit/queued too long and is replayed well after the fact.
                put("client_ts", System.currentTimeMillis())
            })
        }
        sendOrQueueEvent("start_manual_session", payload)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun handleSocketMessage(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val event = json.optString("event")
            val data = json.optJSONObject("data") ?: return

            when (event) {
                "lock_station" -> {
                    Handler(Looper.getMainLooper()).post {
                        deviceStatus = "LOCKED"
                        val invoice = data.optJSONObject("invoice")
                        lockDevice(invoice)
                    }
                }
                "session_started", "session_updated", "session_continued", "session_resumed" -> {
                    Handler(Looper.getMainLooper()).post {
                        val oldStatus = deviceStatus
                        cacheDeviceState(data)
                        if (!isLiveSession(data)) {
                            if (oldStatus != "LOCKED" && oldStatus != "PENDING") {
                                val invoice = data.optJSONObject("last_invoice") ?: data.optJSONObject("invoice")
                                lockDevice(invoice)
                            } else {
                                val syncIntent = Intent("com.example.psbill.ACTION_SYNC").apply {
                                    putExtra("sync_data", data.toString())
                                }
                                sendBroadcast(syncIntent)
                            }
                        } else if (oldStatus == "LOCKED" || oldStatus == "PENDING" || !isOverlayAttached) {
                            unlockDevice(data)
                        } else {
                            updateTimerOverlay(data)
                        }
                    }
                }
                "unlock_station", "admin_unlock" -> {
                    Handler(Looper.getMainLooper()).post {
                        sessionStartTimestamp = System.currentTimeMillis()
                        val oldStatus = deviceStatus
                        cacheDeviceState(data)
                        if (!isLiveSession(data)) {
                            val invoice = data.optJSONObject("last_invoice") ?: data.optJSONObject("invoice")
                            lockDevice(invoice)
                        } else if (oldStatus == "LOCKED" || !isOverlayAttached) {
                            unlockDevice(data)
                        } else {
                            updateTimerOverlay(data)
                        }
                    }
                }
                "update_timer" -> {
                    Handler(Looper.getMainLooper()).post {
                        cacheDeviceState(data)
                        if (isLiveSession(data)) {
                            updateTimerOverlay(data)
                        } else {
                            val invoice = data.optJSONObject("last_invoice") ?: data.optJSONObject("invoice")
                            lockDevice(invoice)
                        }
                    }
                }
                "reconcile_confirm" -> {
                    offlineSecondsAccumulated = 0
                    Handler(Looper.getMainLooper()).post {
                        cacheDeviceState(data)
                        if (isLiveSession(data)) {
                            updateTimerOverlay(data)
                        } else {
                            val invoice = data.optJSONObject("last_invoice") ?: data.optJSONObject("invoice")
                            lockDevice(invoice)
                        }
                    }
                }
                "start_stream" -> {
                    val streamIntent = Intent(this, LockActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("action", "request_projection")
                    }
                    startActivity(streamIntent)
                }
                "stop_stream" -> {
                    stopScreenStreaming()
                }
                "device_revoked" -> {
                    Handler(Looper.getMainLooper()).post {
                        handleRevocationOrDeactivation()
                    }
                }
                "sleep_station" -> {
                    Handler(Looper.getMainLooper()).post {
                        showSleepOverlay()
                    }
                }
                "wakeup_station" -> {
                    Handler(Looper.getMainLooper()).post {
                        hideSleepOverlay()
                    }
                }

                // ── Loser Pay Events ─────────────────────────────────────────
                "loser_pay_started" -> {
                    Handler(Looper.getMainLooper()).post {
                        val status = data.optString("status", "PENDING")
                        val isSupervised = data.optBoolean("is_supervised", false)
                        
                        isLoserPaySession = true
                        loserPayToken = data.optString("token", "")
                        loserPayPlayerA.value = data.optString("player_a", "Player A")
                        loserPayPlayerB.value = data.optString("player_b", "Player B")
                        loserPayScoreA.clear()
                        loserPayScoreB.clear()
                        loserPayAmountA.value = 0.0
                        loserPayAmountB.value = 0.0
                        childSessionLogs.clear()
                        childSessionRound.value = 1
                        childSessionStartTime = System.currentTimeMillis()
                        
                        deviceStatus = status
                        
                        // Broadcast sync payload to LockActivity to display QR code if not supervised
                        val serverDomainString = serverDomain
                        val qrLink = data.optString("qr_link").ifBlank {
                            "https://$serverDomainString/api/v1/kiosk/loser-pay/play/$loserPayToken"
                        }
                        val syncJson = JSONObject().apply {
                            put("status", status)
                            put("loser_pay_token", loserPayToken)
                            put("qr_link", qrLink)
                            put("billing_mode", data.optString("billing_mode", "POSTPAID"))
                            put("current_game", data.optString("game_name", "FIFA"))
                            
                            val lpObj = JSONObject().apply {
                                put("player_a", loserPayPlayerA.value)
                                put("player_b", loserPayPlayerB.value)
                                put("game_name", data.optString("game_name", "FIFA"))
                                put("status", status)
                                put("is_supervised", isSupervised)
                            }
                            put("loser_pay_session", lpObj)
                        }
                        if (status == "ACTIVE") {
                            // Both players accepted — unlock the device and show timer
                            unlockDevice(syncJson)
                        } else {
                            // PENDING — show QR screen in LockActivity
                            val syncIntent = Intent("com.example.psbill.ACTION_SYNC_DATA").apply {
                                putExtra("sync_data", syncJson.toString())
                            }
                            sendBroadcast(syncIntent)
                        }

                        Log.d(TAG, "[LoserPay] Session started, status: $status, supervised: $isSupervised")
                    }
                }

                "round_result" -> {
                    // Fired when a round/child-session ends
                    // data: { winner, loser, is_draw, amount_kes, round_number, ended_at }
                    Handler(Looper.getMainLooper()).post {
                        val winner  = data.optString("winner", "")
                        val loser   = data.optString("loser", "")
                        val isDraw  = data.optBoolean("is_draw", false)
                        val amount  = data.optDouble("amount_kes", 0.0)
                        val roundNo = data.optInt("round_number", childSessionRound.value)

                        // Record round in log
                        childSessionLogs.add(data)

                        // Update score tallies
                        val pA = loserPayPlayerA.value
                        val pB = loserPayPlayerB.value
                        when {
                            isDraw -> { loserPayScoreA.add('D'); loserPayScoreB.add('D') }
                            winner == pA -> { loserPayScoreA.add('W'); loserPayScoreB.add('L') }
                            winner == pB -> { loserPayScoreA.add('L'); loserPayScoreB.add('W') }
                            else -> { loserPayScoreA.add('?'); loserPayScoreB.add('?') }
                        }

                        // Track who owes money
                        if (!isDraw && loser.isNotEmpty()) {
                            if (loser == pA) loserPayAmountA.value += amount
                            else loserPayAmountB.value += amount
                        }

                        // Advance to next round
                        childSessionRound.value = roundNo + 1
                        childSessionStartTime = System.currentTimeMillis()

                        Log.d(TAG, "[LoserPay] Round $roundNo done. ${if(isDraw) "DRAW" else "$winner wins"}. Amount KES $amount")

                        // Brief sports-ticker popup showing result
                        showRoundResultPopup(winner, loser, isDraw, amount, roundNo)
                    }
                }

                "loser_pay_ended" -> {
                    // Final totals from server
                    Handler(Looper.getMainLooper()).post {
                        loserPayAmountA.value = data.optDouble("amount_player_a", loserPayAmountA.value)
                        loserPayAmountB.value = data.optDouble("amount_player_b", loserPayAmountB.value)
                        Log.d(TAG, "[LoserPay] Session ended. A owes KES ${loserPayAmountA.value}, B owes KES ${loserPayAmountB.value}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling socket message: ${e.message}")
        }
    }

    // Brief sports-ticker style popup that auto-dismisses after 4 seconds
    @RequiresApi(Build.VERSION_CODES.M)
    private fun showRoundResultPopup(winner: String, loser: String, isDraw: Boolean, amount: Double, round: Int) {
        if (!Settings.canDrawOverlays(this)) return

        var popupView: ComposeView? = null
        val popupParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 60
        }

        popupView = ComposeView(this).apply {
            setContent {
                val visible = remember { mutableStateOf(true) }
                androidx.compose.animation.AnimatedVisibility(
                    visible = visible.value,
                    enter = androidx.compose.animation.slideInVertically { it } +
                            androidx.compose.animation.fadeIn(),
                    exit  = androidx.compose.animation.slideOutVertically { it } +
                            androidx.compose.animation.fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .background(
                                brush = Brush.horizontalGradient(
                                    if (isDraw) listOf(Color(0xFF78350F), Color(0xFFB45309))
                                    else listOf(Color(0xFF14532D), Color(0xFF15803D))
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("R$round", color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(0.25f)))
                        Text(
                            text = if (isDraw) "⚖ DRAW" else "🏆 $winner WINS",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (!isDraw && amount > 0) {
                            Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(0.25f)))
                            Text("$loser pays KES ${amount.toInt()}", color = Color(0xFFFCA5A5), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        val lifecycleOwner = CustomLifecycleOwner(); lifecycleOwner.start()
        popupView.setViewTreeLifecycleOwner(lifecycleOwner)
        popupView.setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
        popupView.setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())

        try {
            windowManager?.addView(popupView, popupParams)
            // Auto-dismiss after 4 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                try { windowManager?.removeView(popupView) } catch (e: Exception) {}
            }, 4000)
        } catch (e: Exception) {
            Log.e(TAG, "Could not show round result popup: ${e.message}")
        }
    }


    private fun cacheDeviceState(data: JSONObject) {
        val oldStatus = deviceStatus
        deviceStatus = normalizedStatus(data)
        // Only clear the pending flag when a new START event comes via WebSocket (unlockDevice path),
        // not during a sync that may still show stale ACTIVE state. Flag is cleared in unlockDevice().

        if ((oldStatus == "LOCKED" || oldStatus == "STOPPED" || oldStatus == "ENDED") &&
            (deviceStatus == "ACTIVE" || deviceStatus == "OVERTIME")) {
            if (currentSessionIdempotencyKey == null) {
                currentSessionIdempotencyKey = java.util.UUID.randomUUID().toString()
                currentSessionStartTimeMs = System.currentTimeMillis()
                Log.d(TAG, "New session detected. Generated idempotency key: $currentSessionIdempotencyKey")
            }
        }
        val wasLive = oldStatus == "ACTIVE" || oldStatus == "OVERTIME"
        val isLive = deviceStatus == "ACTIVE" || deviceStatus == "OVERTIME"
        if (wasLive && isLive) {
            // Same session still running on screen — local ticker stays authoritative.
            // Never snap the visible number to the server's value (that looks like a
            // jump/hurry). Just note the difference; the ticker will nudge toward it
            // by a fraction of a second at a time, imperceptibly, until it converges.
            remainingDrift = data.optInt("seconds_remaining") - localSecondsRemaining
            elapsedDrift = data.optInt("seconds_elapsed") - localSecondsElapsed
            overtimeDrift = data.optInt("seconds_elapsed_overtime") - localSecondsElapsedOvertime
        } else {
            // Session boundary (start, end, pause/resume) — nothing is visibly ticking
            // yet, so it's safe (and correct) to snap exactly to the server's value.
            localSecondsRemaining = data.optInt("seconds_remaining")
            localSecondsElapsed = data.optInt("seconds_elapsed")
            localSecondsElapsedOvertime = data.optInt("seconds_elapsed_overtime")
            remainingDrift = 0
            elapsedDrift = 0
            overtimeDrift = 0
        }
        localAllowOvertime = data.optBoolean("allow_overtime", true)
        activeBillingKes = data.optDouble("active_billing_kes")
        minCharge = data.optDouble("minimum_charge_kes", 40.0)
        ratePerMinute = data.optDouble("rate_per_minute_kes", 3.0)
        billingMode = data.optString("billing_mode", "POSTPAID")
        activeBillingMode.value = billingMode
        activeSecondsRemaining = localSecondsRemaining
        currentGame = if (data.has("current_game") && !data.isNull("current_game")) data.getString("current_game") else null
        if (!data.optBoolean("has_ended_session", false)) {
            clearPersistedEndedInvoice()
        }
        
        isAdminMode.value = data.optBoolean("is_admin_mode", false)
        activeBillingAmount.value = data.optDouble("active_billing_kes", 0.0)
        activeBillingMode.value = data.optString("billing_mode", "POSTPAID")

        // Cache custom background wallpaper url in SharedPreferences
        val lockBg = data.optString("lock_bg_stream_url", data.optString("lock_bg_url", ""))
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        edit.putString("lock_bg_url", lockBg)

        // Cache device_name and arcade_name for LockActivity UI
        val devName = data.optString("device_name", data.optString("name", ""))
        if (devName.isNotEmpty()) {
            edit.putString("device_name", devName)
        }
        val arcName = data.optString("arcade_name", "")
        if (arcName.isNotEmpty()) {
            edit.putString("arcade_name", arcName)
        }
        edit.apply()

        // Handle remote sleep/wakeup state sync
        val isAsleep = data.optBoolean("is_asleep", false)
        if (isAsleep && !isSleeping) {
            showSleepOverlay()
        } else if (!isAsleep && isSleeping) {
            hideSleepOverlay()
        }

        // Update session lock state if device reset
        if (data.optBoolean("is_reset", false)) {
            prefs.edit().putBoolean("session_locked", false).apply()
        }

        // Sync local ticker state
        if (deviceStatus != "LOCKED" && localTickerJob == null) {
            startLocalTicker()
        } else if (deviceStatus == "LOCKED" && localTickerJob != null) {
            stopLocalTicker()
        }
    }

    private suspend fun syncStateWithServer() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        
        val deviceId = getKioskDeviceId()
        val identifier = if (deviceId.isNotEmpty()) deviceId else getMacAddress()
        
        // Append local session state to query parameters to sync with the backend
        val status = deviceStatus
        val secsRem = localSecondsRemaining
        val secsElapsed = localSecondsElapsed
        val secsOvertime = localSecondsElapsedOvertime
        val billingKes = activeBillingKes
        val curGame = currentGame ?: ""
        val hdmiConnectedFlag = localHdmiConnected
        
        val uptimeSeconds = (android.os.SystemClock.elapsedRealtime() / 1000).toInt()
        val syncUrl = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$identifier/sync" +
                "?status=$status" +
                "&seconds_remaining=$secsRem" +
                "&seconds_elapsed=$secsElapsed" +
                "&seconds_elapsed_overtime=$secsOvertime" +
                "&active_billing_kes=$billingKes" +
                "&current_game=${java.net.URLEncoder.encode(curGame, "UTF-8")}" +
                "&hdmi_connected=$hdmiConnectedFlag" +
                "&uptime_seconds=$uptimeSeconds"
        
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        val fingerprint = getDeviceFingerprint()

        val request = Request.Builder()
            .url(syncUrl)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", fingerprint)
            .build()
        
        withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                        withContext(Dispatchers.Main) {
                            handleRevocationOrDeactivation()
                        }
                        return@use
                    }
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    try {
                        val data = JSONObject(body)
                        
                        // Cache response and sync time in SharedPreferences
                        prefs.edit()
                            .putString("last_sync_data", body)
                            .putLong("last_sync_time", System.currentTimeMillis())
                            .apply()
                        
                        // Send local broadcast to update LockActivity UI
                        val syncIntent = Intent("com.example.psbill.ACTION_SYNC_DATA").apply {
                            putExtra("sync_data", body)
                        }
                        sendBroadcast(syncIntent)

                        withContext(Dispatchers.Main) {
                            flushOfflineSessions()
                            val oldStatus = deviceStatus
                            cacheDeviceState(data)
                            if (!isLiveSession(data)) {
                                // Server confirms session is not live — clear the pending flag
                                localSessionEndedPendingServerConfirm = false
                                removeTimerOverlay()
                                if (oldStatus != "LOCKED") {
                                    val lastInvoice = data.optJSONObject("last_invoice")
                                    lockDevice(lastInvoice)
                                }
                            } else if (localSessionEndedPendingServerConfirm) {
                                // TV ended the session locally but server hasn't processed the stop yet.
                                // Do NOT re-unlock — just wait for the next sync cycle.
                                Log.d(TAG, "syncState: server still shows ACTIVE but local session ended; holding lock screen.")
                            } else {
                                if (oldStatus == "LOCKED" || !isOverlayAttached) {
                                    unlockDevice(data)
                                } else {
                                    updateTimerOverlay(data)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncStateWithServer: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun lockDevice(invoiceJson: JSONObject? = null) {
        val shouldPersistEndedInvoice = invoiceJson != null ||
            localSessionEndedPendingServerConfirm ||
            activeBillingKes > 0.0 ||
            localSecondsElapsed > 0 ||
            localSecondsElapsedOvertime > 0
        if (shouldPersistEndedInvoice) {
            persistEndedInvoice(invoiceJson)
        } else {
            clearPersistedEndedInvoice()
        }
        removeTimerOverlay()
        stopScreenshotJob()
        stopLocalTicker()
        
        saveEndedSessionLocally()

        // Set session lock flag to prevent new sessions until admin resets
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("session_locked", true)
            .remove("last_sync_data")
            .remove("last_sync_time")
            .apply()

        // Always launch the LockActivity screen
        launchLockScreen(invoiceJson)

        // Force a sync with server immediately so LockActivity has the latest ended session data
        serviceScope.launch {
            syncStateWithServer()
        }

        // Reset loser pay state for next session
        isLoserPaySession = false
        loserPayToken = null
        loserPayScoreA.clear()
        loserPayScoreB.clear()
        loserPayAmountA.value = 0.0
        loserPayAmountB.value = 0.0
        childSessionRound.value = 0
        childSessionLogs.clear()
    }

    private fun launchLockScreen(invoiceJson: JSONObject?) {
        val intent = Intent(this, LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (invoiceJson != null) {
                putExtra("show_invoice", true)
                putExtra("amount_charged", invoiceJson.optDouble("amount_charged_kes", 0.0))
                putExtra("duration_minutes", invoiceJson.optInt("duration_minutes", 0))
                putExtra("game_name", invoiceJson.optString("game_name", ""))
                putExtra("billing_mode", invoiceJson.optString("billing_mode", ""))
            }
        }
        startActivity(intent)
        startLockScreenWatchdog()
    }

    private var lockScreenWatchdogHandler: android.os.Handler? = null
    private val lockScreenWatchdogRunnable = object : Runnable {
        override fun run() {
            if (deviceStatus != "LOCKED" && !localSessionEndedPendingServerConfirm) {
                // Device is no longer locked — stop watchdog
                lockScreenWatchdogHandler = null
                return
            }
            if (!LockActivity.isLockActivityVisible) {
                // Lock screen was pushed to background (HOME, YouTube, network panel, etc.)
                Log.w(TAG, "Watchdog: lock screen not visible while device is LOCKED — relaunching")
                val relaunch = Intent(this@KioskService, LockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                startActivity(relaunch)
            }
            lockScreenWatchdogHandler?.postDelayed(this, 800)
        }
    }

    private fun startLockScreenWatchdog() {
        lockScreenWatchdogHandler?.removeCallbacks(lockScreenWatchdogRunnable)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lockScreenWatchdogHandler = handler
        handler.postDelayed(lockScreenWatchdogRunnable, 800)
    }

    private fun stopLockScreenWatchdog() {
        lockScreenWatchdogHandler?.removeCallbacks(lockScreenWatchdogRunnable)
        lockScreenWatchdogHandler = null
    }

    private fun handleRevocationOrDeactivation() {
        Log.w(TAG, "Device registration is unavailable. Launching recovery screen.")
        removeTimerOverlay()
        stopScreenshotJob()
        stopLocalTicker()
        stopScreenStreaming()
        
        // Broadcast local unlock to close LockActivity if it's active
        val unlockIntent = Intent("com.example.psbill.ACTION_UNLOCK")
        sendBroadcast(unlockIntent)
        
        // Launch DeviceAuthActivity to show QR linking screen
        val intent = Intent(this, DeviceAuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun unlockDevice(deviceData: JSONObject) {
        clearPersistedEndedInvoice()
        // A confirmed new session start — clear the pending ended flag and stop watchdog
        localSessionEndedPendingServerConfirm = false
        activeDeviceStatus = "ACTIVE"  // Ensure any new LockActivity created after this exits immediately
        stopLockScreenWatchdog()
        // 1. Broadcast the unlock intent — LockActivity will call stopLockTask() + finish()
        val unlockIntent = Intent("com.example.psbill.ACTION_UNLOCK")
        sendBroadcast(unlockIntent)

        // 2. Give LockActivity time to process the broadcast and dismiss itself
        //    before we attach the timer overlay to the WindowManager.
        //    Without this delay the overlay appears behind/on top of LockActivity
        //    because sendBroadcast() is async (main-thread message queue).
        Handler(Looper.getMainLooper()).postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                showTimerOverlay(deviceData)
            }
            startScreenshotJob()
            launchHdmiInput(deviceData.optInt("hdmi_input", 1))
        }, 400) // 400ms is enough for LockActivity to finish() and be removed
    }

    private fun updateLocalHdmiConnected(isConnected: Boolean) {
        localHdmiConnected = isConnected
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("hdmi_connected", isConnected).apply()
    }

    private fun launchHdmiInput(hdmiNumber: Int = 1) {
        try {
            val tvInputManager = getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager
            if (tvInputManager != null) {
                val inputList = tvInputManager.tvInputList
                var hdmiInputId: String? = null
                var hdmiCount = 0
                for (info in inputList) {
                    Log.d(TAG, "TV Input found: id=${info.id}, type=${info.type}")
                    if (info.type == android.media.tv.TvInputInfo.TYPE_HDMI || info.id.contains("hdmi", ignoreCase = true)) {
                        hdmiCount++
                        if (hdmiCount == hdmiNumber) {
                            hdmiInputId = info.id
                            break
                        }
                    }
                }
                // Fall back to first HDMI port if the requested one wasn't found
                if (hdmiInputId == null) {
                    for (info in inputList) {
                        if (info.type == android.media.tv.TvInputInfo.TYPE_HDMI || info.id.contains("hdmi", ignoreCase = true)) {
                            hdmiInputId = info.id
                            break
                        }
                    }
                }
                
                if (hdmiInputId != null) {
                    val uri = android.media.tv.TvContract.buildChannelUriForPassthroughInput(hdmiInputId)
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    updateLocalHdmiConnected(true)
                    Log.d(TAG, "Launched HDMI input #$hdmiNumber: $hdmiInputId")
                    return
                }
            }
        } catch (e: Exception) {
            updateLocalHdmiConnected(false)
            Log.e(TAG, "Failed to launch TvInput HDMI: ${e.message}")
        }
        
        // Fallback: Try launching common HDMI/TV system intents
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://android.media.tv/passthrough"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "Launched fallback TV intent content://android.media.tv/passthrough")
        } catch (e: Exception) {
            updateLocalHdmiConnected(false)
            Log.e(TAG, "All HDMI launch fallbacks failed: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @OptIn(ExperimentalTvMaterial3Api::class)
    private fun showTimerOverlay(deviceData: JSONObject) {
        if (deviceStatus == "LOCKED" || LockActivity.isLockActivityVisible) {
            removeTimerOverlay()
            return
        }
        if (!Settings.canDrawOverlays(this)) return

        updateOverlayData(deviceData)
        if (isOverlayAttached) return

        if (sessionStartTimestamp == 0L) sessionStartTimestamp = System.currentTimeMillis()

        // 1. Meter Overlay (FLAG_NOT_TOUCHABLE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 32
            y = 32
        }

        overlayView = ComposeView(this).apply {
            setContent {
                val text   = timerText.value
                val isOver = isOvertime.value
                val isAdm  = isAdminMode.value
                val amt    = activeBillingAmount.value
                val mode   = activeBillingMode.value
                val isLP   = isLoserPaySession
                val scoreA = loserPayScoreA.toList()
                val scoreB = loserPayScoreB.toList()
                val pA     = loserPayPlayerA.value
                val pB     = loserPayPlayerB.value
                val round  = childSessionRound.value

                val gradientColors = when {
                    isAdm  -> listOf(Color(0x804F46E5), Color(0x806366F1))
                    isLP   -> listOf(Color(0x80B45309), Color(0x80D97706))  // amber for loser pay
                    isOver -> listOf(Color(0x80DC2626), Color(0x80EF4444))
                    else   -> listOf(Color(0x80059669), Color(0x8010B981))
                }

                Column(
                    modifier = Modifier
                        .background(
                            brush = Brush.horizontalGradient(gradientColors),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .border(1.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Main timer row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = when {
                                    isAdm  -> "FREE PLAY"
                                    isOver -> "OVERTIME"
                                    mode == "PREPAID" -> "TIME LEFT"
                                    else   -> "TIME ELAPSED"
                                },
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                            )
                            Text(
                                text = if (isAdm) "∞" else if (isOver) "+$text" else text,
                                color = Color.White,
                                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.3f)))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = when {
                                    isAdm -> "COST"
                                    mode == "PREPAID" -> "SPENT (PAID)"
                                    else -> "BILL AMOUNT"
                                },
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                            )
                            Text(
                                text = if (isAdm) "FREE" else "KES ${amt.toInt()}",
                                color = Color.White,
                                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // ── Loser Pay Score Tally (shown only when loser pay is active) ──
                    if (isLP && (scoreA.isNotEmpty() || scoreB.isNotEmpty() || round > 0)) {
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(0.25f)))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚔", fontSize = 9.sp)
                            // Player A tally
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(pA.take(6), color = Color.White.copy(0.7f), fontSize = 8.sp)
                                Text(":", color = Color.White.copy(0.4f), fontSize = 8.sp)
                                scoreA.forEach { r: Char ->
                                    Text(
                                        text = r.toString(),
                                        color = when {
                                            r == 'W' -> Color(0xFF4ADE80)
                                            r == 'L' -> Color(0xFFF87171)
                                            else     -> Color(0xFFFBBF24)
                                        },
                                        fontSize = 9.sp, fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            Text("|", color = Color.White.copy(0.3f), fontSize = 8.sp)
                            // Player B tally
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(pB.take(6), color = Color.White.copy(0.7f), fontSize = 8.sp)
                                Text(":", color = Color.White.copy(0.4f), fontSize = 8.sp)
                                scoreB.forEach { r: Char ->
                                    Text(
                                        text = r.toString(),
                                        color = when {
                                            r == 'W' -> Color(0xFF4ADE80)
                                            r == 'L' -> Color(0xFFF87171)
                                            else     -> Color(0xFFFBBF24)
                                        },
                                        fontSize = 9.sp, fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            if (round > 0) Text("R$round", color = Color(0xFFFBBF24), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        val lifecycleOwner = CustomLifecycleOwner()
        lifecycleOwner.start()
        overlayView!!.setViewTreeLifecycleOwner(lifecycleOwner)
        overlayView!!.setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
        overlayView!!.setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())

        windowManager?.addView(overlayView, params)
        isOverlayAttached = true

        // 2. Transparent back-key interceptor — 1dp focusable overlay that catches KEYCODE_BACK
        //    and ends the active session when the user presses Back on the TV remote.
        val keyParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlayButtonView = object : android.widget.FrameLayout(this@KioskService) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                        event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        showEndSessionConfirmationDialog()
                    } else {
                        endActiveSessionLocallyAndOnServer()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        overlayButtonView!!.setViewTreeLifecycleOwner(lifecycleOwner)
        overlayButtonView!!.setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
        overlayButtonView!!.setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())

        windowManager?.addView(overlayButtonView, keyParams)
        overlayButtonView!!.requestFocus()
        isButtonAttached = true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateTimerOverlay(deviceData: JSONObject) {
        if (deviceStatus == "LOCKED" || LockActivity.isLockActivityVisible) {
            removeTimerOverlay()
            return
        }
        if (!isOverlayAttached) {
            showTimerOverlay(deviceData)
        } else {
            updateOverlayData(deviceData)
        }
    }

    private fun updateOverlayData(deviceData: JSONObject) {
        val status = deviceData.optString("status")
        val mode = deviceData.optString("billing_mode", "POSTPAID")
        val minsRem = deviceData.optInt("seconds_remaining") / 60
        val secsRem = deviceData.optInt("seconds_remaining") % 60
        val elapsedMins = deviceData.optInt("seconds_elapsed_overtime") / 60
        val elapsedSecs = deviceData.optInt("seconds_elapsed_overtime") % 60
        val elapsedTotalMins = deviceData.optInt("seconds_elapsed") / 60
        val elapsedTotalSecs = deviceData.optInt("seconds_elapsed") % 60
        
        isAdminMode.value = deviceData.optBoolean("is_admin_mode", false)
        activeBillingAmount.value = deviceData.optDouble("active_billing_kes", 0.0)
        activeBillingMode.value = mode

        if (status == "OVERTIME") {
            isOvertime.value = true
            timerText.value = String.format("%02d:%02d", elapsedMins, elapsedSecs)
        } else {
            isOvertime.value = false
            if (mode == "PREPAID") {
                timerText.value = String.format("%02d:%02d", minsRem, secsRem)
            } else {
                timerText.value = String.format("%02d:%02d", elapsedTotalMins, elapsedTotalSecs)
            }
        }
    }

    private fun removeTimerOverlay() {
        dismissEndSessionConfirmationDialog()
        if (isOverlayAttached && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
            overlayView = null
            isOverlayAttached = false
        }
        if (isButtonAttached && overlayButtonView != null) {
            try {
                windowManager?.removeView(overlayButtonView)
            } catch (e: Exception) {}
            overlayButtonView = null
            isButtonAttached = false
        }
    }

    private fun endActiveSessionLocallyAndOnServer() {
        // Prevent sync loop from re-unlocking while the stop request is in flight
        localSessionEndedPendingServerConfirm = true
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val deviceId = getKioskDeviceId()
        val identifier = if (deviceId.isNotEmpty()) deviceId else getMacAddress()
        
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        val fingerprint = getDeviceFingerprint()
        
        val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$identifier/stop"
        
        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create("application/json".toMediaType(), "{}"))
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", fingerprint)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e(TAG, "Failed to end session on server: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    val durationMins = Math.max(1, localSecondsElapsed / 60)
                    val localInvoice = JSONObject().apply {
                        put("amount_charged_kes", activeBillingKes)
                        put("duration_minutes", durationMins)
                        put("game_name", currentGame ?: "Game")
                        put("billing_mode", billingMode)
                    }
                    lockDevice(localInvoice)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string() ?: ""
                    Log.d(TAG, "End session response: $body")
                    Handler(Looper.getMainLooper()).post {
                        deviceStatus = "LOCKED"
                        val invoice = if (resp.isSuccessful) {
                            try {
                                JSONObject(body)
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            val durationMins = Math.max(1, localSecondsElapsed / 60)
                            JSONObject().apply {
                                put("amount_charged_kes", activeBillingKes)
                                put("duration_minutes", durationMins)
                                put("game_name", currentGame ?: "Game")
                                put("billing_mode", billingMode)
                            }
                        }
                        lockDevice(invoice)
                    }
                }
            }
        })
    }

    private fun startScreenStreaming() {
        val proj = mediaProjection
        val ws = webSocket
        if (proj != null && ws != null) {
            streamEncoder = StreamEncoder(proj, ws)
            streamEncoder?.start()
        }
    }

    private fun stopScreenStreaming() {
        streamEncoder?.stop()
        streamEncoder = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }


    private fun registerScreenWakeReceiver() {
        if (screenWakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) return
                if (deviceStatus == "LOCKED" || localSessionEndedPendingServerConfirm) {
                    launchLockScreen(null)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            screenWakeReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register wake receiver: ${e.message}")
        }
    }

    private fun unregisterScreenWakeReceiver() {
        val receiver = screenWakeReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
        screenWakeReceiver = null
    }

    private fun persistEndedInvoice(invoiceJson: JSONObject?) {
        val payload = invoiceJson ?: JSONObject().apply {
            put("amount_charged_kes", activeBillingKes)
            put("duration_minutes", Math.max(1, localSecondsElapsed / 60))
            put("game_name", currentGame ?: "Game")
            put("billing_mode", billingMode)
        }
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("pending_ended_invoice", payload.toString())
            .putBoolean("has_pending_ended_invoice", true)
            .apply()
    }

    private fun clearPersistedEndedInvoice() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("pending_ended_invoice")
            .putBoolean("has_pending_ended_invoice", false)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeTimerOverlay()
        stopScreenshotJob()
        stopScreenStreaming()
        stopHeartbeatLoop()
        stopTokenVerificationLoop()
        stopSyncStateLoop()
        try {
            webSocket?.close(1000, "Service destroyed")
        } catch (e: Exception) {}
        reconnectHandler.removeCallbacksAndMessages(null)
        unregisterScreenWakeReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Gaming Lounge Kiosk Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val queued = queuedOutboxCount()
        val content = if (isConnected) {
            if (queued > 0) {
                "TV online • syncing $queued queued event(s)"
            } else {
                "TV online • real-time control active"
            }
        } else {
            if (queued > 0) {
                "TV offline • queued $queued event(s) for sync"
            } else {
                "TV offline • reconnecting to local/cloud control"
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lounge Kiosk Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateForegroundStatus() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun getMacAddress(): String {
        try {
            val all = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in all) {
                if (!nif.name.equals("wlan0", ignoreCase = true)) continue
                val macBytes = nif.hardwareAddress ?: return "02:00:00:00:00:00"
                val res1 = StringBuilder()
                for (b in macBytes) {
                    res1.append(String.format("%02X:", b))
                }
                if (res1.isNotEmpty()) {
                    res1.deleteCharAt(res1.length - 1)
                }
                return res1.toString()
            }
        } catch (ex: Exception) {}
        return "02:00:00:00:00:00"
    }

    private fun getDeviceFingerprint(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_ID"
        val mac = getMacAddress()
        val model = Build.MODEL ?: "UNKNOWN_MODEL"
        val rawFingerprint = androidId + mac + model
        return sha256(rawFingerprint)
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getKioskDeviceId(): String {
        try {
            val securePrefs = getEncryptedPrefs(this)
            val token = securePrefs.getString("auth_token", "") ?: ""
            if (token.isNotEmpty()) {
                val parts = token.split(".")
                if (parts.size >= 2) {
                    val payloadBytes = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
                    return payloadJson.optString("sub", "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JWT to get device ID: ${e.message}")
        }
        return ""
    }

    private fun getKioskPartnerId(): String {
        try {
            val securePrefs = getEncryptedPrefs(this)
            val token = securePrefs.getString("auth_token", "") ?: ""
            if (token.isNotEmpty()) {
                val parts = token.split(".")
                if (parts.size >= 2) {
                    val payloadBytes = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
                    return payloadJson.optString("partner_id", "default_partner")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JWT to get partner ID: ${e.message}")
        }
        return "default_partner"
    }

    private fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
        val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            "SecureKioskPrefs",
            masterKeyAlias,
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun startTokenVerificationLoop() {
        tokenVerifyJob?.cancel()
        tokenVerifyJob = serviceScope.launch {
            while (true) {
                delay(30000) // Every 30 seconds
                verifyTokenWithServer()
            }
        }
    }

    private fun stopTokenVerificationLoop() {
        tokenVerifyJob?.cancel()
        tokenVerifyJob = null
    }

    private fun verifyTokenWithServer() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        serverDomain = prefs.getString("server_domain", "https://api.ajiriwa.gidraf.dev") ?: "https://api.ajiriwa.gidraf.dev"
        
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        if (token.isEmpty()) {
            handleRevocationOrDeactivation()
            return
        }
        val fingerprint = getDeviceFingerprint()
        val url = "${apiBaseUrl(serverDomain)}/api/devices/verify-token"
        
        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create("application/json".toMediaType(), "{}"))
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", fingerprint)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e(TAG, "Failed to verify token: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403 || response.code == 404) {
                        Log.w(TAG, "Token verification failed with code ${response.code}. Redirecting to activation.")
                        Handler(Looper.getMainLooper()).post {
                            handleRevocationOrDeactivation()
                        }
                    }
                }
            }
        })
    }

    private fun startSyncStateLoop() {
        syncStateJob?.cancel()
        syncStateJob = serviceScope.launch {
            while (true) {
                syncStateWithServer()
                // TV is the timer authority; keep sync cadence tight so server/mobile/web
                // reflect TV state quickly and can enforce stale-device timeout reliably.
                delay(2_000)
            }
        }
    }

    private fun stopSyncStateLoop() {
        syncStateJob?.cancel()
        syncStateJob = null
    }

    private fun formatIsoTime(epochMs: Long): String {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        return df.format(java.util.Date(epochMs))
    }

    private fun saveEndedSessionLocally() {
        val idemKey = currentSessionIdempotencyKey
        if (idemKey == null) {
            Log.w(TAG, "No active session to save locally.")
            return
        }

        val startTimeMs = if (currentSessionStartTimeMs > 0L) currentSessionStartTimeMs else System.currentTimeMillis()
        val endTimeMs = System.currentTimeMillis()
        val durationMins = Math.max(1, ((endTimeMs - startTimeMs) / 60000).toInt())

        val sessionJson = JSONObject().apply {
            put("idempotency_key", idemKey)
            put("game_name", currentGame ?: "Game")
            put("billing_mode", billingMode)
            put("start_time", formatIsoTime(startTimeMs))
            put("end_time", formatIsoTime(endTimeMs))
            put("duration_minutes", durationMins)
            put("amount_charged_kes", activeBillingKes)
            put("payment_status", "UNPAID")
        }

        Log.d(TAG, "Saving ended session locally: $sessionJson")

        val offlinePrefs = getSharedPreferences("OfflineSessionsPrefs", Context.MODE_PRIVATE)
        val existingRaw = offlinePrefs.getString("sessions", "[]") ?: "[]"
        try {
            val array = JSONArray(existingRaw)
            array.put(sessionJson)
            offlinePrefs.edit().putString("sessions", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving offline session: ${e.message}")
        }

        // Clear active session identifiers
        currentSessionIdempotencyKey = null
        currentSessionStartTimeMs = 0L

        // Trigger sync of offline sessions
        flushOfflineSessions()
    }

    private fun flushOfflineSessions() {
        val offlinePrefs = getSharedPreferences("OfflineSessionsPrefs", Context.MODE_PRIVATE)
        val existingRaw = offlinePrefs.getString("sessions", "[]") ?: "[]"
        if (existingRaw == "[]") return

        val deviceId = getKioskDeviceId()
        val identifier = if (deviceId.isNotEmpty()) deviceId else getMacAddress()
        
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        val fingerprint = getDeviceFingerprint()

        val sessionsArray = try { JSONArray(existingRaw) } catch (e: Exception) { JSONArray() }
        if (sessionsArray.length() == 0) return

        val payload = JSONObject().apply {
            put("device_id", identifier)
            put("sessions", sessionsArray)
        }

        val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/sessions/batch-sync"
        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", fingerprint)
            .post(requestBody)
            .build()

        Log.d(TAG, "Syncing offline sessions: $payload")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Failed to sync offline sessions: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        Log.d(TAG, "Successfully batch-synced offline sessions: $body")
                        // Clear the sessions locally
                        offlinePrefs.edit().putString("sessions", "[]").apply()
                    } else {
                        Log.e(TAG, "Failed to batch-sync sessions on server. Code: ${resp.code}, Body: $body")
                    }
                }
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @OptIn(ExperimentalTvMaterial3Api::class)
    private fun showEndSessionConfirmationDialog() {
        if (isEndSessionDialogShowing) return
        isEndSessionDialogShowing = true

        val isPrepaid = activeBillingMode.value == "PREPAID"
        val secsLeft = activeSecondsRemaining

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        val lifecycleOwner = CustomLifecycleOwner()
        lifecycleOwner.start()

        val newDialogView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())

            setContent {
                val stopFocusRequester = remember { FocusRequester() }
                val continueFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    delay(100)
                    // Default focus on "Continue" so accidental BACK press doesn't stop session
                    continueFocusRequester.requestFocus()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.80f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .width(400.dp)
                            .background(Color(0xFF0F172A), RoundedCornerShape(20.dp))
                            .border(2.dp, Color(0xFFEF4444), RoundedCornerShape(20.dp))
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        // Title
                        Text(
                            text = "End Session?",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )

                        // Prepaid warning box
                        if (isPrepaid && secsLeft > 0) {
                            val minsLeft = secsLeft / 60
                            val secsRem = secsLeft % 60
                            val timeStr = if (minsLeft > 0) "${minsLeft}m ${secsRem}s" else "${secsRem}s"
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF7C2D12), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFF97316), RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "⚠  Prepaid Session",
                                        color = Color(0xFFFED7AA),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "You have $timeStr of paid time remaining. If you stop now, this time will be forfeited and the voucher will be deactivated.",
                                        color = Color(0xFFFEDCC0),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        } else if (!isPrepaid) {
                            Text(
                                text = "Your session will be stopped and you will be charged for the time used.",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        // Buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Continue playing — default focused
                            var isContinueFocused by remember { mutableStateOf(false) }
                            Button(
                                onClick = { dismissEndSessionConfirmationDialog() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isContinueFocused) Color(0xFF16A34A) else Color(0xFF15803D),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(continueFocusRequester)
                                    .onFocusChanged { isContinueFocused = it.isFocused }
                                    .border(2.dp, if (isContinueFocused) Color.White else Color.Transparent, RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Continue", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }

                            // Stop session
                            var isStopFocused by remember { mutableStateOf(false) }
                            Button(
                                onClick = {
                                    dismissEndSessionConfirmationDialog()
                                    endActiveSessionLocallyAndOnServer()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isStopFocused) Color(0xFFDC2626) else Color(0xFFEF4444),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(stopFocusRequester)
                                    .onFocusChanged { isStopFocused = it.isFocused }
                                    .border(2.dp, if (isStopFocused) Color.White else Color.Transparent, RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    if (isPrepaid) "Stop & Forfeit" else "Stop Session",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Text(
                            text = "Press BACK to continue your session",
                            color = Color(0xFF475569),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        val wrapper = object : android.widget.FrameLayout(this) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                // BACK dismisses the dialog and resumes the session (does NOT stop it)
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_DOWN) {
                    dismissEndSessionConfirmationDialog()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(newDialogView)
        }

        wrapper.setViewTreeLifecycleOwner(lifecycleOwner)
        wrapper.setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
        wrapper.setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())

        windowManager?.addView(wrapper, params)
        wrapper.requestFocus()
        endSessionDialogWrapper = wrapper
        endSessionDialogView = newDialogView
    }

    private fun dismissEndSessionConfirmationDialog() {
        if (!isEndSessionDialogShowing) return
        isEndSessionDialogShowing = false
        val wrapper = endSessionDialogWrapper
        if (wrapper != null) {
            try {
                windowManager?.removeView(wrapper)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dialog wrapper: ${e.message}")
            }
            endSessionDialogWrapper = null
        }
        endSessionDialogView = null
    }

    private class CustomLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this)
        fun start() { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }
    
    private class CustomViewModelStoreOwner : androidx.lifecycle.ViewModelStoreOwner {
        private val store = androidx.lifecycle.ViewModelStore()
        override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store
    }
    
    private class CustomSavedStateRegistryOwner : androidx.savedstate.SavedStateRegistryOwner {
        private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this).apply {
            currentState = androidx.lifecycle.Lifecycle.State.INITIALIZED
        }
        private val controller = androidx.savedstate.SavedStateRegistryController.create(this)
        
        init {
            controller.performAttach()
            controller.performRestore(null)
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        
        override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = controller.savedStateRegistry
    }

    private fun showSleepOverlay() {
        if (sleepOverlayView != null) return
        isSleeping = true
        removeTimerOverlay()
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            screenBrightness = 0.01f
        }

        val lifecycleOwner = CustomLifecycleOwner()
        lifecycleOwner.start()

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())

            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }

        try {
            windowManager?.addView(view, params)
            sleepOverlayView = view
            Log.d(TAG, "💤 TV Sleep overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing sleep overlay: ${e.message}")
        }
    }

    private fun hideSleepOverlay() {
        isSleeping = false
        val view = sleepOverlayView ?: return
        try {
            windowManager?.removeView(view)
            sleepOverlayView = null
            Log.d(TAG, "☀️ TV Sleep overlay removed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing sleep overlay: ${e.message}")
        }
        serviceScope.launch {
            syncStateWithServer()
        }
    }
}
