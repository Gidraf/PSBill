package com.example.psbill

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import com.example.psbill.ui.theme.PSBillTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class LockActivity : ComponentActivity() {

    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            KioskService.mediaProjection = projection
            startService(Intent(this, KioskService::class.java).apply {
                action = "START_STREAMING"
            })
            if (intent.getStringExtra("action") == "request_projection") finish()
        }
    }

    private val client = OkHttpClient()
    private val gamesList = mutableStateListOf<JSONObject>()
    private var isLoadingGames by mutableStateOf(false)

    private var showInvoiceDialog by mutableStateOf(false)
    private var invoiceAmount by mutableStateOf(0.0)
    private var invoiceDuration by mutableStateOf(0)
    private var invoiceGame by mutableStateOf("")
    private var invoiceBillingMode by mutableStateOf("")

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // New session starting — treat any pending ended-session state as acknowledged
            // so it doesn't reappear when this LockActivity is re-launched after the next end.
            endedSessionAcknowledged = true
            hasEndedSession = false
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                try {
                    stopLockTask()
                } catch (e: Exception) {}
            }
            finish()
        }
    }

    private var totalRevenue by mutableStateOf(0.0)
    private val recentSessions = mutableStateListOf<JSONObject>()
    private val gamesPlayedAnalytics = mutableStateListOf<JSONObject>()
    private var isAdminLoggedIn by mutableStateOf(false)
    private var showAdminLoginDialog by mutableStateOf(false)
    private val allowedApps = mutableStateListOf<JSONObject>()

    private var deviceName by mutableStateOf("TV Screen")
    private var arcadeName by mutableStateOf("Arcade")
    private var hdmiConnected by mutableStateOf(true)

    private var hasEndedSession by mutableStateOf(false)
    // Set to true when the user dismisses the session summary so syncs don't re-show it.
    private var endedSessionAcknowledged = false
    private var endedSessionAmount by mutableStateOf(0.0)
    private var endedSessionGame by mutableStateOf("")
    private var endedSessionBillingMode by mutableStateOf("")
    private var endedSessionLoserPay by mutableStateOf(false)
    private val endedSessionRounds = mutableStateListOf<JSONObject>()
    private var endedSessionStart by mutableStateOf("")
    private var endedSessionEnd by mutableStateOf("")
    private var endedSessionDurationMins by mutableStateOf(0)

    private var deviceBillingMode by mutableStateOf("POSTPAID")
    private val queueTickets = mutableStateListOf<JSONObject>()

    private var loserPayPending by mutableStateOf(false)
    private var loserPayQrLink by mutableStateOf("")
    private var loserPayPlayerA by mutableStateOf("")
    private var loserPayPlayerB by mutableStateOf("")
    private var loserPayGameName by mutableStateOf("")

    private fun prettyLabel(raw: String): String {
        val value = raw.trim().replace('_', ' ').replace('-', ' ')
        if (value.isBlank()) return "Arcade"
        return value.split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.lowercase(Locale.US).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
    }

    private fun getArcadeDisplayName(): String {
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        if (token.isNotEmpty()) {
            try {
                val parts = token.split(".")
                if (parts.size >= 2) {
                    val payloadBytes = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
                    val keys = listOf("partner_name", "arcade_name", "business_name", "company_name", "display_name", "full_name", "name")
                    for (key in keys) {
                        val candidate = payloadJson.optString(key, "").trim()
                        if (candidate.isNotEmpty()) return candidate
                    }
                    val partnerId = payloadJson.optString("partner_id", "")
                    if (partnerId.isNotBlank()) return prettyLabel(partnerId)
                }
            } catch (_: Exception) {}
        }
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        return prettyLabel(prefs.getString("partner_id", "") ?: "")
    }

    private fun normalizeStatus(json: JSONObject): String =
        json.optString("status", "").trim().uppercase(Locale.US)

    private fun JSONObject.optCleanString(key: String, fallback: String = ""): String {
        if (this.isNull(key)) return fallback
        val value = this.optString(key, fallback)
        return if (value == "null") fallback else value
    }

    private fun deriveHasEndedSession(json: JSONObject): Boolean {
        return json.optBoolean("has_ended_session", false)
    }

    private fun isLiveSessionState(json: JSONObject): Boolean {
        val status = normalizeStatus(json)
        if (status in setOf("ACTIVE", "OVERTIME", "RUNNING", "STARTED", "RESUMED", "CONTINUED", "PLAYING")) {
            return true
        }
        return !deriveHasEndedSession(json) &&
            status !in setOf("LOCKED", "STOPPED", "PAUSED", "ENDED", "FINISHED", "PENDING")
    }

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val syncData = intent?.getStringExtra("sync_data")
            if (syncData != null) {
                parseSyncData(syncData)
                // Only dismiss the lock screen from a sync broadcast when the server confirms
                // a live session AND the local state does NOT have a pending ended session.
                // If has_ended_session is true it means the session ended but hasn't been reset yet —
                // the lock screen must remain until admin/attender resets via app or web.
                try {
                    val json = JSONObject(syncData)
                    val serverHasEndedSession = json.optBoolean("has_ended_session", false)
                    if (!serverHasEndedSession && isLiveSessionState(json)) {
                        runOnUiThread { finish() }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun formatIsoTimeToTimeOnly(isoStr: String): String {
        if (isoStr.isEmpty()) return ""
        try {
            val timePart = if (isoStr.contains("T")) {
                isoStr.substringAfter("T").substringBefore(".")
            } else {
                isoStr
            }
            return timePart.take(8)
        } catch (e: Exception) {
            return ""
        }
    }

    private fun parseSyncData(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            totalRevenue = json.optDouble("total_revenue", 0.0)
            
            val serverHasEnded = deriveHasEndedSession(json)
            // Only show the ended-session panel if the user hasn't already acknowledged it.
            // Once acknowledged it stays hidden until a brand new session ends.
            if (serverHasEnded && !endedSessionAcknowledged) {
                hasEndedSession = true
                endedSessionAmount = json.optDouble("active_billing_kes", 0.0)
                endedSessionGame = json.optCleanString("current_game", "")
                endedSessionBillingMode = json.optCleanString("billing_mode", "")
                showInvoiceDialog = true
            } else if (!serverHasEnded) {
                // A new session started — reset the acknowledged flag for the next end cycle.
                endedSessionAcknowledged = false
                hasEndedSession = false
                showInvoiceDialog = false
            }
            
            val devName = json.optCleanString("device_name", json.optCleanString("name", ""))
            if (devName.isNotEmpty()) {
                deviceName = devName
            }
            val arcName = json.optCleanString("arcade_name", "")
            if (arcName.isNotEmpty()) {
                arcadeName = prettyLabel(arcName)
            } else {
                val pId = json.optCleanString("partner_id", "")
                if (pId.isNotEmpty()) {
                    arcadeName = prettyLabel(pId)
                }
            }

            // Respect explicit HDMI/signal state from server when available.
            // If not present, keep the previous state to avoid false negatives.
            val hdmiState = when {
                json.has("hdmi_connected") -> json.optBoolean("hdmi_connected", true)
                json.has("is_hdmi_connected") -> json.optBoolean("is_hdmi_connected", true)
                else -> hdmiConnected
            }
            hdmiConnected = hdmiState
            getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("hdmi_connected", hdmiConnected)
                .apply()

            val startTimeStr = json.optCleanString("session_start_time", "")
            val updatedAtStr = json.optCleanString("updated_at", "")
            val secondsElapsed = json.optInt("seconds_elapsed", 0)
            endedSessionDurationMins = maxOf(1, secondsElapsed / 60)
            endedSessionStart = formatIsoTimeToTimeOnly(startTimeStr)
            endedSessionEnd = formatIsoTimeToTimeOnly(updatedAtStr)
            
            val lp = json.optJSONObject("loser_pay_session")
            if (lp != null) {
                endedSessionLoserPay = true
                val roundsArr = lp.optJSONArray("rounds")
                runOnUiThread {
                    endedSessionRounds.clear()
                    if (roundsArr != null) {
                        for (i in 0 until roundsArr.length()) {
                            endedSessionRounds.add(roundsArr.getJSONObject(i))
                        }
                    }
                }
            } else {
                endedSessionLoserPay = false
                runOnUiThread { endedSessionRounds.clear() }
            }

            val arr = json.optJSONArray("recent_sessions")
            runOnUiThread {
                recentSessions.clear()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        recentSessions.add(arr.getJSONObject(i))
                    }
                }
            }

            val gpArr = json.optJSONArray("games_played")
            runOnUiThread {
                gamesPlayedAnalytics.clear()
                if (gpArr != null) {
                    for (i in 0 until gpArr.length()) {
                        gamesPlayedAnalytics.add(gpArr.getJSONObject(i))
                    }
                }
            }
            deviceBillingMode = json.optCleanString("billing_mode", "POSTPAID")

            val status = json.optCleanString("status", "LOCKED").uppercase(Locale.US)
            loserPayPending = status == "PENDING"
            loserPayQrLink = json.optCleanString("qr_link", "")
            if (loserPayPending) {
                val lp = json.optJSONObject("loser_pay_session")
                if (lp != null) {
                    loserPayPlayerA = lp.optCleanString("player_a", "Player A")
                    loserPayPlayerB = lp.optCleanString("player_b", "Player B")
                    loserPayGameName = lp.optCleanString("game_name", "")
                } else {
                    loserPayPlayerA = json.optCleanString("player_a", "Player A")
                    loserPayPlayerB = json.optCleanString("player_b", "Player B")
                    loserPayGameName = json.optCleanString("current_game", "")
                }
            } else {
                loserPayQrLink = ""
                loserPayPlayerA = ""
                loserPayPlayerB = ""
                loserPayGameName = ""
            }

            val queueObj = json.optJSONObject("queue")
            val ticketsArr = queueObj?.optJSONArray("tickets")
            runOnUiThread {
                queueTickets.clear()
                if (ticketsArr != null) {
                    for (i in 0 until ticketsArr.length()) {
                        queueTickets.add(ticketsArr.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LockActivity", "Error parsing sync data: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Dismiss immediately only when there is a confirmed live session AND no pending reset is required.
        if ((KioskService.activeDeviceStatus == "ACTIVE" || KioskService.activeDeviceStatus == "OVERTIME")
            && !KioskService.localSessionEndedPendingServerConfirm) {
            finish()
            return
        }
        
        checkIntentForInvoice(intent)
        
        val filter = IntentFilter("com.example.psbill.ACTION_UNLOCK")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, unlockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        val action = intent.getStringExtra("action")
        if (action == "request_projection") {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            return
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                startLockTask()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val macAddress = getMacAddress(this)
        val ipAddress = getIpAddress(this)
        
        // Read background wallpaper and device name from SharedPreferences
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val lockBgUrl = prefs.getString("lock_bg_url", "") ?: ""
        deviceName = prefs.getString("device_name", "TV Screen") ?: "TV Screen"
        arcadeName = prefs.getString("arcade_name", "") ?: ""
        hdmiConnected = prefs.getBoolean("hdmi_connected", true)
        if (arcadeName.isEmpty()) {
            arcadeName = getArcadeDisplayName()
        }

        val syncFilter = IntentFilter("com.example.psbill.ACTION_SYNC_DATA")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, syncFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, syncReceiver, syncFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        val lastSync = prefs.getString("last_sync_data", null)
        if (lastSync != null) {
            parseSyncData(lastSync)
            try {
                val lastJson = JSONObject(lastSync)
                val serverHasEndedSession = lastJson.optBoolean("has_ended_session", false)
                // Only dismiss if the cached state is a live session AND no reset is pending.
                if (!serverHasEndedSession
                    && !KioskService.localSessionEndedPendingServerConfirm
                    && isLiveSessionState(lastJson)) {
                    finish()
                    return
                }
            } catch (_: Exception) {}
        }
        restorePendingInvoiceFromPrefs()

        fetchGamesFromServer()
        fetchAllowedApps()

        setContent {
            PSBillTheme {
                var selectedGameForStart by remember { mutableStateOf<String?>(null) }
                
                LockScreenContent(
                    macAddress = macAddress,
                    ipAddress = ipAddress,
                    lockBgUrl = lockBgUrl,
                    arcadeName = arcadeName,
                    deviceName = deviceName,
                    hdmiConnected = hdmiConnected,
                    games = gamesList,
                    isLoading = isLoadingGames,
                    showInvoiceDialog = showInvoiceDialog,
                    totalRevenue = totalRevenue,
                    recentSessions = recentSessions,
                    hasEndedSession = hasEndedSession,
                    endedSessionAmount = endedSessionAmount,
                    endedSessionGame = endedSessionGame,
                    endedSessionBillingMode = endedSessionBillingMode,
                    endedSessionLoserPay = endedSessionLoserPay,
                    endedSessionRounds = endedSessionRounds,
                    endedSessionStart = endedSessionStart,
                    endedSessionEnd = endedSessionEnd,
                    endedSessionDurationMins = endedSessionDurationMins,
                    isAdminLoggedIn = isAdminLoggedIn,
                    onAdminLogout = { isAdminLoggedIn = false },
                    onAdminLoginClick = { showAdminLoginDialog = true },
                    onRelinkClick = { triggerRelinkTVFlow() },
                    gamesPlayedAnalytics = gamesPlayedAnalytics,
                    deviceBillingMode = deviceBillingMode,
                    queueTickets = queueTickets,
                    loserPayPending = loserPayPending,
                    loserPayQrLink = loserPayQrLink,
                    loserPayPlayerA = loserPayPlayerA,
                    loserPayPlayerB = loserPayPlayerB,
                    loserPayGame = loserPayGameName,
                    onGameSelect = { gameName ->
                        selectedGameForStart = gameName
                    },
                    allowedApps = allowedApps,
                )
                
                selectedGameForStart?.let { gameName ->
                    val selectedGameObj = gamesList.find { it.optString("game_name", "") == gameName }
                    val isLoserPayAvailable = selectedGameObj?.optBoolean("loser_pay_available", false) ?: false

                    ManualStartDialog(
                        gameName = gameName,
                        loserPayAvailable = isLoserPayAvailable,
                        onDismiss = { selectedGameForStart = null },
                        onConfirm = { mode, game, mins, voucher, loserPay, playerA, playerB ->
                            selectedGameForStart = null
                            if (loserPay) {
                                startLoserPaySession(mode, game, playerA, playerB)
                            } else {
                                startSessionFromTv(mode, game, mins, voucher)
                            }
                        }
                    )
                }

                if (showInvoiceDialog) {
                    SessionSummaryDialog(
                        amount = invoiceAmount,
                        duration = invoiceDuration,
                        game = invoiceGame,
                        billingMode = invoiceBillingMode,
                        onDismiss = {}
                    )
                }

                if (showAdminLoginDialog) {
                    AdminLoginDialog(
                        onDismiss = { showAdminLoginDialog = false },
                        onLoginSuccess = {
                            showAdminLoginDialog = false
                            isAdminLoggedIn = true
                        }
                    )
                }
            }
        }
    }

    private fun fetchAllowedApps() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        if (token.isEmpty()) return

        val request = Request.Builder()
            .url("https://$serverDomain/api/v1/kiosk/allowed-apps")
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val body = it.body?.string() ?: return
                    try {
                        val arr = JSONArray(body)
                        runOnUiThread {
                            allowedApps.clear()
                            for (i in 0 until arr.length()) allowedApps.add(arr.getJSONObject(i))
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    private fun fetchGamesFromServer() {
        isLoadingGames = true
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        
        val url = "https://$serverDomain/api/v1/kiosk/games"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    isLoadingGames = false
                    populateDefaultGames()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        try {
                            val arr = JSONArray(body)
                            runOnUiThread {
                                gamesList.clear()
                                for (i in 0 until arr.length()) {
                                    gamesList.add(arr.getJSONObject(i))
                                }
                                isLoadingGames = false
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                isLoadingGames = false
                                populateDefaultGames()
                            }
                        }
                    } else {
                        runOnUiThread {
                            isLoadingGames = false
                            populateDefaultGames()
                        }
                    }
                }
            }
        })
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
            e.printStackTrace()
        }
        return ""
    }

    private fun continueSessionOnServer() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val deviceId = getKioskDeviceId()
        val identifier = if (deviceId.isNotEmpty()) deviceId else getMacAddress(this)
        
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        
        val url = "https://$serverDomain/api/v1/kiosk/devices/$identifier/continue"
        val body = JSONObject().toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $token")
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@LockActivity, "Failed to continue session: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@LockActivity, "Session continued successfully!", Toast.LENGTH_SHORT).show()
                            val serviceIntent = Intent(this@LockActivity, KioskService::class.java).apply {
                                action = "START_ONLINE_SESSION"
                                putExtra("device_data", bodyStr)
                            }
                            startService(serviceIntent)
                            finish()
                        }
                    } else {
                        val err = try { JSONObject(bodyStr).getString("error") } catch (e: Exception) { "Server error: ${response.code}" }
                        runOnUiThread {
                            Toast.makeText(this@LockActivity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }

    internal fun loginAsAdmin(email: String, password: String, onSuccess: (JSONObject) -> Unit, onFailure: (String) -> Unit) {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val url = "https://$serverDomain/auth/login"
        val payload = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread { onFailure("Connection error: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val bodyStr = resp.body?.string() ?: ""
                    runOnUiThread {
                        if (resp.isSuccessful) {
                            try {
                                val json = JSONObject(bodyStr)
                                val accountType = json.optString("account_type")
                                if (accountType == "admin" || accountType == "partner") {
                                    onSuccess(json)
                                } else {
                                    onFailure("Access denied: Not an administrator")
                                }
                            } catch (e: Exception) {
                                onFailure("Invalid response format")
                            }
                        } else {
                            val err = try { JSONObject(bodyStr).getString("error") } catch (e: Exception) { "Invalid email or password" }
                            onFailure(err)
                        }
                    }
                }
            }
        })
    }

    private fun populateDefaultGames() {
        gamesList.clear()
        val defaults = listOf("FC 24", "FIFA 23", "GTA V", "Mortal Kombat 1", "Tekken 8", "Spider-Man 2")
        for (g in defaults) {
            gamesList.add(JSONObject().apply {
                put("game_name", g)
            })
        }
    }

    private fun startLoserPaySession(mode: String, game: String, playerA: String, playerB: String) {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""

        var deviceId = ""
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val pl = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING), Charsets.UTF_8)
                deviceId = JSONObject(pl).optString("sub", "")
            }
        } catch (e: Exception) { Log.e("LockActivity", "Failed to decode JWT: ${e.message}") }

        if (deviceId.isEmpty()) {
            // Fallback: start a normal session locally without loser pay
            startSessionLocally(mode, game, 0)
            return
        }

        val url = "https://$serverDomain/api/v1/kiosk/loser-pay/start"
        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("billing_mode", mode)
            put("current_game", game)
            put("player_a", playerA.ifEmpty { "Player A" })
            put("player_b", playerB.ifEmpty { "Player B" })
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        runOnUiThread { Toast.makeText(this, "Starting Loser Pay session...", Toast.LENGTH_SHORT).show() }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@LockActivity, "Server unreachable, starting locally", Toast.LENGTH_SHORT).show()
                    startSessionLocally(mode, game, 0)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val bodyStr = resp.body?.string() ?: ""
                    runOnUiThread {
                        if (resp.isSuccessful) {
                            val serviceIntent = Intent(this@LockActivity, KioskService::class.java).apply {
                                action = "START_ONLINE_SESSION"
                                putExtra("device_data", bodyStr)
                            }
                            startService(serviceIntent)
                            val qrLink = try { JSONObject(bodyStr).optString("qr_link", "") } catch(e:Exception){""}
                            if (qrLink.isNotEmpty()) {
                                Toast.makeText(this@LockActivity, "Loser Pay started! QR link ready.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(this@LockActivity, "Failed: ${resp.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun startSessionFromTv(mode: String, game: String, minutes: Int, voucherCode: String = "") {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        
        var deviceId = ""
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payloadString = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING), Charsets.UTF_8)
                val payloadJson = JSONObject(payloadString)
                deviceId = payloadJson.optString("sub", "")
            }
        } catch (e: Exception) {
            Log.e("LockActivity", "Failed to decode JWT: ${e.message}")
        }
        
        if (deviceId.isEmpty()) {
            startSessionLocally(mode, game, minutes)
            return
        }
        
        val url = "https://$serverDomain/api/v1/kiosk/devices/$deviceId/start"
        val payload = JSONObject().apply {
            put("billing_mode", mode)
            put("current_game", game)
            put("voucher_code", voucherCode)
        }
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    startSessionLocally(mode, game, minutes)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        runOnUiThread {
                            val serviceIntent = Intent(this@LockActivity, KioskService::class.java).apply {
                                action = "START_ONLINE_SESSION"
                                putExtra("device_data", bodyStr)
                            }
                            startService(serviceIntent)
                            finish()
                        }
                    } else {
                        val code = response.code
                        runOnUiThread {
                            if (code == 401 || code == 403) {
                                handleRevocationInActivity()
                            } else {
                                startSessionLocally(mode, game, minutes)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun handleRevocationInActivity() {
        try {
            val securePrefs = getEncryptedPrefs(this)
            securePrefs.edit().remove("auth_token").commit()
            
            val sharedPrefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().remove("partner_id").commit()
            
            stopService(Intent(this, KioskService::class.java))
        } catch (e: Exception) {}
        
        val intent = Intent(this, DeviceAuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun triggerRelinkTVFlow() {
        try {
            val securePrefs = getEncryptedPrefs(this)
            securePrefs.edit().remove("auth_token").commit()
            
            val sharedPrefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().remove("partner_id").commit()
            
            stopService(Intent(this, KioskService::class.java))
        } catch (e: Exception) {}
        
        val intent = Intent(this, DeviceAuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("initial_mode", "QR")
        }
        startActivity(intent)
        finish()
    }

    private fun startSessionLocally(mode: String, game: String, minutes: Int) {
        Toast.makeText(this, "Starting offline local session...", Toast.LENGTH_LONG).show()
        val serviceIntent = Intent(this, KioskService::class.java).apply {
            action = "START_MANUAL_SESSION"
            putExtra("billing_mode", mode)
            putExtra("current_game", game)
            putExtra("base_minutes", minutes)
        }
        startService(serviceIntent)
        finish()
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


    private fun restorePendingInvoiceFromPrefs() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val hasPending = prefs.getBoolean("has_pending_ended_invoice", false)
        if (!hasPending || showInvoiceDialog || hasEndedSession) return
        val raw = prefs.getString("pending_ended_invoice", null) ?: return
        try {
            val invoice = JSONObject(raw)
            invoiceAmount = invoice.optDouble("amount_charged_kes", invoiceAmount)
            invoiceDuration = invoice.optInt("duration_minutes", invoiceDuration)
            invoiceGame = invoice.optString("game_name", invoiceGame)
            invoiceBillingMode = invoice.optString("billing_mode", invoiceBillingMode)
            showInvoiceDialog = true
            hasEndedSession = true
        } catch (_: Exception) {}
    }

    private fun checkIntentForInvoice(intent: Intent?) {
        if (intent == null) return
        val showInvoice = intent.getBooleanExtra("show_invoice", false)
        if (showInvoice) {
            invoiceAmount = intent.getDoubleExtra("amount_charged", 0.0)
            invoiceDuration = intent.getIntExtra("duration_minutes", 0)
            invoiceGame = intent.getStringExtra("game_name") ?: ""
            invoiceBillingMode = intent.getStringExtra("billing_mode") ?: ""
            showInvoiceDialog = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkIntentForInvoice(intent)
        val action = intent.getStringExtra("action")
        if (action == "request_projection") {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        isLockActivityVisible = true
        // Race-condition guard: if KioskService restored an active session and sent ACTION_UNLOCK
        // before this activity's BroadcastReceiver was registered, we would have missed the broadcast.
        // Checking activeDeviceStatus here guarantees we always exit cleanly once the service is ready.
        if ((KioskService.activeDeviceStatus == "ACTIVE" || KioskService.activeDeviceStatus == "OVERTIME")
            && !KioskService.localSessionEndedPendingServerConfirm) {
            isLockActivityVisible = false
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                try { stopLockTask() } catch (e: Exception) {}
            }
            finish()
            return
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName) && intent.getStringExtra("action") != "request_projection") {
            try {
                startLockTask()
            } catch (e: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        isLockActivityVisible = false
        // If the device is still locked and something (HOME, YouTube shortcut, network panel)
        // pushed us to the background, immediately relaunch to reclaim the foreground.
        // The 300ms delay gives the system time to finish its transition before we override it.
        if (KioskService.activeDeviceStatus == "LOCKED"
            || KioskService.localSessionEndedPendingServerConfirm) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Double-check: only relaunch if we haven't been legitimately unlocked yet
                if (KioskService.activeDeviceStatus == "LOCKED"
                    || KioskService.localSessionEndedPendingServerConfirm) {
                    val relaunch = Intent(this, LockActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    startActivity(relaunch)
                }
            }, 300)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus
            && (KioskService.activeDeviceStatus == "LOCKED"
                || KioskService.localSessionEndedPendingServerConfirm)) {
            // Window lost focus (system overlay, notification panel, etc.) — bring back immediately
            val intent = Intent(this, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        isLockActivityVisible = false
        super.onDestroy()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(syncReceiver)
        } catch (e: Exception) {}
    }

    // Back is blocked via dispatchKeyEvent — no onBackPressed override needed

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Keys that must be blocked (they leave the screen)
        val blocked = when (code) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_DVR,
            KeyEvent.KEYCODE_SETTINGS -> true
            else -> false
        }
        if (blocked) return true
        // Everything else (DPAD, OK/ENTER, volume, number keys) passes through to Compose
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // BACK is already blocked in dispatchKeyEvent above
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        return try {
            val pureBase64 = if (base64Str.startsWith("data:image/")) {
                base64Str.substringAfter(",")
            } else {
                base64Str
            }
            val decodedBytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun getMacAddress(context: Context): String {
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

    private fun getIpAddress(context: Context): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val props = cm.getLinkProperties(cm.activeNetwork) ?: return "0.0.0.0"
                return props.linkAddresses
                    .map { it.address }
                    .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress ?: "0.0.0.0"
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ip = wifiManager.connectionInfo.ipAddress
                return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    companion object {
        var isLockActivityVisible = false
    }
}

@Composable
fun GameCoverImage(
    coverImageUrl: String,
    coverImageStreamUrl: String,
    coverImageDataUrl: String,
    gameName: String,
    modifier: Modifier = Modifier
) {
    val preferredUrl = if (coverImageStreamUrl.isNotEmpty()) coverImageStreamUrl else coverImageUrl

    var decodedBitmap by remember(coverImageDataUrl) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(preferredUrl, coverImageDataUrl) {
        if (preferredUrl.isEmpty() && coverImageDataUrl.isNotEmpty()) {
            try {
                val pureBase64 = if (coverImageDataUrl.startsWith("data:image/")) {
                    coverImageDataUrl.substringAfter(",")
                } else {
                    coverImageDataUrl
                }
                val decodedBytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)
                decodedBitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } catch (e: Exception) {
                decodedBitmap = null
            }
        } else {
            decodedBitmap = null
        }
    }

    if (preferredUrl.isNotEmpty()) {
        Image(
            painter = rememberAsyncImagePainter(model = preferredUrl),
            contentDescription = gameName,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else if (decodedBitmap != null) {
        Image(
            bitmap = decodedBitmap!!.asImageBitmap(),
            contentDescription = gameName,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        val initials = gameName.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2).uppercase()
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFF1E293B),
                        androidx.compose.ui.graphics.Color(0xFF0F172A)
                    )
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = androidx.compose.ui.graphics.Color(0xFF00E676),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GameCard(
    game: JSONObject,
    onClick: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val gameName        = game.optString("game_name", "Unknown Game")
    val coverImageUrl   = game.optString("cover_image_url", "")
    val coverImageStreamUrl = game.optString("cover_image_stream_url", "")
    val coverImageDataUrl = game.optString("cover_image_data_url", "")
    val isLoserPay      = game.optBoolean("loser_pay_available", false)

    var isFocused by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200)
    )

    Box(
        modifier = Modifier
            .width(180.dp)
            .height(260.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
    ) {
        Surface(
            onClick = { if (showActions) showActions = false else onClick() },
            shape = RoundedCornerShape(12.dp),
            color = androidx.compose.ui.graphics.Color(0xFF1E293B),
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { isFocused = it.isFocused }
                .border(
                    width = if (isFocused) 3.dp else 1.dp,
                    color = if (isFocused) androidx.compose.ui.graphics.Color(0xFF00E676)
                            else androidx.compose.ui.graphics.Color(0xFF334155),
                    shape = RoundedCornerShape(12.dp)
                ),
            shadowElevation = if (isFocused) 16.dp else 4.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                GameCoverImage(
                    coverImageUrl = coverImageUrl,
                    coverImageStreamUrl = coverImageStreamUrl,
                    coverImageDataUrl = coverImageDataUrl,
                    gameName = gameName,
                    modifier = Modifier.fillMaxSize()
                )

                // Bottom name gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f)
                                )
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = gameName,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2, lineHeight = 17.sp
                        )
                        if (isLoserPay) {
                            Text(
                                text = "⚔ Loser Pay",
                                color = androidx.compose.ui.graphics.Color(0xFFFBBF24),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Top-right: Edit / Delete action tray (shows when focused + Select pressed)
                androidx.compose.animation.AnimatedVisibility(
                    visible = isFocused,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Edit
                        Box(
                            modifier = Modifier
                                .background(
                                    androidx.compose.ui.graphics.Color(0xFF1D4ED8).copy(0.85f),
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onEdit() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("✏", fontSize = 11.sp)
                        }
                        // Delete
                        Box(
                            modifier = Modifier
                                .background(
                                    androidx.compose.ui.graphics.Color(0xFFDC2626).copy(0.85f),
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onDelete() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("🗑", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueueTicketRow(ticket: JSONObject) {
    val ticketNumber = ticket.optInt("ticket_number", 0)
    val playerName = ticket.optString("player_name", "Unknown Player")
    val gameName = ticket.optString("game_name", "")
    val status = ticket.optString("status", "WAITING").uppercase(Locale.US)

    val isCalled = status == "CALLED"
    val borderCol = if (isCalled) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFF334155)
    val bgCol = if (isCalled) androidx.compose.ui.graphics.Color(0xFF38BDF8).copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color(0xFF1E293B).copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgCol, RoundedCornerShape(8.dp))
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Ticket Number Badge
        Box(
            modifier = Modifier
                .background(
                    if (isCalled) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFF475569),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = String.format(Locale.US, "#%03d", ticketNumber),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Player Name & Game Details
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = playerName,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (gameName.isNotEmpty()) {
                Text(
                    text = gameName,
                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }

        // Status Badge
        Box(
            modifier = Modifier
                .background(
                    if (isCalled) androidx.compose.ui.graphics.Color(0xFF0284C7).copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color(0xFF1E293B),
                    RoundedCornerShape(4.dp)
                )
                .border(
                    1.dp,
                    if (isCalled) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFF64748B),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = status,
                color = if (isCalled) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LockScreenContent(
    macAddress: String,
    ipAddress: String,
    lockBgUrl: String,
    arcadeName: String,
    deviceName: String,
    hdmiConnected: Boolean,
    games: List<JSONObject>,
    isLoading: Boolean,
    showInvoiceDialog: Boolean,
    totalRevenue: Double,
    recentSessions: List<JSONObject>,
    hasEndedSession: Boolean,
    endedSessionAmount: Double,
    endedSessionGame: String,
    endedSessionBillingMode: String,
    endedSessionLoserPay: Boolean,
    endedSessionRounds: List<JSONObject>,
    endedSessionStart: String,
    endedSessionEnd: String,
    endedSessionDurationMins: Int,
    isAdminLoggedIn: Boolean,
    onAdminLogout: () -> Unit,
    onAdminLoginClick: () -> Unit,
    onRelinkClick: () -> Unit,
    gamesPlayedAnalytics: List<JSONObject>,
    deviceBillingMode: String,
    queueTickets: List<JSONObject>,
    loserPayPending: Boolean,
    loserPayQrLink: String,
    loserPayPlayerA: String,
    loserPayPlayerB: String,
    loserPayGame: String,
    onGameSelect: (String) -> Unit,
    allowedApps: List<JSONObject> = emptyList(),
    onGameEdit: (JSONObject) -> Unit = {},
    onGameDelete: (JSONObject) -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (lockBgUrl.isNotEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(model = lockBgUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color(0xCC0B0F19),
                                androidx.compose.ui.graphics.Color(0xF20F172A)
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color(0xFF1E1B4B),
                                androidx.compose.ui.graphics.Color(0xFF0F172A),
                                androidx.compose.ui.graphics.Color(0xFF020617)
                            )
                        )
                    )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            // Realistic Lounge Header & HDMI Input Source Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .background(androidx.compose.ui.graphics.Color(0xFF8B5CF6), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("🎮 LOUNGE", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Text(
                            text = arcadeName.ifEmpty { "BASEYA GAMING LOUNGE" },
                            color = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    if (hdmiConnected) androidx.compose.ui.graphics.Color(0xFF0284C7).copy(alpha = 0.25f)
                                    else androidx.compose.ui.graphics.Color(0xFFB91C1C).copy(alpha = 0.22f),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (hdmiConnected) androidx.compose.ui.graphics.Color(0xFF38BDF8)
                                    else androidx.compose.ui.graphics.Color(0xFFFB7185),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (hdmiConnected) "HDMI LINK ACTIVE"
                                else "HDMI SIGNAL LOST",
                                color = if (hdmiConnected) androidx.compose.ui.graphics.Color(0xFF38BDF8)
                                else androidx.compose.ui.graphics.Color(0xFFFDA4AF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = deviceName,
                            color = androidx.compose.ui.graphics.Color(0xFFCBD5E1),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("•", color = androidx.compose.ui.graphics.Color(0xFF64748B))
                        Text(
                            text = if (hdmiConnected) "🟢 CONSOLE SIGNAL CONNECTED" else "🔴 HDMI NOT CONNECTED — CHECK INPUT/CABLE",
                            color = if (hdmiConnected) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFB7185),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                androidx.compose.ui.graphics.Color(0xFF3B82F6).copy(alpha = 0.1f),
                                RoundedCornerShape(20.dp)
                            )
                            .border(
                                width = 1.5.dp,
                                color = androidx.compose.ui.graphics.Color(0xFF3B82F6),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "TOTAL REV: KES ${totalRevenue.toInt()}",
                            color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    val (badgeText, badgeColor) = remember(showInvoiceDialog) {
                        if (showInvoiceDialog) {
                            "SESSION ENDED" to androidx.compose.ui.graphics.Color(0xFFEF4444)
                        } else {
                            "READY TO PLAY" to androidx.compose.ui.graphics.Color(0xFF00E676)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                badgeColor.copy(alpha = 0.1f),
                                RoundedCornerShape(20.dp)
                            )
                            .border(
                                width = 1.5.dp,
                                color = badgeColor,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(badgeColor, RoundedCornerShape(4.dp))
                            )
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Admin Login/Logout Button
                    var isAdminBtnFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            if (isAdminLoggedIn) {
                                onAdminLogout()
                            } else {
                                onAdminLoginClick()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAdminLoggedIn) androidx.compose.ui.graphics.Color(0xFFDC2626)
                                             else if (isAdminBtnFocused) androidx.compose.ui.graphics.Color(0xFF4F46E5)
                                             else androidx.compose.ui.graphics.Color(0xFF312E81),
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier
                            .onFocusChanged { isAdminBtnFocused = it.isFocused }
                            .border(
                                width = 1.5.dp,
                                color = if (isAdminBtnFocused) androidx.compose.ui.graphics.Color.White
                                        else if (isAdminLoggedIn) androidx.compose.ui.graphics.Color(0xFFEF4444)
                                        else androidx.compose.ui.graphics.Color(0xFF4338CA),
                                shape = RoundedCornerShape(20.dp)
                            ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isAdminLoggedIn) "ADMIN LOGOUT" else "ADMIN LOGIN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            if (isAdminLoggedIn) {
                AdminDashboardContent(
                    totalRevenue = totalRevenue,
                    recentSessions = recentSessions,
                    gamesPlayedAnalytics = gamesPlayedAnalytics,
                    onClose = onAdminLogout,
                    onRelinkClick = onRelinkClick,
                    allowedApps = allowedApps,
                )
            } else if (loserPayPending) {
                LoserPayPendingContent(
                    qrLink = loserPayQrLink,
                    playerA = loserPayPlayerA,
                    playerB = loserPayPlayerB,
                    game = loserPayGame
                )
            } else if (hasEndedSession) {
                if (!hdmiConnected) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .background(androidx.compose.ui.graphics.Color(0xFF3F0A15), RoundedCornerShape(14.dp))
                            .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFFFB7185), RoundedCornerShape(14.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "NO HDMI SIGNAL — settle charges from app/web first, then reconnect console cable/input.",
                            color = androidx.compose.ui.graphics.Color(0xFFFECDD3),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                val focusRequester = remember { FocusRequester() }
                var isButtonFocused by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .background(androidx.compose.ui.graphics.Color(0xFF0F172A), RoundedCornerShape(20.dp))
                        .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFF00E676).copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // ── Header ──────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "SESSION SUMMARY",
                                color = androidx.compose.ui.graphics.Color(0xFF00E676),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = endedSessionGame,
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            if (endedSessionStart.isNotEmpty() && endedSessionEnd.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$endedSessionStart  →  $endedSessionEnd",
                                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "TOTAL",
                                color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "KES ${endedSessionAmount.toInt()}",
                                color = androidx.compose.ui.graphics.Color(0xFF00E676),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${endedSessionDurationMins} mins • ${endedSessionBillingMode}",
                                color = androidx.compose.ui.graphics.Color(0xFF64748B),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Divider
                    Box(Modifier.fillMaxWidth().height(1.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)))

                    // ── Loser Pay section ────────────────────────────────
                    if (endedSessionLoserPay && endedSessionRounds.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "⚔  LOSER PAY BREAKDOWN",
                                color = androidx.compose.ui.graphics.Color(0xFFFBBF24),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                                    .background(androidx.compose.ui.graphics.Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Round", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    Text("Winner", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 10.sp, modifier = Modifier.weight(2f))
                                    Text("Amount", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 10.sp, modifier = Modifier.weight(1f))
                                }
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(androidx.compose.ui.graphics.Color.White.copy(0.1f)))
                                
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(endedSessionRounds) { round ->
                                        val isDraw = round.optBoolean("is_draw", false)
                                        val winner = round.optString("winner", "?")
                                        val amt = round.optDouble("amount_kes", 0.0)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Round ${round.optInt("round_number")}", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                            Text(
                                                if (isDraw) "Draw" else winner,
                                                color = if (isDraw) androidx.compose.ui.graphics.Color(0xFFFBBF24) else androidx.compose.ui.graphics.Color(0xFF00E676),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(2f)
                                            )
                                            Text("KES ${amt.toInt()}", color = androidx.compose.ui.graphics.Color(0xFFEF4444), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Regular session — show single session row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Duration", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 10.sp)
                                Text("${endedSessionDurationMins} minutes", color = androidx.compose.ui.graphics.Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                                Text("Amount", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 10.sp)
                                Text("KES ${endedSessionAmount.toInt()}", color = androidx.compose.ui.graphics.Color(0xFF00E676), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                }
            } else {
                if (deviceBillingMode == "PREPAID") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Left: Game Selection (weight 1.8f)
                        Column(
                            modifier = Modifier.weight(1.8f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "SELECT A GAME TO START",
                                color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )

                            if (isLoading) {
                                Box(
                                    modifier = Modifier
                                        .height(260.dp)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = androidx.compose.ui.graphics.Color(0xFF00E676),
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            } else if (games.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .height(260.dp)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No games available",
                                        color = androidx.compose.ui.graphics.Color.Gray,
                                        fontSize = 16.sp
                                    )
                                }
                            } else {
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp)
                                ) {
                                    items(games) { game ->
                                        GameCard(
                                            game = game,
                                            onClick = {
                                                val gName = game.optString("game_name", "")
                                                if (gName.isNotEmpty()) onGameSelect(gName)
                                            },
                                            onEdit = { onGameEdit(game) },
                                            onDelete = { onGameDelete(game) }
                                        )
                                    }
                                }
                            }
                        }

                        // Right: Waiting Queue (weight 1.2f)
                        Column(
                            modifier = Modifier
                                .weight(1.2f)
                                .height(280.dp)
                                .background(androidx.compose.ui.graphics.Color(0xFF0F172A).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                                .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFF1E293B), RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "WAITING QUEUE",
                                color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )

                            if (queueTickets.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Queue is empty",
                                        color = androidx.compose.ui.graphics.Color(0xFF64748B),
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(queueTickets) { ticket ->
                                        QueueTicketRow(ticket)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "SELECT A GAME TO START",
                            color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .height(260.dp)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = androidx.compose.ui.graphics.Color(0xFF00E676),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        } else if (games.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .height(260.dp)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No games available",
                                    color = androidx.compose.ui.graphics.Color.Gray,
                                    fontSize = 16.sp
                                )
                            }
                        } else {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                items(games) { game ->
                                    GameCard(
                                        game = game,
                                        onClick = {
                                            val gName = game.optString("game_name", "")
                                            if (gName.isNotEmpty()) onGameSelect(gName)
                                        },
                                        onEdit = { onGameEdit(game) },
                                        onDelete = { onGameDelete(game) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Column {
                            Text("MAC Address", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 10.sp)
                            Text(macAddress, color = androidx.compose.ui.graphics.Color(0xFFCBD5E1), fontSize = 12.sp)
                        }
                        Column {
                            Text("IP Address", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 10.sp)
                            Text(ipAddress, color = androidx.compose.ui.graphics.Color(0xFFCBD5E1), fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = "Use remote controller D-Pad to navigate & Select key to start",
                        color = androidx.compose.ui.graphics.Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (recentSessions.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "RECENT SESSIONS",
                            color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        recentSessions.take(3).forEach { session ->
                            Row(
                                modifier = Modifier
                                    .background(androidx.compose.ui.graphics.Color(0xFF1E293B).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .width(280.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = session.optString("game_name", "Game"),
                                        color = androidx.compose.ui.graphics.Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${session.optInt("duration_minutes", 0)} mins • ${session.optString("billing_mode", "POSTPAID")}",
                                        color = androidx.compose.ui.graphics.Color.Gray,
                                        fontSize = 9.sp
                                    )
                                }
                                Text(
                                    text = "KES ${session.optDouble("amount_charged_kes", 0.0).toInt()}",
                                    color = androidx.compose.ui.graphics.Color(0xFF00E676),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManualStartDialog(
    gameName: String,
    loserPayAvailable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, String, Boolean, String, String) -> Unit
) {
    var mode by remember { mutableStateOf("POSTPAID") }
    var voucherCode by remember { mutableStateOf("") }
    var isLoserPay by remember { mutableStateOf(false) }
    var playerA by remember { mutableStateOf("") }
    var playerB by remember { mutableStateOf("") }

    LaunchedEffect(loserPayAvailable) {
        if (!loserPayAvailable) {
            isLoserPay = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.ui.graphics.Color(0xFF111827),
        title = {
            Text(
                "Start Session",
                color = androidx.compose.ui.graphics.Color(0xFF00E676),
                fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column {
                    Text("Selected Game", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(gameName, color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                // Billing mode
                Text("Billing Mode", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("POSTPAID", "PREPAID").forEach { m ->
                        var isFocused by remember { mutableStateOf(false) }
                        val isSelected = mode == m
                        Button(
                            onClick = { mode = m },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) androidx.compose.ui.graphics.Color(0xFF00E676)
                                                else if (isFocused) androidx.compose.ui.graphics.Color(0xFF1F2937)
                                                else androidx.compose.ui.graphics.Color(0xFF161E2F),
                                contentColor = if (isSelected) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
                            ),
                            modifier = Modifier.weight(1f)
                                .onFocusChanged { isFocused = it.isFocused }
                                .border(1.dp,
                                    if (isFocused) androidx.compose.ui.graphics.Color(0xFF00E676)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    RoundedCornerShape(8.dp)),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text(m, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }

                if (mode == "PREPAID") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Prepaid voucher must be generated from Admin Dashboard",
                            color = androidx.compose.ui.graphics.Color.LightGray, fontSize = 11.sp)
                        OutlinedTextField(
                            value = voucherCode, onValueChange = { voucherCode = it },
                            label = { Text("Voucher Code", color = androidx.compose.ui.graphics.Color.Gray) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ── Loser Pay Toggle ─────────────────────────────────────────
                if (loserPayAvailable) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isLoserPay) androidx.compose.ui.graphics.Color(0xFF78350F).copy(0.3f)
                                else androidx.compose.ui.graphics.Color(0xFF1F2937),
                                RoundedCornerShape(10.dp)
                            )
                            .border(
                                1.dp,
                                if (isLoserPay) androidx.compose.ui.graphics.Color(0xFFFBBF24)
                                else androidx.compose.ui.graphics.Color(0xFF374151),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { isLoserPay = !isLoserPay }
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    "⚔  Loser Pay Mode",
                                    color = if (isLoserPay) androidx.compose.ui.graphics.Color(0xFFFBBF24)
                                            else androidx.compose.ui.graphics.Color.White,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "The loser of each round pays the bill",
                                    color = androidx.compose.ui.graphics.Color.Gray, fontSize = 10.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isLoserPay) androidx.compose.ui.graphics.Color(0xFFFBBF24)
                                        else androidx.compose.ui.graphics.Color(0xFF374151),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (isLoserPay) "ON" else "OFF",
                                    color = if (isLoserPay) androidx.compose.ui.graphics.Color.Black
                                            else androidx.compose.ui.graphics.Color.White,
                                    fontSize = 10.sp, fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    // Player name fields (only shown when Loser Pay is ON)
                    if (isLoserPay) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Players (both must join via QR link to confirm)",
                                color = androidx.compose.ui.graphics.Color(0xFFFBBF24), fontSize = 10.sp)
                            OutlinedTextField(
                                value = playerA, onValueChange = { playerA = it },
                                label = { Text("Player A name", color = androidx.compose.ui.graphics.Color.Gray) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = androidx.compose.ui.graphics.Color(0xFFFBBF24)
                                )
                            )
                            OutlinedTextField(
                                value = playerB, onValueChange = { playerB = it },
                                label = { Text("Player B name", color = androidx.compose.ui.graphics.Color.Gray) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = androidx.compose.ui.graphics.Color(0xFFFBBF24)
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            var isFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { onConfirm(mode, gameName, 0, voucherCode, isLoserPay, playerA, playerB) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFocused) androidx.compose.ui.graphics.Color(0xFF00FF88)
                                     else androidx.compose.ui.graphics.Color(0xFF00E676),
                    contentColor = androidx.compose.ui.graphics.Color.Black
                ),
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .border(2.dp,
                        if (isFocused) androidx.compose.ui.graphics.Color.White
                        else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isLoserPay) "START  ⚔" else "START SESSION", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            var isFocused by remember { mutableStateOf(false) }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .border(1.dp,
                        if (isFocused) androidx.compose.ui.graphics.Color.White
                        else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(8.dp))
            ) {
                Text("Cancel", color = if (isFocused) androidx.compose.ui.graphics.Color.White
                                       else androidx.compose.ui.graphics.Color.Gray)
            }
        }
    )
}


@Composable
fun SessionSummaryDialog(
    amount: Double,
    duration: Int,
    game: String,
    billingMode: String,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    // Request focus for TV navigation highlight only. Do not auto-dismiss.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {}
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF161E2F)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(420.dp)
                .border(2.dp, androidx.compose.ui.graphics.Color(0xFF00E676), RoundedCornerShape(16.dp))
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .background(androidx.compose.ui.graphics.Color(0xFF0B0F19), RoundedCornerShape(12.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "🎮",
                    fontSize = 48.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "SESSION SUMMARY",
                    color = androidx.compose.ui.graphics.Color.Gray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "KES ${amount.toInt()}",
                        color = androidx.compose.ui.graphics.Color(0xFF00E676),
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Total Charged",
                        color = androidx.compose.ui.graphics.Color.Gray,
                        fontSize = 12.sp
                    )
                }

                HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF1E2638), thickness = 1.dp)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Game:", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp)
                        Text(game, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Duration:", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp)
                        Text("${duration} minutes", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Mode:", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp)
                        Text(billingMode, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .border(1.dp, androidx.compose.ui.graphics.Color(0xFF334155), RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.Color(0xFF0F172A), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Awaiting payment confirmation from app/web...",
                        color = androidx.compose.ui.graphics.Color(0xFFFBBF24),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AdminLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.ui.graphics.Color(0xFF111827),
        title = {
            Text(
                "Admin Login",
                color = androidx.compose.ui.graphics.Color(0xFF818CF8),
                fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address", color = androidx.compose.ui.graphics.Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF818CF8)
                    )
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = androidx.compose.ui.graphics.Color.Gray) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF818CF8)
                    )
                )

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = androidx.compose.ui.graphics.Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isSubmitting) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = androidx.compose.ui.graphics.Color(0xFF818CF8), modifier = Modifier.size(24.dp))
                    }
                }
            }
        },
        confirmButton = {
            val context = androidx.compose.ui.platform.LocalContext.current
            val activity = context as? LockActivity
            var isFocused by remember { mutableStateOf(false) }
            Button(
                onClick = {
                    if (email.isBlank() || password.isBlank()) {
                        errorMessage = "Email and Password are required"
                        return@Button
                    }
                    isSubmitting = true
                    errorMessage = ""
                    activity?.loginAsAdmin(
                        email = email,
                        password = password,
                        onSuccess = {
                            isSubmitting = false
                            onLoginSuccess()
                        },
                        onFailure = { err ->
                            isSubmitting = false
                            errorMessage = err
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFocused) androidx.compose.ui.graphics.Color(0xFF6366F1) else androidx.compose.ui.graphics.Color(0xFF4F46E5),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .border(
                        2.dp,
                        if (isFocused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(8.dp)
                    ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("LOGIN", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            var isFocused by remember { mutableStateOf(false) }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .border(
                        1.dp,
                        if (isFocused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
            ) {
                Text("Cancel", color = if (isFocused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray)
            }
        }
    )
}

@Composable
fun AdminDashboardContent(
    totalRevenue: Double,
    recentSessions: List<JSONObject>,
    gamesPlayedAnalytics: List<JSONObject>,
    onClose: () -> Unit,
    onRelinkClick: () -> Unit,
    allowedApps: List<JSONObject> = emptyList(),
) {
    val focusRequester = remember { FocusRequester() }
    var isCloseFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .fillMaxHeight(0.75f)
            .background(androidx.compose.ui.graphics.Color(0xFF0F172A), RoundedCornerShape(20.dp))
            .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFF818CF8).copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ADMIN PORTAL",
                    color = androidx.compose.ui.graphics.Color(0xFF818CF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "GAMES PLAYED ANALYTICS",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "LIFETIME REVENUE",
                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "KES ${totalRevenue.toInt()}",
                    color = androidx.compose.ui.graphics.Color(0xFF00E676),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)))

        // Scrollable games played list
        if (gamesPlayedAnalytics.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("No games played data recorded yet.", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("GAME NAME", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                        Text("SESSIONS", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("DURATION", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("REVENUE", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(androidx.compose.ui.graphics.Color.White.copy(0.1f)))
                }

                items(gamesPlayedAnalytics) { game ->
                    val gName = game.optString("game_name", "Unknown Game")
                    val sessions = game.optInt("session_count", 0)
                    val minutes = game.optInt("total_minutes", 0)
                    val revenue = game.optDouble("total_revenue", 0.0)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0xFF1E293B).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(gName, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                        Text("$sessions", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("$minutes mins", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("KES ${revenue.toInt()}", color = androidx.compose.ui.graphics.Color(0xFF00E676), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
        // ── Allowed Apps ──────────────────────────────────────────────────
        if (allowedApps.isNotEmpty()) {
            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF1E293B))
            Text(
                text = "LAUNCH APP",
                color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(allowedApps.size) { idx ->
                    val app = allowedApps[idx]
                    val pkgName = app.optString("package_name", "")
                    val appName = app.optString("app_name", pkgName)
                    var isFocused by remember { mutableStateOf(false) }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val isInstalled = remember(pkgName) {
                        try { context.packageManager.getPackageInfo(pkgName, 0); true }
                        catch (_: Exception) { false }
                    }
                    Column(
                        modifier = Modifier
                            .width(90.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isFocused) androidx.compose.ui.graphics.Color(0xFF1E293B) else androidx.compose.ui.graphics.Color(0xFF0F172A))
                            .border(1.dp, if (isFocused) androidx.compose.ui.graphics.Color(0xFF818CF8) else androidx.compose.ui.graphics.Color(0xFF1E293B), RoundedCornerShape(10.dp))
                            .clickable(enabled = isInstalled) {
                                try {
                                    val intent = context.packageManager.getLaunchIntentForPackage(pkgName)
                                        ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkgName)
                                    if (intent != null) context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable()
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val icon: android.graphics.Bitmap? = remember(pkgName) {
                            if (!isInstalled) return@remember null
                            try {
                                val d = context.packageManager.getApplicationIcon(pkgName)
                                if (d is android.graphics.drawable.BitmapDrawable) d.bitmap
                                else {
                                    val bmp = android.graphics.Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bmp)
                                    d.setBounds(0, 0, canvas.width, canvas.height)
                                    d.draw(canvas)
                                    bmp
                                }
                            } catch (_: Exception) { null }
                        }
                        if (icon != null) {
                            androidx.compose.foundation.Image(
                                bitmap = icon.asImageBitmap(),
                                contentDescription = appName,
                                modifier = Modifier.size(40.dp)
                            )
                        } else {
                            Box(Modifier.size(40.dp).background(androidx.compose.ui.graphics.Color(0xFF1E293B), RoundedCornerShape(6.dp)), Alignment.Center) {
                                Text("?", color = androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 18.sp)
                            }
                        }
                        Text(appName, color = if (isInstalled) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF64748B), fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
                    }
                }
            }
            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF1E293B))
        }

            var isRelinkFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onRelinkClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRelinkFocused) androidx.compose.ui.graphics.Color(0xFFF59E0B) else androidx.compose.ui.graphics.Color(0xFFD97706),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier
                    .onFocusChanged { isRelinkFocused = it.isFocused }
                    .border(
                        width = 2.dp,
                        color = if (isRelinkFocused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "RELINK TV",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCloseFocused) androidx.compose.ui.graphics.Color(0xFF6366F1) else androidx.compose.ui.graphics.Color(0xFF4F46E5),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { isCloseFocused = it.isFocused }
                    .border(
                        width = 2.dp,
                        color = if (isCloseFocused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "CLOSE DASHBOARD",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

private fun generateQrCodeBitmap(content: String): Bitmap? {
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

@Composable
fun LoserPayPendingContent(
    qrLink: String,
    playerA: String,
    playerB: String,
    game: String
) {
    val qrBitmap = remember(qrLink) {
        if (qrLink.isNotEmpty()) generateQrCodeBitmap(qrLink) else null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .background(androidx.compose.ui.graphics.Color(0xFF0F172A).copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            .border(2.dp, androidx.compose.ui.graphics.Color(0xFF00E676).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Details
        Column(
            modifier = Modifier.weight(1.5f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚔ LOSER PAY CHALLENGE ⚔",
                color = androidx.compose.ui.graphics.Color(0xFF00E676),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )

            Text(
                text = "A Loser Pay session has been initiated. Both players must scan the QR code to accept the challenge and verify their billing mode on their mobile phone to unlock this screen.",
                color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                fontSize = 14.sp,
                lineHeight = 22.sp
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Game:", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 13.sp)
                        Text(game.ifEmpty { "FIFA" }, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Players:", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 13.sp)
                        Text(
                            text = "${playerA.ifEmpty { "Player A" }} vs ${playerB.ifEmpty { "Player B" }}",
                            color = androidx.compose.ui.graphics.Color(0xFFF59E0B),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Right Column: QR Code
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Scan to play",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    CircularProgressIndicator(
                        color = androidx.compose.ui.graphics.Color(0xFF00E676),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Text(
                text = "SCAN QR CODE TO JOIN",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            if (qrLink.isNotEmpty()) {
                Text(
                    text = qrLink,
                    color = androidx.compose.ui.graphics.Color(0xFF64748B),
                    fontSize = 9.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}
