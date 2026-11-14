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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
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
    // Meter: hidden most of the time, shown at the start, a short peek every
    // 5 minutes, and always near the end. 0 = green, 1 = amber, 2 = red.
    private val meterVisible = mutableStateOf(true)
    private val meterLevel = mutableStateOf(0)
    // Continue in the BACK dialog shows the meter for a few seconds (elapsedRealtime)
    private var meterPeekUntil = 0L
    // Pay-first finale: the last 10 seconds as a big countdown, then "TIME UP"
    private val finaleText = mutableStateOf<String?>(null)
    private var finaleView: ComposeView? = null
    private var finaleHideJob: Job? = null
    // The player's top-up request (sent from the TV, approved by staff)
    private var lastTopupRequestStatus: String? = null
    private var lastTopupRequestAt: String? = null
    // Queue: a short pop-up when someone joins the queue for this screen.
    private val queueNotice = mutableStateOf<String?>(null)
    private var queueWaiting = 0
    private var lastQueueNoticeAt = 0L
    private var queueNoticeJob: Job? = null
    private var turnSeconds = 30 * 60
    private var syncStateJob: Job? = null
    private var heartbeatJob: Job? = null

    // ── Loser Pay State ──────────────────────────────────────────────────────
    // Compose state: the meter re-draws (and stays on) when a loser-pay match starts or ends
    private val loserPayActive = mutableStateOf(false)
    private var isLoserPaySession: Boolean
        get() = loserPayActive.value
        set(v) { loserPayActive.value = v }
    // When the current game (round) started, System.currentTimeMillis(); the meter counts from it
    private val loserPayRoundStartMs = mutableStateOf(0L)
    private var loserPaySyncSig = ""
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
    // Server's top-up counter we have applied (sent with every sync).
    private var lastTopupSeq = 0
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
    // Pay-after: past the turn while others wait, each second is "queued overtime",
    // billed at overtimeRate (0 = the normal rate). The player is told when it starts.
    private var overtimeRate = 0.0
    private var rushHour = false
    private var prepaidWarningShown = false
    private var queuedOvertimeSecs = 0
    private var turnWarningShown = false
    private var overtimeNoticeShown = false
    private var billingMode = "POSTPAID"
    private var currentGame: String? = null
    private var localHdmiConnected = true
    // Console watch: the HDMI input in use and its state (TvInputManager.INPUT_STATE_*;
    // -1 = not known, e.g. a box without TV inputs).
    private var hdmiInputId: String? = null
    private var hdmiNumber = 1
    private var hdmiState = -1
    private var consoleOffSince = 0L
    // The console went dark (off, standby, cable out) during this session: when it comes
    // back the HDMI input is opened again, whatever is in front (the TV often leaves the
    // input by itself when the cable is pulled, so there may be no cover to close).
    private var consoleLostInSession = false
    private var hdmiHadChild: Boolean? = null   // the console's CEC input exists on the port
    private var hdmiCallback: android.media.tv.TvInputManager.TvInputCallback? = null
    private val hdmiWatchHandler = Handler(Looper.getMainLooper())
    private val hdmiWatchRunnable = object : Runnable {
        override fun run() {
            checkConsole()
            hdmiWatchHandler.postDelayed(this, HDMI_POLL_MS)
        }
    }
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
        private const val HDMI_POLL_MS = 5_000L
        /** Loser pay: the game result pop-up stays this long, then fades. */
        private const val RESULT_POPUP_MS = 6_000L
        // Console off this long during a session -> cover the HDMI input (the TV's own
        // no-signal countdown is much longer, so a quick console restart is not covered).
        private const val CONSOLE_OFF_GRACE_MS = 20_000L
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
        hdmiNumber = prefs.getInt("hdmi_input_number", 1)
        hdmiInputId = prefs.getString(ConsoleOffActivity.KEY_INPUT_ID, null)
        startConsoleWatch()
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

    private var updateCheckJob: Job? = null

    private fun startUpdateChecks() {
        AppUpdater.restore(this)
        updateCheckJob?.cancel()
        updateCheckJob = serviceScope.launch {
            while (true) {
                AppUpdater.checkNow(this@KioskService)
                delay(30 * 60_000L)
            }
        }
    }

    private fun startAuthenticatedRuntime(prefs: android.content.SharedPreferences) {
        startUpdateChecks()
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
                        minCharge = minChg
                        ratePerMinute = ratePerMin
                        queuedOvertimeSecs = syncJson.optInt("queued_overtime_seconds", 0)
                        overtimeNoticeShown = queuedOvertimeSecs > 0
                        turnWarningShown = overtimeNoticeShown
                        activeBillingKes = postpaidBill()
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
                    queuedOvertimeSecs = 0
                    prepaidWarningShown = false
                    turnWarningShown = false
                    overtimeNoticeShown = false
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
                        val left = localSecondsRemaining
                        withContext(Dispatchers.Main) { updateFinale(left) }
                        if (localSecondsRemaining <= 0) {
                            // Pay-first: the screen locks when the paid time is used up.
                            // Play continues only after a top-up (bought before it runs out).
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
                    } else {
                        if (overchargeNow() && localSecondsElapsed > turnSeconds) queuedOvertimeSecs++
                        activeBillingKes = postpaidBill()
                    }
                } else if (deviceStatus == "OVERTIME" && billingMode == "PREPAID") {
                    // Pay-first never runs into overtime: lock (an old session, or one the
                    // server started in overtime). A top-up starts the time again.
                    deviceStatus = "LOCKED"
                    localSessionEndedPendingServerConfirm = true
                    withContext(Dispatchers.Main) { endActiveSessionLocallyAndOnServer() }
                    break
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
                        put("queued_overtime_seconds", queuedOvertimeSecs)
                        put("minimum_charge_kes", minCharge)
                        put("rate_per_minute_kes", ratePerMinute)
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
                "games_updated" -> {
                    sendBroadcast(Intent("com.example.psbill.ACTION_GAMES_UPDATED").setPackage(packageName))
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
                "remote_control" -> {
                    // pause / resume / remove from the dashboard: sync now (the reply carries "remote")
                    serviceScope.launch { syncStateWithServer() }
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
                        loserPayRoundStartMs.value = childSessionStartTime
                        loserPaySyncSig = ""
                        // Continue after a pause: keep the score and totals so far
                        if (data.optBoolean("resumed", false)) applyLoserPayState(data, resetClock = true)
                        
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
                            put("current_game", data.cleanStr("game_name", "FIFA"))
                            
                            val lpObj = JSONObject().apply {
                                put("player_a", loserPayPlayerA.value)
                                put("player_b", loserPayPlayerB.value)
                                put("game_name", data.cleanStr("game_name", "FIFA"))
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

                        // Track who owes money: the loser pays the game, a draw is split in half
                        if (data.has("total_amount_player_a") && data.has("total_amount_player_b")) {
                            loserPayAmountA.value = data.optDouble("total_amount_player_a", loserPayAmountA.value)
                            loserPayAmountB.value = data.optDouble("total_amount_player_b", loserPayAmountB.value)
                        } else if (isDraw) {
                            loserPayAmountA.value += data.optDouble("amount_player_a", amount / 2)
                            loserPayAmountB.value += data.optDouble("amount_player_b", amount / 2)
                        } else if (loser.isNotEmpty()) {
                            if (loser == pA) loserPayAmountA.value += amount
                            else loserPayAmountB.value += amount
                        }

                        // Advance to next round
                        childSessionRound.value = roundNo + 1
                        childSessionStartTime = System.currentTimeMillis()
                        loserPayRoundStartMs.value = childSessionStartTime
                        loserPaySyncSig = ""

                        Log.d(TAG, "[LoserPay] Round $roundNo done. ${if(isDraw) "DRAW" else "$winner wins"}. Amount KES $amount")

                        // Brief sports-ticker popup showing result
                        showRoundResultPopup(data, roundNo)
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

    // ── Loser pay: result pop-up + state from the sync ─────────────────────────

    /** Won / drawn / lost of player A (left) and B (right) from the local score lists. */
    private fun localStats(): Pair<IntArray, IntArray> {
        fun count(l: List<Char>) = intArrayOf(l.count { it == 'W' }, l.count { it == 'D' }, l.count { it == 'L' })
        return count(loserPayScoreA.toList()) to count(loserPayScoreB.toList())
    }

    /** "2021-11-14T10:00:00.123456" (UTC, no zone) -> epoch ms, 0 when unreadable. */
    private fun parseUtcMs(raw: String): Long {
        return try {
            var t = raw.trim().removeSuffix("Z")
            val plus = t.indexOf('+', 10)
            if (plus > 0) t = t.substring(0, plus)
            val dot = t.indexOf('.')
            if (dot > 0) t = t.substring(0, minOf(t.length, dot + 4)).padEnd(dot + 4, '0')
            val fmt = java.text.SimpleDateFormat(
                if (dot > 0) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss", Locale.US
            ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            fmt.parse(t)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * Rebuild the match (players, W/D/L per side, totals, current game) from the server's
     * loser_pay_session (sync) or a resumed loser_pay_started event. Keeps the TV in step
     * after a restart, a missed event or a Continue.
     */
    private fun applyLoserPayState(lp: JSONObject, resetClock: Boolean = false) {
        val a = lp.optString("player_a", loserPayPlayerA.value).ifBlank { "Player 1" }
        val b = lp.optString("player_b", loserPayPlayerB.value).ifBlank { "Player 2" }
        val rounds = lp.optJSONArray("rounds") ?: JSONArray()
        val scoreA = mutableListOf<Char>(); val scoreB = mutableListOf<Char>()
        var openRound: JSONObject? = null
        for (i in 0 until rounds.length()) {
            val r = rounds.optJSONObject(i) ?: continue
            val ended = r.optString("ended_at", "").let { it.isNotBlank() && it != "null" }
            if (!ended) { openRound = r; continue }
            val winner = r.optString("winner", "")
            val loser = r.optString("loser", "")
            when {
                // an unfinished game that cost nothing (end of a session) is not a played draw
                r.optBoolean("is_draw", false) -> if (r.optDouble("amount_kes", 0.0) > 0) { scoreA.add('D'); scoreB.add('D') }
                winner == a || loser == b -> { scoreA.add('W'); scoreB.add('L') }
                winner == b || loser == a -> { scoreA.add('L'); scoreB.add('W') }
            }
        }
        val totalA = lp.optDouble("total_amount_player_a", loserPayAmountA.value)
        val totalB = lp.optDouble("total_amount_player_b", loserPayAmountB.value)
        val roundNo = openRound?.optInt("round_number", scoreA.size + 1) ?: (scoreA.size + 1)
        val sig = "$a|$b|${scoreA.joinToString("")}|$roundNo|$totalA|$totalB"
        isLoserPaySession = true
        lp.optString("token", "").takeIf { it.isNotBlank() && it != "null" }?.let { loserPayToken = it }
        if (sig == loserPaySyncSig) return
        loserPaySyncSig = sig
        loserPayPlayerA.value = a
        loserPayPlayerB.value = b
        if (loserPayScoreA.toList() != scoreA) { loserPayScoreA.clear(); loserPayScoreA.addAll(scoreA) }
        if (loserPayScoreB.toList() != scoreB) { loserPayScoreB.clear(); loserPayScoreB.addAll(scoreB) }
        loserPayAmountA.value = totalA
        loserPayAmountB.value = totalB
        val newRound = roundNo != childSessionRound.value
        childSessionRound.value = roundNo
        if (resetClock) {
            loserPayRoundStartMs.value = System.currentTimeMillis()
        } else if (newRound || loserPayRoundStartMs.value == 0L) {
            // the server's start time; the event path (round_result) sets the TV's own clock instead
            val started = openRound?.let { parseUtcMs(it.optString("started_at", "")) } ?: 0L
            loserPayRoundStartMs.value = if (started in 1..System.currentTimeMillis()) started else System.currentTimeMillis()
        }
        childSessionStartTime = loserPayRoundStartMs.value
    }

    /** Every sync carries the screen's latest loser-pay match: follow it while it is live. */
    private fun onLoserPaySync(data: JSONObject) {
        val lp = data.optJSONObject("loser_pay_session") ?: return
        if (lp.optString("status", "") != "ACTIVE" || !isLiveSession(data)) return
        try { applyLoserPayState(lp) } catch (e: Exception) { Log.w(TAG, "[LoserPay] sync: ${e.message}") }
    }

    /**
     * Result of a game: player 1 always on the LEFT, player 2 on the RIGHT. The winner's side
     * lights up, each side shows its won / drawn / lost and what it owes, then it all fades.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun showRoundResultPopup(data: JSONObject, round: Int) {
        if (!Settings.canDrawOverlays(this)) return
        val a = loserPayPlayerA.value
        val b = loserPayPlayerB.value
        val isDraw = data.optBoolean("is_draw", false)
        val amount = data.optDouble("amount_kes", 0.0)
        val winner = data.optString("winner", "")
        val loser = data.optString("loser", "")
        val winnerSide = data.optString("winner_side", "").ifBlank {
            when { winner == a || loser == b -> "A"; winner == b || loser == a -> "B"; else -> "" }
        }
        val local = localStats()
        val st = data.optJSONObject("stats")
        fun side(key: String, fallback: IntArray): IntArray = st?.optJSONObject(key)?.let {
            intArrayOf(it.optInt("won"), it.optInt("drawn"), it.optInt("lost"))
        } ?: fallback
        val statsA = side("A", local.first)
        val statsB = side("B", local.second)
        val payA = if (isDraw) data.optDouble("amount_player_a", amount / 2) else if (winnerSide == "B") amount else 0.0
        val payB = if (isDraw) data.optDouble("amount_player_b", amount / 2) else if (winnerSide == "A") amount else 0.0
        val totalA = loserPayAmountA.value
        val totalB = loserPayAmountB.value

        val popupParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 40
        }

        val popupView = ComposeView(this).apply {
            setContent {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    shown = true
                    delay(RESULT_POPUP_MS)
                    shown = false
                }
                AnimatedVisibility(visible = shown, enter = fadeIn(tween(500)), exit = fadeOut(tween(1200))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ResultSide(a, "PLAYER 1", isDraw, winnerSide == "A", statsA, payA, totalA, Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("GAME $round", color = Color(0xFFFBBF24), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(if (isDraw) "DRAW" else "VS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            if (isDraw) Text("split", color = Color(0xFFFDE68A), fontSize = 11.sp)
                        }
                        ResultSide(b, "PLAYER 2", isDraw, winnerSide == "B", statsB, payB, totalB, Modifier.weight(1f))
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
            // gone once the fade-out is over
            Handler(Looper.getMainLooper()).postDelayed({
                try { windowManager?.removeView(popupView) } catch (_: Exception) {}
            }, RESULT_POPUP_MS + 1_600L)
        } catch (e: Exception) {
            Log.e(TAG, "Could not show round result popup: ${e.message}")
        }
    }

    @Composable
    private fun ResultSide(
        name: String, label: String, isDraw: Boolean, won: Boolean, stats: IntArray,
        pays: Double, total: Double, modifier: Modifier,
    ) {
        val colors = when {
            isDraw -> listOf(Color(0xFF78350F), Color(0xFFB45309))
            won -> listOf(Color(0xFF14532D), Color(0xFF16A34A))
            else -> listOf(Color(0xFF3F1D1D), Color(0xFF7F1D1D))
        }
        val headline = when {
            isDraw -> "DRAW"
            won -> "WINNER"
            else -> "LOST"
        }
        Column(
            modifier
                .background(Brush.horizontalGradient(colors), RoundedCornerShape(14.dp))
                .border(if (won) 2.dp else 1.dp, if (won) Color(0xFFFBBF24) else Color.White.copy(0.2f), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = Color.White.copy(0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(headline, color = if (won) Color(0xFFFDE68A) else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatCell("WON", stats[0], Color(0xFF86EFAC))
                StatCell("DRAWN", stats[1], Color(0xFFFDE68A))
                StatCell("LOST", stats[2], Color(0xFFFCA5A5))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    pays > 0 -> "Pays KES ${pays.toInt()} for this game"
                    else -> "Pays nothing for this game"
                },
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Text("Total so far: KES ${total.toInt()}", color = Color.White.copy(0.8f), fontSize = 11.sp)
        }
    }

    @Composable
    private fun StatCell(label: String, value: Int, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", color = color, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(label, color = Color.White.copy(0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
        val serverTopupSeq = data.optInt("topup_seq", lastTopupSeq)
        if (wasLive && isLive && serverTopupSeq > lastTopupSeq) {
            // A top-up was paid: jump to the new time left at once (drifting toward it
            // one second at a time would take ages). Overtime already played stays billed.
            localSecondsRemaining = data.optInt("seconds_remaining")
            remainingDrift = 0
            lastTopupSeq = serverTopupSeq
            Log.d(TAG, "Top-up applied: ${localSecondsRemaining}s left")
        } else if (wasLive && isLive) {
            // Queued overtime only ever grows within a session (the TV counts it).
            queuedOvertimeSecs = maxOf(queuedOvertimeSecs, data.optInt("queued_overtime_seconds", 0))
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
            lastTopupSeq = serverTopupSeq
            localSecondsRemaining = data.optInt("seconds_remaining")
            localSecondsElapsed = data.optInt("seconds_elapsed")
            localSecondsElapsedOvertime = data.optInt("seconds_elapsed_overtime")
            queuedOvertimeSecs = data.optInt("queued_overtime_seconds", 0)
            overtimeNoticeShown = queuedOvertimeSecs > 0
            turnWarningShown = overtimeNoticeShown
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
                "&console_state=${consoleStateName()}" +
                "&backup_wifi_state=${BackupWifi.state(this)}" +
                "&uptime_seconds=$uptimeSeconds" +
                "&topup_seq=$lastTopupSeq" +
                "&queued_overtime_seconds=$queuedOvertimeSecs"
        
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
                            onQueueInfo(data)
                            onLoserPaySync(data)
                            onTopupRequest(data)
                            BackupWifi.onSync(this@KioskService, data)
                            // Owner paused / is removing PSBill (RemoteControl): leave the screen alone
                            if (RemoteControl.onSync(this@KioskService, data, deviceId, apiBaseUrl(serverDomain),
                                    token, fingerprint)) {
                                removeTimerOverlay()
                                stopLockScreenWatchdog()
                                return@withContext
                            }
                            if (!isLiveSession(data)) {
                                // Server confirms session is not live — clear the pending flag
                                localSessionEndedPendingServerConfirm = false
                                removeTimerOverlay()
                                if (oldStatus != "LOCKED") {
                                    // last_invoice is the last SAVED session log, usually the
                                    // previous session (this one is saved when the bill is cleared).
                                    // Use the bill of the session that just ended instead.
                                    lockDevice(endedInvoiceFrom(data) ?: data.optJSONObject("last_invoice"))
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
    /** The bill of the session that just ended, from the device state the server sent. */
    private fun endedInvoiceFrom(data: JSONObject): JSONObject? {
        if (!data.optBoolean("has_ended_session", false)) return null
        val amount = data.optDouble("active_billing_kes", 0.0)
        if (amount.isNaN() || amount <= 0) return null
        val secs = data.optInt("seconds_elapsed", 0) + data.optInt("seconds_elapsed_overtime", 0)
        return JSONObject().apply {
            put("amount_charged_kes", amount)
            put("duration_minutes", maxOf(1, Math.round(secs / 60.0).toInt()))
            put("game_name", data.cleanStr("current_game", ""))
            put("billing_mode", data.optString("billing_mode", ""))
        }
    }

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
        loserPayRoundStartMs.value = 0L
        loserPaySyncSig = ""
    }

    private fun launchLockScreen(invoiceJson: JSONObject?) {
        if (RemoteControl.isPaused(this)) return   // paused by the owner: no lock screen
        val intent = Intent(this, LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (invoiceJson != null) {
                putExtra("show_invoice", true)
                putExtra("amount_charged", invoiceJson.optDouble("amount_charged_kes", 0.0))
                putExtra("duration_minutes", invoiceJson.optInt("duration_minutes", 0))
                putExtra("game_name", invoiceJson.cleanStr("game_name", ""))
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
            if (RemoteControl.isPaused(this@KioskService)) {
                lockScreenWatchdogHandler = null
                return
            }
            if (!LockActivity.isLockActivityVisible && !AppUpdater.installInProgress && !BackupWifi.askOpen) {
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
        if (RemoteControl.isRemoving(this)) {
            // unlinked because the owner removed PSBill: no linking screen, just stop
            removeTimerOverlay()
            stopLockScreenWatchdog()
            stopSelf()
            return
        }
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

    /**
     * The id of HDMI input #number (1-based), else the first HDMI input; null without TV inputs.
     * Only the TV's own HDMI ports count: a console with HDMI-CEC adds a child input (parentId =
     * the port) that exists only while the console is plugged in and on. Counting those moved
     * the numbering, and a saved child id was gone after the cable came out, so the console was
     * never seen coming back (the HDMI input was not picked up again).
     */
    private fun resolveHdmiInputId(number: Int): String? {
        return try {
            val tim = getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager ?: return null
            val all = tim.tvInputList.filter {
                it.type == android.media.tv.TvInputInfo.TYPE_HDMI || it.id.contains("hdmi", ignoreCase = true)
            }
            val ports = all.filter { it.parentId == null }
            val hdmi = ports.ifEmpty { all }
            val pick = hdmi.getOrNull(number - 1) ?: hdmi.firstOrNull()
            (pick?.parentId ?: pick?.id)
        } catch (e: Exception) {
            Log.w(TAG, "TV inputs unavailable: ${e.message}")
            null
        }
    }

    private fun launchHdmiInput(number: Int = 1) {
        hdmiNumber = number
        val id = resolveHdmiInputId(number)
        if (id != null) {
            hdmiInputId = id
            getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE).edit()
                .putInt("hdmi_input_number", number)
                .putString(ConsoleOffActivity.KEY_INPUT_ID, id)
                .apply()
            try {
                val uri = android.media.tv.TvContract.buildChannelUriForPassthroughInput(id)
                startActivity(Intent(Intent.ACTION_VIEW, uri).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                Log.d(TAG, "Launched HDMI input #$number: $id")
                checkConsole()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch TvInput HDMI: ${e.message}")
            }
        }
        // Fallback: the generic passthrough intent
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://android.media.tv/passthrough"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "Launched fallback TV intent content://android.media.tv/passthrough")
        } catch (e: Exception) {
            if (hdmiState < 0) updateLocalHdmiConnected(false)
            Log.e(TAG, "All HDMI launch fallbacks failed: ${e.message}")
        }
    }

    // ── Console watch ─────────────────────────────────────────────────────────
    // hdmi_connected used to mean "we managed to open the HDMI input". It now comes from the
    // input's real state: signal (console on), standby (cable in, console off/asleep) or
    // unplugged. While a session runs with the console off, ConsoleOffActivity covers the
    // input so the TV's no-signal countdown never puts it to sleep.

    private fun consoleStateName(): String = when (hdmiState) {
        android.media.tv.TvInputManager.INPUT_STATE_CONNECTED -> "on"
        android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "standby"
        android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED -> "unplugged"
        else -> "unknown"
    }

    private fun startConsoleWatch() {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager ?: return
        if (hdmiCallback == null) {
            val cb = object : android.media.tv.TvInputManager.TvInputCallback() {
                override fun onInputStateChanged(inputId: String, state: Int) {
                    val port = hdmiInputId ?: return
                    if (inputId == port) onConsoleState(state)
                    else if (parentOf(inputId) == port) checkConsole()  // the console's CEC input
                }
                // A console with CEC appears / disappears as its own input when plugged / unplugged
                override fun onInputAdded(inputId: String) { checkConsole() }
                override fun onInputRemoved(inputId: String) { checkConsole() }
                override fun onInputUpdated(inputId: String) { checkConsole() }
            }
            try {
                tim.registerCallback(cb, hdmiWatchHandler)
                hdmiCallback = cb
            } catch (e: Exception) {
                Log.w(TAG, "TV input callback unavailable: ${e.message}")
            }
        }
        hdmiWatchHandler.removeCallbacks(hdmiWatchRunnable)
        hdmiWatchHandler.postDelayed(hdmiWatchRunnable, HDMI_POLL_MS)
    }

    private fun stopConsoleWatch() {
        hdmiWatchHandler.removeCallbacks(hdmiWatchRunnable)
        val cb = hdmiCallback ?: return
        try {
            (getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager)?.unregisterCallback(cb)
        } catch (_: Exception) {}
        hdmiCallback = null
    }

    private fun parentOf(inputId: String): String? = try {
        (getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager)
            ?.getTvInputInfo(inputId)?.parentId
    } catch (_: Exception) { null }

    private fun checkConsole() {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager ?: return
        var id = hdmiInputId
        // A saved id that is gone (an old CEC child input) or is a child: find the port again
        val info = id?.let { try { tim.getTvInputInfo(it) } catch (_: Exception) { null } }
        if (id == null || info == null || info.parentId != null) {
            id = resolveHdmiInputId(hdmiNumber) ?: return
            if (id != hdmiInputId) {
                Log.d(TAG, "HDMI input is now $id (was $hdmiInputId)")
                hdmiInputId = id
                getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE).edit()
                    .putString(ConsoleOffActivity.KEY_INPUT_ID, id).apply()
            }
        }
        val state = try { tim.getInputState(id) } catch (e: Exception) { return }
        // Many TVs keep the PORT "connected" whatever the cable does; only the console's CEC
        // input (a child of the port) comes and goes. Follow that too, or a returned cable
        // is never noticed and the TV stays on its home / no-signal screen.
        val hasChild = try { tim.tvInputList.any { it.parentId == id } } catch (_: Exception) { false }
        val hadChild = hdmiHadChild
        hdmiHadChild = hasChild
        if (hadChild != null && hadChild != hasChild) {
            Log.d(TAG, "Console CEC input ${if (hasChild) "back" else "gone"} on $id")
            if (!hasChild) {
                if (sessionOnScreen()) consoleLostInSession = true
            } else if (consoleLostInSession && state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED &&
                hdmiState == state && sessionOnScreen() && !isSleeping && !RemoteControl.isPaused(this)) {
                // the port never changed state, so onConsoleState would not see the return
                consoleLostInSession = false
                if (ConsoleOffActivity.isOpen()) ConsoleOffActivity.close()
                Log.d(TAG, "Console back (CEC): opening HDMI input #$hdmiNumber")
                launchHdmiInput(hdmiNumber)
                return
            }
        }
        onConsoleState(state)
    }

    private fun sessionOnScreen(): Boolean =
        deviceStatus != "LOCKED" && deviceStatus != "PENDING" &&
            !localSessionEndedPendingServerConfirm && !LockActivity.isLockActivityVisible

    private fun onConsoleState(state: Int) {
        val previous = hdmiState
        if (state != hdmiState) {
            Log.d(TAG, "Console state ${consoleStateName()} -> $state")
            hdmiState = state
            // a real change (cable, console power): stop trusting an earlier "console is on" OK
            getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE).edit()
                .remove(ConsoleOffActivity.KEY_TRUST_ON).apply()
            val on = state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED
            if (on != localHdmiConnected) updateLocalHdmiConnected(on)
        }
        val inSession = sessionOnScreen()
        val canSwitch = inSession && !isSleeping && !RemoteControl.isPaused(this)
        if (state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED) {
            consoleOffSince = 0L
            val coverOpen = ConsoleOffActivity.isOpen()
            val wasInFront = coverOpen && ConsoleOffActivity.isResumedNow
            if (coverOpen) ConsoleOffActivity.close()
            // The console is back after going dark in this session: open the HDMI input again.
            // Before, this only happened when our cover page was in front, so after the cable
            // was pulled (the TV leaves the input by itself) or a quick console restart (no
            // cover yet) the TV stayed on its home / no-signal screen.
            val back = consoleLostInSession && previous != state
            consoleLostInSession = false
            if (canSwitch && (wasInFront || back)) {
                Log.d(TAG, "Console back: opening HDMI input #$hdmiNumber")
                launchHdmiInput(hdmiNumber)
            }
            return
        }
        if (inSession && previous >= 0) consoleLostInSession = true
        // Cable plugged back in (console still waking): many TVs only see the picture once the
        // input is open, so open it now instead of waiting for OK (the cover comes back if it
        // stays dark).
        if (previous == android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED && canSwitch) {
            Log.d(TAG, "HDMI cable back: opening the input")
            getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE).edit()
                .putLong(ConsoleOffActivity.KEY_SNOOZE_UNTIL, System.currentTimeMillis() + ConsoleOffActivity.SNOOZE_MS)
                .apply()
            consoleOffSince = 0L
            if (ConsoleOffActivity.isOpen()) ConsoleOffActivity.close()
            launchHdmiInput(hdmiNumber)
            return
        }
        if (!inSession) {
            consoleLostInSession = false
            consoleOffSince = 0L
            // the session ended: the lock screen is in front, drop the page behind it
            if (ConsoleOffActivity.isOpen() && !ConsoleOffActivity.isResumedNow) ConsoleOffActivity.close()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (consoleOffSince == 0L) consoleOffSince = now
        if (now - consoleOffSince < CONSOLE_OFF_GRACE_MS || ConsoleOffActivity.isResumedNow) return
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() < prefs.getLong(ConsoleOffActivity.KEY_SNOOZE_UNTIL, 0L)) return
        // OK was pressed and nothing changed since: the TV says standby but the player sees the game
        if (prefs.getBoolean(ConsoleOffActivity.KEY_TRUST_ON, false)) return
        showConsoleCover()
    }

    private fun showConsoleCover() {
        if (RemoteControl.isPaused(this)) return
        try {
            Log.d(TAG, "Console off during a session: covering the HDMI input")
            startActivity(Intent(this, ConsoleOffActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        } catch (e: Exception) {
            Log.e(TAG, "Unable to show the console page: ${e.message}")
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
            // Bottom-right, out of the way of most game HUDs (score, minimap, menus).
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 24
        }

        overlayView = ComposeView(this).apply {
            setContent { MeterOverlay() }
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
        updateMeterVisibility(
            status,
            mode,
            deviceData.optInt("seconds_remaining"),
            deviceData.optInt("seconds_elapsed"),
        )

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

    /**
     * When the meter shows and in which colour.
     * Shown: the first 20 s, a 10 s peek every 5 minutes, and all the time near the end.
     * Pay-first: amber from 5 min left, red from 2 min left and in overtime.
     * Pay-after: there is no end unless someone is waiting; then the expected turn
     * (the arcade's average session) is the end: amber 5 min before, red after it.
     */
    private fun updateMeterVisibility(status: String, mode: String, secsRemaining: Int, secsElapsed: Int) {
        val level = when {
            isAdminMode.value -> 0
            status == "OVERTIME" -> 2
            mode == "PREPAID" -> when {
                secsRemaining <= 120 -> 2
                secsRemaining <= 300 -> 1
                else -> 0
            }
            overchargeNow() -> when {
                secsElapsed >= turnSeconds -> 2
                secsElapsed >= turnSeconds - 300 -> 1
                else -> 0
            }
            else -> 0
        }
        meterLevel.value = level
        if (mode != "PREPAID" && !isAdminMode.value && overchargeNow() && level > 0) tellPlayerAboutOvertime(level)
        if (mode == "PREPAID" && !isAdminMode.value && level >= 2 && !prepaidWarningShown) {
            prepaidWarningShown = true
            val pricier = overchargeNow() && overtimeRate > ratePerMinute
            showNotice(
                if (pricier) "2 minutes left - the screen locks at 0\nPress BACK to add time: KES ${formatRate(overtimeRate)}/min (${whyOvercharge()})"
                else "2 minutes left - the screen locks at 0\nPress BACK to add time",
                20_000
            )
        }
        if (mode == "PREPAID" && level == 0) prepaidWarningShown = false  // topped up
        meterVisible.value = level > 0 || secsElapsed < 20 || (secsElapsed % 300) < 10 ||
            android.os.SystemClock.elapsedRealtime() < meterPeekUntil ||
            isLoserPaySession   // loser pay: the meter stays on so both players see the game's cost
    }

    /** Queue info from the 2-second sync: pop up a short notice when someone joins. */
    private fun onQueueInfo(data: JSONObject) {
        turnSeconds = data.optInt("turn_seconds", turnSeconds).coerceAtLeast(300)
        // The sync carries the EFFECTIVE overtime price (the screen's, else the business's).
        val ot = data.optDouble("overtime_rate_per_minute_kes", overtimeRate)
        overtimeRate = if (ot.isNaN() || ot <= 0) 0.0 else ot
        rushHour = data.optBoolean("rush_hour", rushHour)
        val queue = data.optJSONObject("queue")
        val waiting = queue?.optInt("waiting_count", 0) ?: 0
        val previous = queueWaiting
        queueWaiting = waiting
        if (waiting <= 0) {
            queueNotice.value = null
            return
        }
        val now = System.currentTimeMillis()
        // A new player joined, or a reminder every 10 minutes while people wait.
        val joined = waiting > previous
        val remind = now - lastQueueNoticeAt > 10 * 60 * 1000L
        if (!joined && !remind) return
        if (!isLiveSession(data)) return
        lastQueueNoticeAt = now
        val next = queue?.optJSONObject("next_ticket")
        val nextName = next?.optString("player_name", "")?.takeIf { it.isNotBlank() && it != "null" }
        val nextNo = next?.optInt("ticket_number", 0) ?: 0
        val who = when {
            nextName != null && nextNo > 0 -> "Next: $nextName (#$nextNo)"
            nextName != null -> "Next: $nextName"
            nextNo > 0 -> "Next: ticket #$nextNo"
            else -> null
        }
        val head = if (waiting == 1) "1 player is waiting for this screen" else "$waiting players are waiting for this screen"
        showNotice(if (who != null) "$head\n$who" else head, 12_000)
    }

    /** A short message above the meter (first line bold), gone after [millis]. */
    private fun showNotice(text: String, millis: Long) {
        queueNotice.value = text
        queueNoticeJob?.cancel()
        queueNoticeJob = serviceScope.launch {
            delay(millis)
            queueNotice.value = null
        }
    }

    /** Show the meter for a few seconds (Continue in the BACK dialog). Amber/red stay on anyway. */
    private fun peekMeter(millis: Long = 8_000L) {
        meterPeekUntil = android.os.SystemClock.elapsedRealtime() + millis
        meterVisible.value = true
    }

    /** Price per minute of a top-up right now: the overtime price while someone waits / rush hour. */
    private fun topupRate(): Double =
        if (overchargeNow() && overtimeRate > ratePerMinute) overtimeRate else ratePerMinute

    /** The player asks for more time; staff approve it in the app after taking the money. */
    private fun sendTopupRequest(minutes: Int, done: (Boolean, String) -> Unit) {
        val id = getKioskDeviceId().ifEmpty { getMacAddress() }
        val token = getEncryptedPrefs(this).getString("auth_token", "") ?: ""
        val body = JSONObject().put("minutes", minutes).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$id/topup-request")
            .header("Authorization", "Bearer $token")
            .header("X-Device-Fingerprint", getDeviceFingerprint())
            .post(body)
            .build()
        serviceScope.launch(Dispatchers.IO) {
            val (ok, msg) = try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val r = JSONObject(text).optJSONObject("topup_request")
                        lastTopupRequestStatus = "PENDING"
                        lastTopupRequestAt = r?.optString("at")
                        true to "Sent. Pay KES ${formatRate(r?.optDouble("amount_kes", 0.0) ?: 0.0)} to the staff - your time is added when they approve."
                    } else {
                        false to (try { JSONObject(text).optString("error") } catch (_: Exception) { "" })
                            .ifBlank { "Could not send - ask the staff" }
                    }
                }
            } catch (e: Exception) {
                false to "No connection - ask the staff"
            }
            withContext(Dispatchers.Main) { done(ok, msg) }
        }
    }

    /** Staff answered the player's top-up request: tell the player. */
    private fun onTopupRequest(data: JSONObject) {
        val r = data.optJSONObject("topup_request") ?: return
        val status = r.optString("status")
        val at = r.optString("at")
        if (at == lastTopupRequestAt && status == lastTopupRequestStatus) return
        val sameRequest = at == lastTopupRequestAt
        lastTopupRequestAt = at
        lastTopupRequestStatus = status
        if (!sameRequest) return  // first sight after a restart: nothing to announce
        when (status) {
            "APPROVED" -> {
                val mins = r.optInt("added_minutes", r.optInt("minutes", 0))
                showNotice("Top-up approved: +$mins min\nEnjoy the game!", 8_000)
                peekMeter()
            }
            "DECLINED" -> showNotice("Top-up not approved\nTalk to the staff", 10_000)
        }
    }

    /** Pay-first: the last 10 seconds count down big on screen, then "TIME UP". */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateFinale(secsLeft: Int) {
        if (isAdminMode.value || billingMode != "PREPAID") { hideFinale(); return }
        when {
            secsLeft in 1..10 -> { finaleText.value = secsLeft.toString(); showFinale() }
            secsLeft <= 0 -> {
                finaleText.value = "TIME UP"
                showFinale()
                finaleHideJob?.cancel()
                finaleHideJob = serviceScope.launch { delay(3_500); withContext(Dispatchers.Main) { hideFinale() } }
            }
            else -> hideFinale()  // topped up
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun showFinale() {
        if (finaleView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // see-through to the game and never takes the remote/controller
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        val owner = CustomLifecycleOwner().also { it.start() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())
            setContent { FinaleOverlay() }
        }
        try {
            windowManager?.addView(view, params)
            finaleView = view
        } catch (e: Exception) {
            Log.e(TAG, "Finale overlay failed: ${e.message}")
        }
    }

    private fun hideFinale() {
        finaleText.value = null
        val v = finaleView ?: return
        try { windowManager?.removeView(v) } catch (_: Exception) {}
        finaleView = null
    }

    @Composable
    private fun FinaleOverlay() {
        val text = finaleText.value ?: return
        val over = text == "TIME UP"
        // every new number pops in: big -> normal, bright -> settled
        val pop = remember(text) { Animatable(1.6f) }
        LaunchedEffect(text) { pop.animateTo(1f, tween(450)) }
        val color = if (over) Color(0xFFEF4444) else Color(0xFFFBBF24)
        Box(
            Modifier.fillMaxSize().background(if (over) Color(0xB3000000) else Color(0x33000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text,
                    color = color,
                    fontSize = if (over) 96.sp else 140.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = (2.2f - pop.value).coerceIn(0f, 1f) },
                )
                Text(
                    if (over) "Thanks for playing! Ask the staff to add time." else "Time almost up - press BACK to add time",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    private fun effectiveOvertimeRate() = if (overtimeRate > 0) overtimeRate else ratePerMinute

    /** Overtime price applies while someone waits for this screen, or in rush hours. */
    private fun overchargeNow() = queueWaiting > 0 || rushHour

    private fun whyOvercharge() = if (queueWaiting > 0) "someone is waiting" else "rush hour"

    /** Pay-after bill: normal rate, queued overtime at the overtime rate, at least the minimum. */
    private fun postpaidBill(): Double {
        val q = queuedOvertimeSecs.coerceIn(0, maxOf(0, localSecondsElapsed))
        val normal = localSecondsElapsed - q
        return maxOf(minCharge, normal / 60.0 * ratePerMinute + q / 60.0 * effectiveOvertimeRate())
    }

    /**
     * Pay-after with players waiting: warn 5 minutes before the turn ends, and say so
     * when the overtime price starts. Each once per session.
     */
    private fun tellPlayerAboutOvertime(level: Int) {
        val pricier = overtimeRate > 0 && overtimeRate > ratePerMinute
        val price = "KES ${formatRate(effectiveOvertimeRate())}/min"
        if (level == 1 && !turnWarningShown) {
            turnWarningShown = true
            showNotice(
                if (pricier) "Your turn ends in 5 minutes - ${whyOvercharge()}\nAfter that, overtime is $price"
                else "Your turn ends in 5 minutes\n${whyOvercharge().replaceFirstChar { it.uppercase() }}",
                15_000
            )
        } else if (level >= 2 && !overtimeNoticeShown) {
            overtimeNoticeShown = true
            turnWarningShown = true
            showNotice(
                if (pricier) "Overtime price now applies: $price\n${whyOvercharge().replaceFirstChar { it.uppercase() }}"
                else "Your turn is over\n${whyOvercharge().replaceFirstChar { it.uppercase() }}",
                20_000
            )
        }
    }

    private fun formatRate(r: Double) = if (r % 1.0 == 0.0) r.toInt().toString() else String.format("%.1f", r)

    @Composable
    private fun MeterOverlay() {
        val text = timerText.value
        val isOver = isOvertime.value
        val isAdm = isAdminMode.value
        val amt = activeBillingAmount.value
        val mode = activeBillingMode.value
        val level = meterLevel.value
        val visible = meterVisible.value || isOver
        val notice = queueNotice.value

        // Solid dark background + a coloured edge: the numbers stay readable over any game.
        val accent by animateColorAsState(
            targetValue = when {
                isAdm -> Color(0xFF818CF8)
                level >= 2 -> Color(0xFFEF4444)
                level == 1 -> Color(0xFFF59E0B)
                else -> Color(0xFF22C55E)
            },
            animationSpec = tween(600),
            label = "meterAccent",
        )

        // Time almost up (red): the meter blinks so the player notices it.
        val blink = rememberInfiniteTransition(label = "meterBlink")
        val blinkAlpha by blink.animateFloat(
            initialValue = 1f, targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
            label = "meterBlinkAlpha",
        )
        val meterAlpha = if (level >= 2 && !isAdm) blinkAlpha else 1f

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedVisibility(visible = notice != null, enter = fadeIn(tween(400)), exit = fadeOut(tween(800))) {
                Column(
                    modifier = Modifier
                        .background(Color(0xE6111827), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    (notice ?: "").split("\n").forEachIndexed { i, line ->
                        Text(
                            text = line,
                            color = if (i == 0) Color.White else Color(0xFFBAE6FD),
                            fontSize = if (i == 0) 12.sp else 11.sp,
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = visible, enter = fadeIn(tween(500)), exit = fadeOut(tween(1200))) {
                Column(
                    modifier = Modifier
                        .graphicsLayer { alpha = meterAlpha }
                        .background(Color(0xD90B0F19), RoundedCornerShape(10.dp))
                        .border(1.5.dp, accent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).background(accent, RoundedCornerShape(50)))
                        Text(
                            text = when {
                                isAdm -> "FREE"
                                isOver -> "+$text"
                                else -> text
                            },
                            color = if (level >= 1 && !isAdm) accent else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        if (!isAdm) {
                            Text(
                                text = "KES ${amt.toInt()}",
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Text(
                        text = when {
                            isAdm -> "Free play"
                            isOver -> "Overtime"
                            mode == "PREPAID" && level >= 1 -> "Time almost up"
                            mode == "PREPAID" -> "Time left"
                            level >= 2 && overtimeRate > ratePerMinute -> "Overtime KES ${formatRate(overtimeRate)}/min"
                            level >= 2 -> if (queueWaiting > 0) "Turn over, others waiting" else "Turn over, rush hour"
                            level == 1 -> if (queueWaiting > 0) "Others waiting" else "Rush hour"
                            else -> "Playing"
                        },
                        color = Color(0xFFCBD5E1),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isLoserPaySession) LoserPayTally()
                }
            }
        }
    }

    /**
     * Loser pay under the meter: this game's clock and cost (loser pays it, a draw is split),
     * then player 1 (left) and player 2 (right) with won-drawn-lost and what each owes so far.
     */
    @Composable
    private fun LoserPayTally() {
        val (sa, sb) = localStats()
        val round = childSessionRound.value
        val start = loserPayRoundStartMs.value
        val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), start) {
            while (true) { value = System.currentTimeMillis(); delay(1_000) }
        }
        val secs = if (start > 0) ((now - start) / 1000).coerceAtLeast(0) else 0L
        val cost = maxOf(minCharge, secs / 60.0 * ratePerMinute)
        Column(horizontalAlignment = Alignment.End) {
            if (round > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GAME $round", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60), color = Color.White,
                        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Text("KES ${cost.toInt()}", color = Color(0xFFE2E8F0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(loserPayPlayerA.value.take(8), color = Color(0xFFCBD5E1), fontSize = 9.sp)
                Text("${sa[0]}-${sa[1]}-${sa[2]}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text("KES ${loserPayAmountA.value.toInt()}", color = Color(0xFFFCA5A5), fontSize = 9.sp)
                Text("|", color = Color(0x80FFFFFF), fontSize = 9.sp)
                Text(loserPayPlayerB.value.take(8), color = Color(0xFFCBD5E1), fontSize = 9.sp)
                Text("${sb[0]}-${sb[1]}-${sb[2]}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text("KES ${loserPayAmountB.value.toInt()}", color = Color(0xFFFCA5A5), fontSize = 9.sp)
            }
            Text("W-D-L  |  loser pays, draw split", color = Color(0xFF94A3B8), fontSize = 8.sp)
        }
    }

    private fun tallyColor(r: Char) = when (r) {
        'W' -> Color(0xFF4ADE80)
        'L' -> Color(0xFFF87171)
        else -> Color(0xFFFBBF24)
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
                } else {
                    checkConsole()
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
        stopConsoleWatch()
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

    private fun getDeviceFingerprint(): String = DeviceIdentity.fingerprint(this)

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

                        // Pay-first: ask for more time here; staff approve it after taking the money.
                        if (isPrepaid) AddTimeSection()

                        // Buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Continue playing — default focused
                            var isContinueFocused by remember { mutableStateOf(false) }
                            Button(
                                onClick = { dismissEndSessionConfirmationDialog(); peekMeter() },
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
                    peekMeter()
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

    @Composable
    private fun AddTimeSection() {
        val rate = topupRate()
        val pricier = overchargeNow() && overtimeRate > ratePerMinute
        var sending by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B2545), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add time", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (pricier) "KES ${formatRate(rate)}/min now - overtime price (${whyOvercharge()})"
                else "KES ${formatRate(rate)}/min. If someone is waiting for this screen, the overtime price applies.",
                color = Color(0xFFBAE6FD), fontSize = 11.sp, lineHeight = 15.sp,
            )
            val msg = result
            if (msg != null) {
                Text(msg, color = Color(0xFFFDE68A), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
            } else if (rate > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(15, 30, 60).forEach { mins ->
                        var focused by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                if (sending) return@Button
                                sending = true
                                sendTopupRequest(mins) { _, text -> sending = false; result = text }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (focused) Color(0xFF0284C7) else Color(0xFF075985),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focused = it.isFocused }
                                .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("+$mins min", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("KES ${formatRate(rate * mins)}", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
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
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    // our "sleep" is a dim page: the TV itself must stay on, or the
                    // dashboard can no longer wake it (no network in real standby)
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            // dim, not off: 0.01 made the "how to wake" text unreadable
            screenBrightness = 0.15f
        }

        val lifecycleOwner = CustomLifecycleOwner()
        lifecycleOwner.start()

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(CustomViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())

            setContent {
                val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
                SleepScreen(
                    arcadeName = prefs.getString("arcade_name", "") ?: "",
                    deviceName = prefs.getString("device_name", "") ?: "",
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
