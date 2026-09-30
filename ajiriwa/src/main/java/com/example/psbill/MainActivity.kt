package com.example.psbill

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.hardware.camera2.CaptureRequest
import android.app.role.RoleManager
import android.os.Build
import android.provider.Telephony
import com.example.psbill.core.feature
import com.example.psbill.core.featureKeys
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun normalizeServerHost(raw: String): String =
        raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    private fun apiBaseUrl(raw: String): String = "https://${normalizeServerHost(raw)}"

    private fun wsBaseUrl(raw: String): String = "wss://${normalizeServerHost(raw)}"

    private fun prettyLabel(raw: String): String {
        val value = raw.trim().replace('_', ' ').replace('-', ' ')
        if (value.isBlank()) return "Arcade"
        return value.split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun deriveDisplayName(userJsonStr: String?, fallback: String): String {
        if (!userJsonStr.isNullOrBlank()) {
            try {
                val user = JSONObject(userJsonStr)
                val keys = listOf("partner_name", "arcade_name", "business_name", "company_name", "display_name", "full_name", "name")
                for (key in keys) {
                    val candidate = user.optString(key, "").trim()
                    if (candidate.isNotEmpty()) return candidate
                }
            } catch (_: Exception) {}
        }
        return prettyLabel(fallback)
    }

    private fun isAdmin(userJsonStr: String?): Boolean {
        if (userJsonStr.isNullOrEmpty()) return false
        try {
            val user = JSONObject(userJsonStr)
            val singleRole = user.optString("role", "")
            if (singleRole.equals("owner", ignoreCase = true) || singleRole.equals("admin", ignoreCase = true) || singleRole.equals("super_admin", ignoreCase = true)) {
                return true
            }
            val isSuperAdmin = user.optBoolean("is_super_admin", false)
            if (isSuperAdmin) return true
            val roles = user.optJSONArray("roles")
            if (roles != null) {
                for (i in 0 until roles.length()) {
                    val roleJson = roles.getJSONObject(i)
                    val roleName = roleJson.optString("name")
                    if (roleName.equals("Admin", ignoreCase = true) || roleName.equals("Owner", ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun hasPermission(userJsonStr: String?, permissionName: String): Boolean {
        if (userJsonStr.isNullOrEmpty()) return false
        if (isAdmin(userJsonStr)) return true
        try {
            val user = JSONObject(userJsonStr)
            val perms = user.optJSONArray("permissions")
            if (perms != null) {
                for (i in 0 until perms.length()) {
                    val p = perms.optJSONObject(i)
                    if (p != null) {
                        val name = p.optString("name", "")
                        if (name.equals(permissionName, ignoreCase = true)) return true
                    } else {
                        val name = perms.optString(i)
                        if (name.equals(permissionName, ignoreCase = true)) return true
                    }
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun parseServerTimestampMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val numeric = trimmed.toLongOrNull()
        if (numeric != null) {
            return if (numeric < 1_000_000_000_000L) numeric * 1000L else numeric
        }
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val normalized = trimmed.substringBefore(".").replace("Z", "")
            format.parse(normalized)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun deviceDisconnectedReason(device: JSONObject, nowMillis: Long = System.currentTimeMillis()): String? {
        if (device.has("is_online") && !device.optBoolean("is_online", true)) {
            return "Offline"
        }
        if (device.has("connected") && !device.optBoolean("connected", true)) {
            return "Disconnected"
        }
        if (device.has("is_connected") && !device.optBoolean("is_connected", true)) {
            return "Disconnected"
        }

        val status = device.optString("status", "").uppercase(Locale.US)
        if (status in setOf("OFFLINE", "DISCONNECTED", "UNREACHABLE")) {
            return status.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }

        val lastSeenRaw = device.optString("last_seen_at").ifBlank { device.optString("last_seen") }
        val lastSeenMillis = parseServerTimestampMillis(lastSeenRaw)
        if (lastSeenMillis != null) {
            val staleForMs = nowMillis - lastSeenMillis
            if (staleForMs > 25_000) {
                val secs = staleForMs / 1000
                return "No heartbeat ${secs}s"
            }
        }
        return null
    }

    private fun handleQrActivation(
        qrValue: String,
        authToken: String,
        serverDomain: String,
        getHeaders: () -> Headers,
        deviceId: String? = null,
        callback: (Boolean, String) -> Unit
    ) {
        try {
            val json = JSONObject(qrValue)
            val fingerprint = json.getString("fingerprint")
            val tvName = json.optString("tv_name", "TV Screen")
            val consoleType = json.optString("console_type", "PS5")
            
            val url = "${apiBaseUrl(serverDomain)}/api/devices/activate-by-qr"
            val payload = JSONObject().apply {
                put("fingerprint", fingerprint)
                put("device_name", tvName)
                put("console_type", consoleType)
                if (deviceId != null) {
                    put("device_id", deviceId)
                }
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .headers(getHeaders())
                .build()
                
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { callback(false, "Network error: ${e.message}") }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bodyStr = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            val msg = try { JSONObject(bodyStr).getString("message") } catch (e: Exception) { "Device linked successfully" }
                            runOnUiThread { callback(true, msg) }
                        } else {
                            val err = try { JSONObject(bodyStr).getString("error") } catch (e: Exception) { "Failed to link device: ${response.code}" }
                            runOnUiThread { callback(false, err) }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            runOnUiThread { callback(false, "Invalid QR Code format") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
        val savedServer = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val savedPartner = prefs.getString("partner_id", "default_partner") ?: "default_partner"
        val savedToken = prefs.getString("auth_token", "") ?: ""
        val savedUser = prefs.getString("user_json", "") ?: ""
        val savedPrinterIp = prefs.getString("printer_ip", "") ?: ""

        setContent {
            com.example.psbill.ui.theme.AjiriwaTheme {
                AttenderApp(savedServer, savedPartner, savedToken, savedUser, savedPrinterIp)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AttenderApp(initialServer: String, initialPartner: String, initialToken: String, initialUser: String, initialPrinterIp: String = "") {
        val context = LocalContext.current
        var serverDomain by remember { mutableStateOf(initialServer) }
        var partnerId by remember { mutableStateOf(initialPartner) }
        var displayName by remember { mutableStateOf(deriveDisplayName(initialUser, initialPartner)) }
        var authToken by remember { mutableStateOf(initialToken) }
        var userJson by remember { mutableStateOf(initialUser) }
        var printerIp by remember { mutableStateOf(initialPrinterIp) }
        
        val isAdminUser = remember(userJson) { isAdmin(userJson) }
        val canViewVouchers = remember(userJson) { isAdminUser || hasPermission(userJson, "kiosk.view_vouchers") || hasPermission(userJson, "kiosk.create_vouchers") }
        val canViewPricing = remember(userJson) { isAdminUser || hasPermission(userJson, "kiosk.view_pricing") || hasPermission(userJson, "kiosk.manage_games") }
        val canViewReports = remember(userJson) { isAdminUser || hasPermission(userJson, "kiosk.view_reports") || hasPermission(userJson, "kiosk.download_reports") }
        val canViewCustomers = remember(userJson) { isAdminUser || hasPermission(userJson, "customers.view") || hasPermission(userJson, "kiosk.view_reports") }
        val canViewWifi = remember(userJson) { isAdminUser || hasPermission(userJson, "wifi.view") || hasPermission(userJson, "wifi_kiosk") || hasPermission(userJson, "kiosk.view_reports") }
        val canSendSms = remember(userJson) { isAdminUser || hasPermission(userJson, "kiosk.send_sms") }

        data class AppTab(val title: String, val index: Int)

        val availableTabs = remember(isAdminUser, canViewVouchers, canViewPricing, canViewReports, canViewCustomers, canViewWifi) {
            val list = mutableListOf<AppTab>()
            list.add(AppTab("Screens", 0))
            list.add(AppTab("Activity", 1))
            list.add(AppTab("SMS Manager", 2))
            list.add(AppTab("Catalogue", 3))
            list.add(AppTab("Orders", 4))
            if (canViewCustomers) list.add(AppTab("Customers", 5))
            if (canViewWifi) list.add(AppTab("WiFi Billing", 6))
            if (canViewVouchers) list.add(AppTab("Vouchers", 7))
            if (canViewPricing) list.add(AppTab("Pricing", 8))
            if (canViewReports) list.add(AppTab("Reports", 9))
            list.add(AppTab("Settings", 10))
            list
        }
        
        var selectedTab by remember { mutableStateOf(0) }
        // Web-parity navigation: a string key per module (not an index), the
        // allowed-modules set fetched from the API, and the arcade-agent flag.
        val arcadeAgent = remember(userJson) { com.example.psbill.core.Permissions.isArcadeAgent(userJson) }
        var selectedKey by remember { mutableStateOf("overview") }  // Ajiriwa Client: Dashboard is home
        var permissionsJson by remember { mutableStateOf("") }   // {"modules":[...]} like the web
        var arcadeSub by remember { mutableStateOf("screens") }   // sub-nav inside Gaming Arcade

        val devices = remember { mutableStateListOf<JSONObject>() }
        val games = remember { mutableStateListOf<JSONObject>() }
        var isLoadingGames by remember { mutableStateOf(false) }
        val dailyReportSummary = remember { mutableStateOf<JSONObject?>(null) }
        val systemLogs = remember { mutableStateListOf<JSONObject>() }
        
        var selectedDevice by remember { mutableStateOf<JSONObject?>(null) }
        var isStartSessionOpen by remember { mutableStateOf(false) }
        var isScannerOpen by remember { mutableStateOf(false) }
        var relinkingDeviceForScan by remember { mutableStateOf<JSONObject?>(null) }
        var isManualLinkOpen by remember { mutableStateOf(false) }
        var historyDeviceForDialog by remember { mutableStateOf<JSONObject?>(null) }
        var inspectingDeviceForQueue by remember { mutableStateOf<JSONObject?>(null) }
        val queueTickets = remember { mutableStateListOf<JSONObject>() }
        var isLoadingQueue by remember { mutableStateOf(false) }
        var startSessionQueueTicketId by remember { mutableStateOf<String?>(null) }
        var initialPrefillGame by remember { mutableStateOf("") }
        var initialPrefillPlayerName by remember { mutableStateOf("") }
        var wsConnected by remember { mutableStateOf(false) }
        val disconnectedTvNames = remember { mutableStateListOf<String>() }

        // Camera Permission Launcher
        var hasCameraPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            )
        }
        val requestPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted ->
                hasCameraPermission = granted
                if (granted) {
                    isScannerOpen = true
                } else {
                    Toast.makeText(context, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
                }
            }
        )

        val moduleCtx = com.example.psbill.core.ModuleContext(
            server = serverDomain,
            headers = {
                Headers.Builder().apply { if (authToken.isNotEmpty()) add("Authorization", "Bearer $authToken") }.build()
            },
            partnerId = partnerId,
            userJson = userJson,
            isAdmin = isAdminUser,
            canSendSms = canSendSms,
            printerIp = printerIp,
            onPrinterIpChange = { newIp ->
                printerIp = newIp
                getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE).edit().putString("printer_ip", newIp).apply()
            },
            navigateTo = { key -> selectedKey = key },
        )

        val saveSettings = {
            val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("server_domain", normalizeServerHost(serverDomain))
                putString("partner_id", partnerId)
                putString("auth_token", authToken)
                apply()
            }
            Toast.makeText(context, "Settings Saved", Toast.LENGTH_SHORT).show()
        }

        val getHeaders = {
            Headers.Builder().apply {
                if (authToken.isNotEmpty()) {
                    add("Authorization", "Bearer $authToken")
                }
            }.build()
        }

        // Fetch the partner's enabled modules (same source the web uses to build
        // its sidebar) so the drawer shows exactly what this partner may use.
        LaunchedEffect(authToken, partnerId) {
            if (authToken.isEmpty() || partnerId.isEmpty()) return@LaunchedEffect
            val url = "${apiBaseUrl(serverDomain)}/api/v1/partners/$partnerId/modules"
            val req = Request.Builder().url(url).headers(getHeaders()).build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    val slugs = com.example.psbill.core.Permissions.parseModulesResponse(body)
                    val json = JSONObject().put("modules", JSONArray(slugs.toList())).toString()
                    runOnUiThread { permissionsJson = json }
                }
            })
        }

        val handleAuthError = {
            runOnUiThread {
                authToken = ""
                partnerId = ""
                userJson = ""
                wsConnected = false
                disconnectedTvNames.clear()
                val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    remove("auth_token")
                    remove("partner_id")
                    remove("user_json")
                    apply()
                }
                Toast.makeText(context, "Session expired, please login again", Toast.LENGTH_LONG).show()
            }
        }

        val recomputeDisconnectedTvs = {
            val now = System.currentTimeMillis()
            val names = devices.mapNotNull { device ->
                if (deviceDisconnectedReason(device, now) != null) {
                    device.optString("name", "Unknown TV")
                } else {
                    null
                }
            }.distinct()
            disconnectedTvNames.clear()
            disconnectedTvNames.addAll(names)
        }

        val fetchDevices = {
            if (authToken.isNotEmpty()) {
                val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices"
                val request = Request.Builder().url(url).headers(getHeaders()).build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread { Toast.makeText(context, "Fetch failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = response.body?.string() ?: return
                            if (response.isSuccessful) {
                                runOnUiThread {
                                    devices.clear()
                                    val arr = JSONArray(body)
                                    for (i in 0 until arr.length()) {
                                        devices.add(arr.getJSONObject(i))
                                    }
                                    recomputeDisconnectedTvs()
                                }
                            } else if (response.code == 401) {
                                handleAuthError()
                            }
                        }
                    }
                })
            }
        }

        val fetchGames = {
            if (authToken.isNotEmpty()) {
                isLoadingGames = true
                val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/games"
                val request = Request.Builder().url(url).headers(getHeaders()).build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            isLoadingGames = false
                            Log.e("MainActivity", "Games fetch failed: ${e.message}")
                        }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = response.body?.string() ?: return
                            runOnUiThread { isLoadingGames = false }
                            if (response.isSuccessful) {
                                runOnUiThread {
                                    games.clear()
                                    val arr = JSONArray(body)
                                    for (i in 0 until arr.length()) {
                                        games.add(arr.getJSONObject(i))
                                    }
                                }
                            } else if (response.code == 401) {
                                handleAuthError()
                            }
                        }
                    }
                })
            }
        }

        val fetchDailyReport = {
            if (authToken.isNotEmpty()) {
                val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/reports/daily"
                val request = Request.Builder().url(url).headers(getHeaders()).build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = response.body?.string() ?: return
                            if (response.isSuccessful) {
                                runOnUiThread {
                                    dailyReportSummary.value = JSONObject(body)
                                }
                            } else if (response.code == 401) {
                                handleAuthError()
                            }
                        }
                    }
                })
            }
        }

        val startSessionApi = { devId: String, mode: String, game: String, amount: Double, mins: Int, overtime: Boolean, voucherCode: String, queueTicketId: String? ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$devId/start"
            val payload = JSONObject().apply {
                put("billing_mode", mode)
                put("current_game", game)
                put("voucher_code", voucherCode)
                put("allow_overtime", overtime)
                if (!queueTicketId.isNullOrEmpty()) {
                    put("queue_ticket_id", queueTicketId)
                }
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else {
                            runOnUiThread {
                                isStartSessionOpen = false
                                fetchDevices()
                            }
                        }
                    }
                }
            })
        }

        val fetchQueueTickets = { deviceId: String ->
            isLoadingQueue = true
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/queue/tickets?device_id=$deviceId"
            val request = Request.Builder().url(url).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { isLoadingQueue = false }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else if (response.isSuccessful) {
                            val bodyStr = response.body?.string() ?: "[]"
                            try {
                                val arr = JSONArray(bodyStr)
                                runOnUiThread {
                                    queueTickets.clear()
                                    for (i in 0 until arr.length()) {
                                        queueTickets.add(arr.getJSONObject(i))
                                    }
                                    isLoadingQueue = false
                                }
                            } catch (e: Exception) {
                                runOnUiThread { isLoadingQueue = false }
                            }
                        } else {
                            runOnUiThread { isLoadingQueue = false }
                        }
                    }
                }
            })
        }

        val enqueuePlayer = { deviceId: String, gameName: String, playerName: String, playerPhone: String, giveWifi: Boolean, wifiMinutes: Int? ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/queue/tickets"
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("game_name", gameName)
                put("player_name", playerName)
                put("player_phone", playerPhone)
                put("send_sms", true)                        // ticket + estimated wait by SMS
                put("give_wifi", giveWifi && playerPhone.isNotBlank())
                if (wifiMinutes != null && wifiMinutes > 0) put("wifi_minutes", wifiMinutes)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else if (response.isSuccessful) {
                            val res = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                            val msg = buildString {
                                append("Ticket #${res?.optJSONObject("ticket")?.optInt("ticket_number") ?: "-"}")
                                res?.optInt("estimated_wait_minutes")?.let { append(" · ~$it min wait") }
                                res?.optJSONObject("wifi_pass")?.optString("voucher_code")?.takeIf { it.isNotBlank() }?.let { code ->
                                    append(" · WiFi $code (${res.optJSONObject("wifi_pass")?.optInt("minutes")} min)")
                                }
                                if (res?.optBoolean("sms_sent") == true) append(" · SMS sent")
                            }
                            runOnUiThread {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                fetchQueueTickets(deviceId)
                            }
                        }
                    }
                }
            })
        }

        val renameDevice = { deviceId: String, newName: String ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices"
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("device_name", newName)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { Toast.makeText(context, "Rename failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else if (response.isSuccessful) {
                            runOnUiThread {
                                Toast.makeText(context, "Device renamed successfully!", Toast.LENGTH_SHORT).show()
                                fetchDevices()
                            }
                        } else {
                            runOnUiThread { Toast.makeText(context, "Rename failed: ${response.code}", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            })
        }

        val updateTicketStatus = { ticketId: String, deviceId: String, newStatus: String ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/queue/tickets/$ticketId/status"
            val payload = JSONObject().apply {
                put("status", newStatus)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else if (response.isSuccessful) {
                            runOnUiThread {
                                fetchQueueTickets(deviceId)
                            }
                        }
                    }
                }
            })
        }

        val startLoserPayApi = { devId: String, mode: String, game: String, playerA: String, playerB: String ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/loser-pay/start"
            val payload = JSONObject().apply {
                put("device_id", devId)
                put("billing_mode", mode)
                put("current_game", game)
                put("player_a", playerA.ifEmpty { "Player A" })
                put("player_b", playerB.ifEmpty { "Player B" })
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else {
                            runOnUiThread {
                                isStartSessionOpen = false
                                fetchDevices()
                            }
                        }
                    }
                }
            })
        }

        val stopSessionApi = { devId: String ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$devId/stop"
            val request = Request.Builder().url(url).post("{}".toRequestBody(JSON_MEDIA_TYPE)).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else {
                            runOnUiThread { fetchDevices() }
                        }
                    }
                }
            })
        }

        val deleteDeviceApi = { devId: String ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$devId"
            val request = Request.Builder().url(url).delete().headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { Toast.makeText(context, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else {
                            runOnUiThread {
                                fetchDevices()
                                Toast.makeText(context, "Device deleted successfully", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            })
        }

        val resetSessionApi = { devId: String ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$devId/reset"
            val request = Request.Builder().url(url).post("{}".toRequestBody(JSON_MEDIA_TYPE)).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else {
                            runOnUiThread { fetchDevices() }
                        }
                    }
                }
            })
        }

        val continueSessionApi = { devId: String ->
            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/$devId/continue"
            val request = Request.Builder().url(url).post("{}".toRequestBody(JSON_MEDIA_TYPE)).headers(getHeaders()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code == 401) {
                            handleAuthError()
                        } else {
                            runOnUiThread { fetchDevices() }
                        }
                    }
                }
            })
        }

        DisposableEffect(authToken, partnerId) {
            if (authToken.isEmpty() || partnerId.isEmpty() || !com.example.psbill.core.CompiledModules.has("arcade")) {
                return@DisposableEffect onDispose {}
            }
            
            var ws: WebSocket? = null
            var isDisposed = false
            
            fun connect() {
                if (isDisposed) return
                val wsUrl = "${wsBaseUrl(serverDomain)}/kiosk/ws/mobile/$partnerId/attender_dashboard"
                val request = Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer $authToken")
                    .build()
                    
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d("MainActivityWS", "Connected to mobile WebSocket: $wsUrl")
                        runOnUiThread { wsConnected = true }
                    }
                    
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d("MainActivityWS", "WS Message received: $text")
                        try {
                            val payload = JSONObject(text)
                            val event = payload.optString("event")
                            val devData = payload.optJSONObject("data")
                            
                            if (event == "update_timer" && devData != null) {
                                runOnUiThread {
                                    val devId = devData.optString("id")
                                    val index = devices.indexOfFirst { it.optString("id") == devId }
                                    if (index != -1) {
                                        val merged = JSONObject(devices[index].toString())
                                        val keys = devData.keys()
                                        while (keys.hasNext()) {
                                            val key = keys.next()
                                            merged.put(key, devData.opt(key))
                                        }
                                        devices[index] = merged
                                        recomputeDisconnectedTvs()
                                    }
                                }
                            } else if (event == "system_log" && devData != null) {
                                runOnUiThread {
                                    systemLogs.add(0, devData)
                                    if (systemLogs.size > 100) {
                                        systemLogs.removeAt(systemLogs.size - 1)
                                    }
                                }
                            } else if (event == "games_updated") {
                                runOnUiThread {
                                    fetchGames()
                                }
                            } else if (listOf("session_started", "session_stopped", "session_updated", "admin_unlock", "device_configured", "device_deleted", "loser_pay_started", "round_result", "loser_pay_ended").contains(event)) {
                                runOnUiThread {
                                    fetchDevices()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivityWS", "WS message parse error: ${e.message}")
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e("MainActivityWS", "WS Failure: ${t.message}, retrying in 3s...")
                        runOnUiThread { wsConnected = false }
                        if (!isDisposed) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                connect()
                            }, 3000)
                        }
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d("MainActivityWS", "WS Closed: $reason, retrying in 3s...")
                        runOnUiThread { wsConnected = false }
                        if (!isDisposed) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                connect()
                            }, 3000)
                        }
                    }
                }
                
                ws = client.newWebSocket(request, listener)
            }
            
            connect()
            
            onDispose {
                isDisposed = true
                wsConnected = false
                try {
                    ws?.close(1000, "Effect disposed")
                } catch (e: Exception) {}
            }
        }

        LaunchedEffect(selectedTab, authToken) {
            if (!com.example.psbill.core.CompiledModules.has("arcade") || authToken.isEmpty()) return@LaunchedEffect
            if (selectedTab == 0) {
                fetchDevices()
                fetchGames()
            } else if (selectedTab == 3 && isAdminUser) {
                fetchDailyReport()
            }
        }

        // Polling fallback to keep device state synchronized if websocket delivery drops.
        LaunchedEffect(selectedTab, authToken) {
            if (selectedTab != 0 || authToken.isEmpty() || !com.example.psbill.core.CompiledModules.has("arcade")) return@LaunchedEffect
            while (true) {
                fetchDevices()
                kotlinx.coroutines.delay(15000)
            }
        }

        if (authToken.isEmpty()) {
            LoginScreen(
                serverDomain = serverDomain,
                onLoginSuccess = { token, partId, userStr ->
                    authToken = token
                    partnerId = partId
                    userJson = userStr
                    displayName = deriveDisplayName(userStr, partId)
                    val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("server_domain", normalizeServerHost(serverDomain))
                        putString("auth_token", token)
                        putString("partner_id", partId)
                        putString("user_json", userStr)
                        putString("partner_display_name", displayName)
                        apply()
                    }
                }
            )
        } else {
            val allowedModules = com.example.psbill.core.Permissions.allowedModules(permissionsJson)
            // Arcade agents (and admins) always get the arcade entry; everyone gets
            // SMS (mobile-only core) + the always-visible items, exactly like web.
            val effectiveAllowed = if (arcadeAgent || isAdminUser) allowedModules + "kiosk" else allowedModules
            val drawerItems = com.example.psbill.core.AppModules.partnerVisible(effectiveAllowed, canSendSms)
            // The home screen may not be compiled in (or not enabled for this partner).
            LaunchedEffect(drawerItems.map { it.key }) {
                if (drawerItems.isNotEmpty() && drawerItems.none { it.key == selectedKey } && selectedKey != "settings") {
                    selectedKey = drawerItems.first().key
                }
            }
            val roleLabel = when {
                isAdminUser -> "Administrator"
                com.example.psbill.core.Permissions.isArcadeAgent(userJson) -> "Arcade attendant"
                else -> "Business"
            }
            val doLogout: () -> Unit = {
                com.example.psbill.core.CompiledModules.features.forEach { runCatching { it.onLogout(context) } }
                com.example.psbill.core.DeviceAgent.onLogout(context)
                authToken = ""
                partnerId = ""
                userJson = ""
                val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    remove("auth_token"); remove("partner_id"); remove("user_json"); apply()
                }
                Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show()
            }
            com.example.psbill.core.CompiledModules.features.forEach { feature ->
                androidx.compose.runtime.key(feature.nav.key) { feature.SessionEffects(moduleCtx) }
            }
            // always-on phone agent: continuous location + sync heartbeat
            com.example.psbill.ui.DeviceAgentEffects(moduleCtx)
            com.example.psbill.ui.AppScaffold(
                brandName = displayName,
                subtitle = roleLabel,
                items = drawerItems,
                selectedKey = selectedKey,
                onSelect = { key -> selectedKey = key },
                onLogout = doLogout,
                topBarActions = {
                    if (wsConnected) {
                        Text("● Live", color = Color(0xFF00E676), fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
                    }
                    com.example.psbill.core.CompiledModules.features.forEach { feature ->
                        androidx.compose.runtime.key(feature.nav.key) { feature.TopBarAction(moduleCtx) }
                    }
                    if (com.example.psbill.core.CompiledModules.has("arcade")) Button(
                        onClick = {
                            if (hasCameraPermission) { isScannerOpen = true }
                            else { requestPermissionLauncher.launch(Manifest.permission.CAMERA) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                        modifier = Modifier.padding(end = 4.dp)
                    ) { Text("Scan", color = Color.Black, fontSize = 12.sp) }
                    if (com.example.psbill.core.CompiledModules.has("arcade")) Button(
                        onClick = { isManualLinkOpen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                    ) { Text("Link", color = Color.Black, fontSize = 12.sp) }
                },
            ) { contentModifier ->
                Column(modifier = contentModifier) {
                    if (selectedKey == "kiosk" && (!wsConnected || disconnectedTvNames.isNotEmpty())) {
                        val hasDisconnectedTvs = disconnectedTvNames.isNotEmpty()
                        val bannerText = when {
                            !wsConnected && hasDisconnectedTvs ->
                                "Realtime link unstable. Disconnected TVs: ${disconnectedTvNames.joinToString(", ")}"
                            !wsConnected ->
                                "Realtime link to server is down. Showing polling fallback."
                            else ->
                                "Disconnected TVs: ${disconnectedTvNames.joinToString(", ")}"
                        }
                        Surface(
                            color = Color(0xFF5C1A1A),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = bannerText,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedKey) {
                        // ── Gaming Arcade: its own module with the full arcade core
                        // (link screens, start session, queue, loser-pay) + gaming
                        // receipts/vouchers/pricing/reports as sub-tabs. Shown only
                        // when the arcade module is enabled (or for arcade agents).
                        "kiosk" -> if (com.example.psbill.core.CompiledModules.has("arcade")) {
                            val subTabs = buildList {
                                add("screens" to "Screens")
                                add("orders" to "Gaming Orders")
                                if (canViewVouchers) add("vouchers" to "Vouchers")
                                if (canViewPricing) add("pricing" to "Pricing")
                                if (canViewReports) add("reports" to "Reports")
                                add("activity" to "Activity")
                                if (com.example.psbill.core.CompiledModules.has("wifi")) add("wifipass" to "WiFi passes")
                            }
                            Column(Modifier.fillMaxSize()) {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(subTabs) { pair ->
                                        val sel = arcadeSub == pair.first
                                        Surface(
                                            color = if (sel) Color(0xFF8B5CF6) else Color(0xFF171A28),
                                            shape = RoundedCornerShape(999.dp),
                                            modifier = Modifier.clickable { arcadeSub = pair.first }
                                        ) {
                                            Text(
                                                pair.second,
                                                color = if (sel) Color.White else Color.Gray,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    when (arcadeSub) {
                                        "screens" -> ScreensTab(
                                            devices = devices,
                                            isAdminUser = isAdminUser,
                                            serverDomain = serverDomain,
                                            getHeaders = getHeaders,
                                            onStart = { selectedDevice = it; isStartSessionOpen = true },
                                            onStop = { deviceId -> stopSessionApi(deviceId) },
                                            onContinue = { deviceId -> continueSessionApi(deviceId) },
                                            onReset = { deviceId -> resetSessionApi(deviceId) },
                                            onDelete = { deviceId -> deleteDeviceApi(deviceId) },
                                            onRefresh = { fetchDevices() },
                                            onShowHistory = { historyDeviceForDialog = it },
                                            onShowQueue = { device ->
                                                inspectingDeviceForQueue = device
                                                fetchQueueTickets(device.optString("id"))
                                            }
                                        )
                                        "orders" -> OrdersTab(serverDomain, getHeaders)
                                        "vouchers" -> VouchersTab(serverDomain, getHeaders, games, canSendSms)
                                        "pricing" -> PricingTab(serverDomain, getHeaders)
                                        "reports" -> ReportsTab(dailyReportSummary.value, serverDomain, getHeaders, authToken)
                                        "activity" -> ActivityTab(systemLogs)
                                        "wifipass" -> com.example.psbill.ui.WifiPassPanel(serverDomain, getHeaders)
                                    }
                                }
                            }
                        }
                        // ── Ajiriwa Client routes: feature modules compiled in via modules.properties
                        in com.example.psbill.core.CompiledModules.featureKeys ->
                            com.example.psbill.core.CompiledModules.feature(selectedKey)?.Content(moduleCtx)
                        "wifi" -> if (com.example.psbill.core.CompiledModules.has("wifi")) WifiBillingTab(serverDomain, getHeaders)
                        "device_sync" -> com.example.psbill.ui.DeviceSyncScreen(moduleCtx)
                        "settings" -> SettingsTab(
                            partner = partnerId,
                            serverDomain = serverDomain,
                            onServerDomainChange = { serverDomain = normalizeServerHost(it) },
                            onSaveServer = { saveSettings() },
                            onLogout = doLogout
                        )
                        else -> com.example.psbill.ui.screens.ComingSoonScreen(
                            drawerItems.firstOrNull { it.key == selectedKey }?.title ?: "Module"
                        )
                    }

                    if (isStartSessionOpen && selectedDevice != null) {
                        StartSessionDialog(
                            device = selectedDevice!!,
                            gamesList = games,
                            isLoadingGames = isLoadingGames,
                            initialGame = initialPrefillGame,
                            initialPlayerName = initialPrefillPlayerName,
                            onDismiss = {
                                isStartSessionOpen = false
                                startSessionQueueTicketId = null
                                initialPrefillGame = ""
                                initialPrefillPlayerName = ""
                            },
                            onConfirm = { mode, game, mins, voucherCode, overtime, isLoserPay, playerA, playerB ->
                                if (isLoserPay) {
                                    startLoserPayApi(selectedDevice!!.optString("id"), mode, game, playerA, playerB)
                                } else {
                                    startSessionApi(selectedDevice!!.optString("id"), mode, game, 0.0, mins, overtime, voucherCode, startSessionQueueTicketId)
                                }
                                startSessionQueueTicketId = null
                                initialPrefillGame = ""
                                initialPrefillPlayerName = ""
                            }
                        )
                    }

                    if (inspectingDeviceForQueue != null) {
                        DeviceQueueDialog(
                            device = inspectingDeviceForQueue!!,
                            queueTickets = queueTickets,
                            isLoadingQueue = isLoadingQueue,
                            gamesList = games,
                            onDismiss = { inspectingDeviceForQueue = null },
                            onAddPlayer = { gameName, playerName, playerPhone, giveWifi, wifiMinutes ->
                                enqueuePlayer(inspectingDeviceForQueue!!.optString("id"), gameName, playerName, playerPhone, giveWifi, wifiMinutes)
                            },
                            onCallNext = { ticketId ->
                                updateTicketStatus(ticketId, inspectingDeviceForQueue!!.optString("id"), "CALLED")
                            },
                            onServe = { ticket ->
                                val devId = inspectingDeviceForQueue!!.optString("id")
                                startSessionQueueTicketId = ticket.optString("id")
                                initialPrefillGame = ticket.optString("game_name", "")
                                initialPrefillPlayerName = ticket.optString("player_name", "")
                                inspectingDeviceForQueue = null
                                selectedDevice = devices.find { it.optString("id") == devId }
                                isStartSessionOpen = true
                            },
                            onSkip = { ticketId ->
                                updateTicketStatus(ticketId, inspectingDeviceForQueue!!.optString("id"), "SKIPPED")
                            },
                            onCancel = { ticketId ->
                                updateTicketStatus(ticketId, inspectingDeviceForQueue!!.optString("id"), "CANCELLED")
                            },
                            onRelinkClick = {
                                relinkingDeviceForScan = inspectingDeviceForQueue
                                inspectingDeviceForQueue = null
                                isScannerOpen = true
                            },
                            onRenameDevice = { newName ->
                                val devId = inspectingDeviceForQueue!!.optString("id")
                                renameDevice(devId, newName)
                                inspectingDeviceForQueue = null
                            }
                        )
                    }

                    if (isScannerOpen) {
                        QRScannerDialog(
                            onDismiss = {
                                isScannerOpen = false
                                relinkingDeviceForScan = null
                            },
                            onScan = { qrValue ->
                                isScannerOpen = false
                                val devId = relinkingDeviceForScan?.optString("id")
                                relinkingDeviceForScan = null
                                handleQrActivation(qrValue, authToken, serverDomain, getHeaders, devId) { success, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    fetchDevices()
                                }
                            }
                        )
                    }

                    if (isManualLinkOpen) {
                        ManualLinkDialog(
                            onDismiss = { isManualLinkOpen = false },
                            onConfirm = { fingerprint, name, console ->
                                isManualLinkOpen = false
                                val qrMockPayload = JSONObject().apply {
                                    put("fingerprint", fingerprint)
                                    put("tv_name", name)
                                    put("console_type", console)
                                }.toString()
                                handleQrActivation(qrMockPayload, authToken, serverDomain, getHeaders) { success, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    fetchDevices()
                                }
                            }
                        )
                    }

                    if (historyDeviceForDialog != null) {
                        DeviceHistoryDialog(
                            device = historyDeviceForDialog!!,
                            server = serverDomain,
                            headers = getHeaders,
                            onDismiss = { historyDeviceForDialog = null }
                        )
                    }

                }
                }
            }
        }
    }

    @Composable
    fun ScreensTab(
        devices: List<JSONObject>,
        isAdminUser: Boolean,
        serverDomain: String,
        getHeaders: () -> Headers,
        onStart: (JSONObject) -> Unit,
        onStop: (String) -> Unit,
        onContinue: (String) -> Unit,
        onReset: (String) -> Unit,
        onDelete: (String) -> Unit,
        onRefresh: () -> Unit,
        onShowHistory: (JSONObject) -> Unit,
        onShowQueue: (JSONObject) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Live Screens", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) {
                    Text("Refresh", color = Color(0xFF00E676), fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No Connected Screens Found", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scan the TV QR code or use 'Link TV' above to link a console screen to this lounge.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().weight(1f)
                ) {
                    items(devices) { device ->
                        DeviceCard(device, isAdminUser, serverDomain, getHeaders, onStart, onStop, onContinue, onReset, onDelete, onShowHistory, onRefresh, onShowQueue)
                    }
                }
            }
        }
    }

    @Composable
    fun DeviceCard(
        device: JSONObject,
        isAdminUser: Boolean,
        serverDomain: String,
        getHeaders: () -> Headers,
        onStart: (JSONObject) -> Unit,
        onStop: (String) -> Unit,
        onContinue: (String) -> Unit,
        onReset: (String) -> Unit,
        onDelete: (String) -> Unit,
        onShowHistory: (JSONObject) -> Unit,
        onRefresh: () -> Unit,
        onShowQueue: (JSONObject) -> Unit
    ) {
        val status = device.optString("status", "LOCKED")
        val disconnectedReason = deviceDisconnectedReason(device)
        val isDisconnected = disconnectedReason != null
        val isOccupied = status == "ACTIVE" || status == "OVERTIME"
        val billingMode = device.optString("billing_mode", "POSTPAID")
        val activeBillingKes = device.optDouble("active_billing_kes", 0.0)
        val totalRevenue = device.optDouble("total_revenue", 0.0)
        val hasEndedSession = device.optBoolean("has_ended_session", false)
        
        val bgColor = if (isOccupied) Color(0xFF1E2638) else Color(0xFF161E2F)
        val borderColor = if (isOccupied) Color(0xFF00E676) else Color.Transparent

        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(12.dp)).clickable { onShowQueue(device) },
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        device.optString("name", "Unknown"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isAdminUser && !isOccupied && !hasEndedSession) {
                        IconButton(
                            onClick = { onDelete(device.optString("id")) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Device",
                                tint = Color.Red,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(device.optString("console_type", "PS5"), color = Color.Gray, fontSize = 12.sp)
                    Text("Total Rev: KES ${totalRevenue.toInt()}", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                val uptimeSecs = device.optInt("uptime_seconds", 0)
                val isAsleep = device.optBoolean("is_asleep", false)
                val uptimeStr = if (uptimeSecs >= 3600) "${uptimeSecs / 3600}h ${(uptimeSecs % 3600) / 60}m" else "${uptimeSecs / 60}m"

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Uptime: $uptimeStr", color = Color(0xFF00E676), fontSize = 11.sp)
                    if (isAdminUser) {
                        Text(
                            text = if (isAsleep) "SLEEPING" else "AWAKE",
                            color = if (isAsleep) Color(0xFFFF8A80) else Color(0xFFB9F6CA),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                if (isDisconnected) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Status: DISCONNECTED (${disconnectedReason})",
                        color = Color(0xFFFF8A80),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (isOccupied || hasEndedSession) {
                    val game = device.optString("current_game", "N/A")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (billingMode == "PREPAID") {
                            val secsRem = device.optInt("seconds_remaining", 0)
                            Text("Time Left: ${secsRem / 60}m", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        } else {
                            val secsElap = device.optInt("seconds_elapsed", 0)
                            Text("Time Elapsed: ${secsElap / 60}m", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Text("Charge: KES ${activeBillingKes.toInt()}", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text("Game: $game", color = Color.White, fontSize = 11.sp)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    if (hasEndedSession) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onContinue(device.optString("id")) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("CONTINUE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { onReset(device.optString("id")) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("RESET", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { onShowQueue(device) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B0F19)),
                                modifier = Modifier.weight(1f).border(1.dp, Color(0xFF334155), RoundedCornerShape(50.dp)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("QUEUE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onStop(device.optString("id")) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("STOP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { onShowQueue(device) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B0F19)),
                                modifier = Modifier.weight(1f).border(1.dp, Color(0xFF334155), RoundedCornerShape(50.dp)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("QUEUE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    if (!isDisconnected) {
                        Text("Status: Available", color = Color.Gray, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onStart(device) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp),
                            enabled = !isDisconnected
                        ) {
                            Text(if (isDisconnected) "OFFLINE" else "START", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onShowQueue(device) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B0F19)),
                            modifier = Modifier.weight(1f).border(1.dp, Color(0xFF334155), RoundedCornerShape(50.dp)),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("QUEUE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (isAdminUser) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val action = if (isAsleep) "wakeup" else "sleep"
                            val url = "${apiBaseUrl(serverDomain)}/api/v1/kiosk/devices/${device.optString("id")}/$action"
                            val request = Request.Builder().url(url).post("{}".toRequestBody(JSON_MEDIA_TYPE)).headers(getHeaders()).build()
                            client.newCall(request).enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {}
                                override fun onResponse(call: Call, response: Response) {
                                    if (response.isSuccessful) {
                                        onRefresh()
                                    }
                                }
                            })
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAsleep) Color(0xFF00E676) else Color(0xFF475569)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(if (isAsleep) "WAKE UP SCREEN" else "SLEEP SCREEN", color = if (isAsleep) Color.Black else Color.White, fontSize = 12.sp)
                    }
                }
                
                val recent = device.optJSONArray("recent_sessions")
                if (recent != null && recent.length() > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Recent Sessions:",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    for (i in 0 until minOf(recent.length(), 3)) {
                        val session = recent.getJSONObject(i)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${session.optString("game_name", "Game")} (${session.optInt("duration_minutes", 0)}m)",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "KES ${session.optDouble("amount_charged_kes", 0.0).toInt()}",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ActivityTab(systemLogs: List<JSONObject>) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Lounge Activity Log", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Real-time stream of lounge events", color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF070A13), shape = RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1E2638), shape = RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                if (systemLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No events received yet", color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(systemLogs) { log ->
                            val level = log.optString("level", "INFO")
                            val message = log.optString("message", "")
                            val timestamp = log.optString("timestamp", "")
                            
                            val timeStr = try {
                                if (timestamp.contains("T")) {
                                    timestamp.substringAfter("T").substringBefore(".")
                                } else {
                                    timestamp
                                }
                            } catch (e: Exception) {
                                ""
                            }
                            
                            val levelColor = when (level.uppercase()) {
                                "SUCCESS" -> Color(0xFF00E676)
                                "WARNING" -> Color(0xFFFFB74D)
                                "ERROR" -> Color(0xFFEF5350)
                                else -> Color(0xFFE0E0E0)
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("[$timeStr]", color = Color.Gray, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Text("[$level]", color = levelColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Text(message, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PricingTab(server: String, headers: () -> Headers) {
        var name by remember { mutableStateOf("") }
        var mac by remember { mutableStateOf("") }
        var console by remember { mutableStateOf("PS5") }
        var minCharge by remember { mutableStateOf("50") }
        var rate by remember { mutableStateOf("5") }

        val consoleOptions = listOf("PS5", "PS4", "XBOX", "PC")

        val registerDevice = {
            val url = "https://$server/api/v1/kiosk/devices"
            val payload = JSONObject().apply {
                put("device_name", name)
                put("mac_address", mac)
                put("console_type", console)
                put("minimum_charge_kes", minCharge.toDoubleOrNull() ?: 50.0)
                put("rate_per_minute_kes", rate.toDoubleOrNull() ?: 5.0)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).headers(headers()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    runOnUiThread {
                        name = ""
                        mac = ""
                        Toast.makeText(this@MainActivity, "Device Configured Successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Text("Configure Console Screen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Device Name (e.g. Screen 1)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    label = { Text("MAC Address (e.g. 02:00:00:00:00:00)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("Console Type", color = Color.White, modifier = Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    consoleOptions.forEach { option ->
                        Button(
                            onClick = { console = option },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (console == option) Color(0xFF00E676) else Color(0xFF161E2F)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(option, color = if (console == option) Color.Black else Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = minCharge,
                    onValueChange = { minCharge = it },
                    label = { Text("Minimum Charge (KES)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it },
                    label = { Text("Rate Per Minute (KES)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { registerDevice() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Register & Save Pricing", color = Color.Black)
                }
            }
        }
    }

    @Composable
    fun ReportsTab(report: JSONObject?, server: String, headers: () -> Headers, authToken: String = "") {
        val context = LocalContext.current
        var isDownloading by remember { mutableStateOf(false) }

        val sendEmail = {
            val url = "https://$server/api/v1/kiosk/reports/send-email"
            val request = Request.Builder().url(url).post("{}".toRequestBody(JSON_MEDIA_TYPE)).headers(headers()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Report Emailed", Toast.LENGTH_SHORT).show() }
                }
            })
        }

        val downloadCsv = {
            isDownloading = true
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val url = "https://$server/api/v1/kiosk/reports/export-csv?date_from=$today&date_to=$today"
            val request = Request.Builder().url(url).headers(headers()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        isDownloading = false
                        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bytes = resp.body?.bytes()
                        runOnUiThread {
                            isDownloading = false
                            if (resp.isSuccessful && bytes != null) {
                                try {
                                    val filename = "report_$today.csv"
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        val resolver = context.contentResolver
                                        val contentValues = android.content.ContentValues().apply {
                                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                                        }
                                        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                        if (uri != null) {
                                            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                                            Toast.makeText(context, "Saved to Downloads/$filename", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Could not create file", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                        downloads.mkdirs()
                                        java.io.File(downloads, filename).writeBytes(bytes)
                                        Toast.makeText(context, "Saved to Downloads/$filename", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Download failed (${resp.code})", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            })
        }

        if (report == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Loading daily analytics...", color = Color.Gray)
                }
            }
            return
        }

        val summary = try { report.getJSONObject("summary") } catch (e: Exception) { JSONObject() }
        val sessions = try { report.getJSONArray("sessions") } catch (e: Exception) { JSONArray() }
        val sessionsList = mutableListOf<JSONObject>()
        for (i in 0 until sessions.length()) {
            sessionsList.add(sessions.getJSONObject(i))
        }

        val totalRevenue = summary.optDouble("total_revenue_kes", 0.0)
        val totalMins = summary.optInt("total_minutes", 0)
        val sessionCount = summary.optInt("session_count", 0)

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Daily Lounge Analytics", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(java.text.SimpleDateFormat("EEEE, d MMM yyyy", java.util.Locale.US).format(java.util.Date()), color = Color.Gray, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101824)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Text("Revenue Summary", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("KES ${String.format("%.0f", totalRevenue)}", color = Color(0xFF00E676), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Revenue", color = Color.Gray, fontSize = 12.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$totalMins", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Minutes", color = Color.Gray, fontSize = 12.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$sessionCount", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Sessions", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { sendEmail() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Email Report", color = Color.Black, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { downloadCsv() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                        modifier = Modifier.weight(1f),
                        enabled = !isDownloading
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Download CSV", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                Text("Session Logs (${sessionsList.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            items(sessionsList) { log ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(log.optString("game_name", "Unknown Game"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${log.optString("console_type", "")} • ${log.optInt("duration_minutes")} min", color = Color.Gray, fontSize = 12.sp)
                            val billing = log.optString("billing_mode", "")
                            if (billing.isNotEmpty()) {
                                Text(billing, color = Color(0xFF4FC3F7), fontSize = 11.sp)
                            }
                        }
                        Text("KES ${String.format("%.0f", log.optDouble("amount_charged_kes", 0.0))}", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            if (sessionsList.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No sessions recorded today", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsTab(
        partner: String,
        onLogout: () -> Unit,
        serverDomain: String = "",
        onServerDomainChange: (String) -> Unit = {},
        onSaveServer: () -> Unit = {}
    ) {
        val context = LocalContext.current
        var editableServer by remember { mutableStateOf(serverDomain) }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Server Configuration", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        OutlinedTextField(
                            value = editableServer,
                            onValueChange = {
                                editableServer = it
                                onServerDomainChange(it)
                            },
                            label = { Text("API Server Domain") },
                            placeholder = { Text("e.g. api.ajiriwa.gidraf.dev", color = Color.DarkGray) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E676),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                unfocusedBorderColor = Color.DarkGray
                            )
                        )
                        Button(
                            onClick = {
                                onServerDomainChange(editableServer)
                                onSaveServer()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Server", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("Account", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Lounge ID: $partner", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            item {
                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log Out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LoginScreen(serverDomain: String, onLoginSuccess: (String, String, String) -> Unit) {
        val context = LocalContext.current
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var showServerField by remember { mutableStateOf(false) }
        var editServer by remember(serverDomain) { mutableStateOf(serverDomain) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "PlayGate",
                    color = Color(0xFF00E676),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Session Control",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color.Gray
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color.Gray
                    ),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (email.trim().isEmpty() || password.trim().isEmpty()) {
                            Toast.makeText(context, "Please enter email and password", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isLoading = true
                        
                        val payload = JSONObject().apply {
                            put("email", email.trim().lowercase())
                            put("password", password.trim())
                        }
                        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        val request = Request.Builder()
                            .url("${apiBaseUrl(serverDomain)}/auth/login")
                            .post(body)
                            .build()
                            
                        client.newCall(request).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                runOnUiThread {
                                    isLoading = false
                                    Toast.makeText(context, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                            override fun onResponse(call: Call, response: Response) {
                                response.use {
                                    val bodyStr = response.body?.string() ?: ""
                                    runOnUiThread {
                                        isLoading = false
                                        if (response.isSuccessful) {
                                            try {
                                                val json = JSONObject(bodyStr)
                                                val token = json.getString("refresh_token")
                                                val userJson = json.getJSONObject("user")
                                                val partnerId = userJson.optString("partner_id", "default_partner")
                                                // Merge permissions into user JSON so hasPermission() works
                                                val permsObj = json.optJSONObject("permissions")
                                                if (permsObj != null) {
                                                    val permsArr = permsObj.optJSONArray("permissions")
                                                    if (permsArr != null) userJson.put("permissions", permsArr)
                                                }
                                                val accountType = json.optString("account_type", "partner")
                                                userJson.put("account_type", accountType)
                                                if (accountType == "admin") userJson.put("is_super_admin", true)
                                                onLoginSuccess(token, partnerId, userJson.toString())
                                                Toast.makeText(context, "Login successful", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Invalid response: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            val err = try { JSONObject(bodyStr).getString("error") } catch (e: Exception) { "Invalid email or password" }
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                    } else {
                        Text("Log In", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                TextButton(onClick = { showServerField = !showServerField }) {
                    Text(if (showServerField) "Hide server settings" else "Change server", color = Color.Gray, fontSize = 12.sp)
                }

                if (showServerField) {
                    OutlinedTextField(
                        value = editServer,
                        onValueChange = { editServer = it },
                        label = { Text("API Server Domain", color = Color.Gray) },
                        placeholder = { Text("api.ajiriwa.gidraf.dev", color = Color.DarkGray) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    Button(
                        onClick = {
                            val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("server_domain", normalizeServerHost(editServer)).apply()
                            Toast.makeText(context, "Server saved. Restart app to apply.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2638)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Server Domain", color = Color.White)
                    }
                }
            }
        }
    }

    @Composable
    fun ManualLinkDialog(
        onDismiss: () -> Unit,
        onConfirm: (String, String, String) -> Unit
    ) {
        var fingerprint by remember { mutableStateOf("") }
        var tvName by remember { mutableStateOf("") }
        var consoleType by remember { mutableStateOf("PS5") }
        
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF161E2F),
            title = { Text("Link TV Manually", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fingerprint,
                        onValueChange = { fingerprint = it },
                        label = { Text("TV Fingerprint (from TV screen)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E676),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = tvName,
                        onValueChange = { tvName = it },
                        label = { Text("TV Nickname / Location") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E676),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Text("Console Type", color = Color.White)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("PS5", "PS4", "XBOX", "PC").forEach { option ->
                            Button(
                                onClick = { consoleType = option },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (consoleType == option) Color(0xFF00E676) else Color(0xFF0B0F19)
                                ),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(option, color = if (consoleType == option) Color.Black else Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirm(fingerprint, tvName, consoleType) },
                    enabled = fingerprint.isNotEmpty() && tvName.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("Link Device", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    @Composable
    fun StartSessionDialog(
        device: JSONObject,
        gamesList: List<JSONObject>,
        isLoadingGames: Boolean,
        initialGame: String = "",
        initialPlayerName: String = "",
        onDismiss: () -> Unit,
        onConfirm: (String, String, Int, String, Boolean, Boolean, String, String) -> Unit
    ) {
        val supportedModes = remember(device) {
            val isNull = device.isNull("supported_billing_modes")
            val raw = if (isNull) "PREPAID,POSTPAID" else {
                val value = device.optString("supported_billing_modes", "PREPAID,POSTPAID")
                if (value == "null" || value.isBlank()) "PREPAID,POSTPAID" else value
            }
            raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() && it != "NULL" }
        }
        val initialMode = remember(supportedModes) {
            if (supportedModes.contains("POSTPAID")) "POSTPAID" else "PREPAID"
        }

        var mode by remember { mutableStateOf(initialMode) }
        var game by remember { mutableStateOf(initialGame.ifEmpty { "FC 24" }) }
        var voucherCode by remember { mutableStateOf("") }
        var allowOvertime by remember { mutableStateOf(true) }
        var isLoserPay by remember { mutableStateOf(false) }
        var playerA by remember { mutableStateOf(initialPlayerName) }
        var playerB by remember { mutableStateOf("") }

        val selectedGameObj = gamesList.find { it.optString("game_name", "") == game }
        val isLoserPayEnabledForGame = selectedGameObj?.optBoolean("loser_pay_available", false) ?: false

        LaunchedEffect(game) {
            val currentObj = gamesList.find { it.optString("game_name", "") == game }
            if (currentObj == null || !currentObj.optBoolean("loser_pay_available", false)) {
                isLoserPay = false
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF161E2F),
            title = { Text("Start Session: ${device.optString("name")}", color = Color.White) },
            text = {
                Column {
                    Text("Select Mode", color = Color.Gray, fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        supportedModes.forEach { m ->
                            Button(
                                onClick = { mode = m },
                                colors = ButtonDefaults.buttonColors(containerColor = if (mode == m) Color(0xFF00E676) else Color(0xFF0B0F19)),
                                modifier = Modifier.weight(1f).padding(4.dp)
                            ) {
                                Text(m, color = if (mode == m) Color.Black else Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Quick Game Selection", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isLoadingGames) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF00E676),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else if (gamesList.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(gamesList) { g ->
                                val gName = g.optString("game_name", "")
                                val isSelected = game == gName
                                Button(
                                    onClick = { game = gName },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) Color(0xFF00E676) else Color(0xFF0B0F19),
                                        contentColor = if (isSelected) Color.Black else Color.White
                                    ),
                                    modifier = Modifier.border(1.dp, Color(0xFF00E676).copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(gName, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        Text("No games configured. Type game name below.", color = Color.Gray, fontSize = 10.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = game,
                        onValueChange = { game = it },
                        label = { Text("Game Name", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E676),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    
                    if (mode == "PREPAID") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Prepaid voucher must be generated from Admin Dashboard", color = Color.LightGray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = voucherCode,
                            onValueChange = { voucherCode = it },
                            label = { Text("Voucher Code", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E676),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowOvertime, onCheckedChange = { allowOvertime = it })
                        Text("Allow Overtime", color = Color.White)
                    }

                    // Loser Pay Toggle
                    if (isLoserPayEnabledForGame) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isLoserPay) Color(0xFF78350F).copy(alpha = 0.2f)
                                    else Color(0xFF0B0F19),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isLoserPay) Color(0xFFFBBF24)
                                    else Color(0xFF00E676).copy(alpha = 0.1f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { isLoserPay = !isLoserPay }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("⚔  Loser Pay Mode", color = if (isLoserPay) Color(0xFFFBBF24) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("The loser of each round pays the bill", color = Color.Gray, fontSize = 10.sp)
                            }
                            Switch(
                                checked = isLoserPay,
                                onCheckedChange = { isLoserPay = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFFFBBF24),
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color(0xFF1F2937)
                                )
                            )
                        }

                        if (isLoserPay) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = playerA,
                                onValueChange = { playerA = it },
                                label = { Text("Player A Name", color = Color.Gray) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFBBF24),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = playerB,
                                onValueChange = { playerB = it },
                                label = { Text("Player B Name", color = Color.Gray) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFBBF24),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onConfirm(mode, game, 0, voucherCode, allowOvertime, isLoserPay, playerA, playerB) }) {
                    Text(if (isLoserPay) "START ⚔" else "Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
            }
        )
    }

    @Composable
    fun QRScannerDialog(onDismiss: () -> Unit, onScan: (String) -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color.Black,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            text = {
                Box(modifier = Modifier.fillMaxSize()) {
                    ScannerView(onScan)
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                        Text("X", color = Color.White, fontSize = 24.sp)
                    }
                }
            },
            confirmButton = {}
        )
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    @Composable
    fun ScannerView(onScan: (String) -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val previewBuilder = Preview.Builder()
                    Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    val preview = previewBuilder.build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    Camera2Interop.Extender(imageAnalysisBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    val imageAnalysis = imageAnalysisBuilder.build()

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        processImageProxy(imageProxy, onScan)
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                        previewView.post {
                            if (previewView.width > 0 && previewView.height > 0) {
                                val factory = SurfaceOrientedMeteringPointFactory(
                                    previewView.width.toFloat(),
                                    previewView.height.toFloat()
                                )
                                val focusPoint = factory.createPoint(
                                    previewView.width / 2f,
                                    previewView.height / 2f
                                )
                                val action = FocusMeteringAction.Builder(focusPoint, FocusMeteringAction.FLAG_AF)
                                    .disableAutoCancel()
                                    .build()
                                camera.cameraControl.startFocusAndMetering(action)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("QRScanner", "Unable to start camera scanner", e)
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    @ExperimentalGetImage
    private fun processImageProxy(imageProxy: androidx.camera.core.ImageProxy, onScan: (String) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { onScan(it) }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    @Composable
    fun VouchersTab(server: String, headers: () -> Headers, gamesList: List<JSONObject>, canSendSms: Boolean = false) {
        val context = LocalContext.current
        val vouchersList = remember { mutableStateListOf<JSONObject>() }
        var totalVouchers by remember { mutableStateOf(0) }
        var currentPage by remember { mutableStateOf(1) }
        val limit = 10
        
        var isGenerating by remember { mutableStateOf(false) }
        var durationMinutes by remember { mutableStateOf("60") }
        var amountKes by remember { mutableStateOf("100") }
        var selectedGameName by remember { mutableStateOf("") }
        var customCode by remember { mutableStateOf("") }
        var allowOvertime by remember { mutableStateOf(false) }

        var showCreatedCode by remember { mutableStateOf("") }
        var smsSendingVoucherId by remember { mutableStateOf("") }
        var smsSendPhone by remember { mutableStateOf("") }
        var showSmsDialog by remember { mutableStateOf(false) }

        val fetchVouchers: () -> Unit = {
            val url = "https://$server/api/v1/kiosk/vouchers?page=$currentPage&limit=$limit"
            val request = Request.Builder().url(url).headers(headers()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = response.body?.string() ?: return
                        if (response.isSuccessful) {
                            try {
                                val obj = JSONObject(body)
                                val arr = obj.getJSONArray("vouchers")
                                val count = obj.optInt("total_count", 0)
                                runOnUiThread {
                                    vouchersList.clear()
                                    for (i in 0 until arr.length()) {
                                        vouchersList.add(arr.getJSONObject(i))
                                    }
                                    totalVouchers = count
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            })
        }

        LaunchedEffect(currentPage) {
            fetchVouchers()
        }

        val createVoucher = {
            val url = "https://$server/api/v1/kiosk/vouchers"
            val payload = JSONObject().apply {
                put("duration_minutes", durationMinutes.toIntOrNull() ?: 60)
                put("amount_kes", amountKes.toDoubleOrNull() ?: 100.0)
                if (selectedGameName.isNotEmpty()) put("game_name", selectedGameName)
                put("allow_overtime", allowOvertime)
                if (customCode.isNotEmpty()) {
                    put("code", customCode)
                }
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).headers(headers()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { Toast.makeText(context, "Network error: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bodyStr = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            try {
                                val voucher = JSONObject(bodyStr)
                                val code = voucher.getString("code")
                                runOnUiThread {
                                    showCreatedCode = code
                                    isGenerating = false
                                    customCode = ""
                                    fetchVouchers()
                                    Toast.makeText(context, "Voucher created!", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {}
                        } else {
                            val err = try { JSONObject(bodyStr).getString("error") } catch (e: Exception) { "Creation failed" }
                            runOnUiThread { Toast.makeText(context, err, Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            })
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Prepaid Vouchers", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { isGenerating = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                    ) {
                        Text("Generate Voucher", color = Color.Black)
                    }
                }
            }

            if (vouchersList.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("No vouchers found. Click Generate to create one.", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                items(vouchersList) { voucher ->
                    val code = voucher.optString("code")
                    val dur = voucher.optInt("duration_minutes")
                    val amt = voucher.optDouble("amount_kes")
                    val game = voucher.optString("game_name", "Any Game")
                    val used = voucher.optBoolean("is_used", false)
                    
                    val statusColor = if (used) Color.Red else Color(0xFF00E676)
                    val statusText = if (used) "USED" else "UNUSED"

                    val voucherId = voucher.optString("id")
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = code,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Voucher Code", code)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                                        .border(1.dp, statusColor, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text("$dur mins • KES ${amt.toInt()} • $game", color = Color.LightGray, fontSize = 12.sp)
                            if (voucher.optBoolean("allow_overtime", false)) {
                                Text("Overtime allowed", color = Color(0xFFFFB74D), fontSize = 11.sp)
                            }
                            if (!used && canSendSms) {
                                TextButton(
                                    onClick = {
                                        smsSendingVoucherId = voucherId
                                        smsSendPhone = ""
                                        showSmsDialog = true
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text("Send via SMS", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    val maxPages = Math.max(1, (totalVouchers + limit - 1) / limit)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { if (currentPage > 1) currentPage-- },
                            enabled = currentPage > 1,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2638))
                        ) {
                            Text("Prev", color = Color.White)
                        }
                        Text("Page $currentPage of $maxPages ($totalVouchers total)", color = Color.Gray, fontSize = 13.sp)
                        Button(
                            onClick = { if (currentPage < maxPages) currentPage++ },
                            enabled = currentPage < maxPages,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2638))
                        ) {
                            Text("Next", color = Color.White)
                        }
                    }
                }
            }
        }

        if (isGenerating) {
            AlertDialog(
                onDismissRequest = { isGenerating = false },
                containerColor = Color(0xFF161E2F),
                title = { Text("Generate Voucher", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = durationMinutes,
                            onValueChange = { durationMinutes = it },
                            label = { Text("Duration (Minutes)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E676),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        OutlinedTextField(
                            value = amountKes,
                            onValueChange = { amountKes = it },
                            label = { Text("Amount (KES)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E676),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        
                        Text("Game Bound", color = Color.White, fontSize = 12.sp)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(gamesList) { g ->
                                val name = g.optString("game_name", "")
                                val isSelected = selectedGameName == name
                                Button(
                                    onClick = { selectedGameName = name },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) Color(0xFF00E676) else Color(0xFF0B0F19),
                                        contentColor = if (isSelected) Color.Black else Color.White
                                    )
                                ) {
                                    Text(name, fontSize = 11.sp)
                                }
                            }
                        }
                        
                        OutlinedTextField(
                            value = selectedGameName,
                            onValueChange = { selectedGameName = it },
                            label = { Text("Selected Game Bound") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E676),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        
                        OutlinedTextField(
                            value = customCode,
                            onValueChange = { customCode = it },
                            label = { Text("Custom Code (Optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E676),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Allow Overtime", color = Color.White, fontSize = 14.sp)
                                Text("Player can continue beyond voucher time", color = Color.Gray, fontSize = 11.sp)
                            }
                            Switch(
                                checked = allowOvertime,
                                onCheckedChange = { allowOvertime = it },
                                colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E676), checkedTrackColor = Color(0xFF00E676).copy(alpha = 0.4f))
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { createVoucher() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                    ) {
                        Text("Generate", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isGenerating = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }

        if (showSmsDialog) {
            AlertDialog(
                onDismissRequest = { showSmsDialog = false },
                containerColor = Color(0xFF161E2F),
                title = { Text("Send Voucher via SMS", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Enter the recipient's phone number (with country code, e.g. +254712345678)", color = Color.Gray, fontSize = 13.sp)
                        OutlinedTextField(
                            value = smsSendPhone,
                            onValueChange = { smsSendPhone = it },
                            label = { Text("Phone Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4FC3F7),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (smsSendPhone.isNotBlank() && smsSendingVoucherId.isNotEmpty()) {
                                val url = "https://$server/api/v1/kiosk/vouchers/$smsSendingVoucherId/send-sms"
                                val payload = JSONObject().apply { put("phone_number", smsSendPhone.trim()) }
                                val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
                                val req = Request.Builder().url(url).post(body).headers(headers()).build()
                                client.newCall(req).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {
                                        runOnUiThread { Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                                    }
                                    override fun onResponse(call: Call, response: Response) {
                                        runOnUiThread {
                                            if (response.isSuccessful) {
                                                Toast.makeText(context, "SMS sent!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "SMS failed (${response.code})", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                })
                                showSmsDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7))
                    ) {
                        Text("Send", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSmsDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }

        if (showCreatedCode.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showCreatedCode = "" },
                containerColor = Color(0xFF0B0F19),
                title = { Text("Voucher Generated Successfully", color = Color(0xFF00E676), textAlign = TextAlign.Center) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = showCreatedCode,
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF161E2F), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(8.dp))
                                .padding(vertical = 12.dp)
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Voucher Code", showCreatedCode)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                        )
                        Text(
                            text = "Linked to Game: $selectedGameName\nDuration: $durationMinutes minutes\nAmount: KES $amountKes",
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Text("Tap code to copy", color = Color.Gray, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showCreatedCode = "" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                    ) {
                        Text("Done", color = Color.Black)
                    }
                }
            )
        }
    }

    @Composable
    fun DeviceHistoryDialog(
        device: JSONObject,
        server: String,
        headers: () -> Headers,
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        val sessionsList = remember { mutableStateListOf<JSONObject>() }
        var totalSessionsCount by remember { mutableStateOf(0) }
        var currentPage by remember { mutableStateOf(1) }
        val limit = 10
        
        var dateFilter by remember { mutableStateOf("TODAY") } // TODAY, YESTERDAY, WEEK, ALL, CUSTOM
        var customStartDate by remember { mutableStateOf("") }
        var customEndDate by remember { mutableStateOf("") }
        
        val fetchHistory = {
            var url = "https://$server/api/v1/kiosk/devices/${device.optString("id")}/sessions?page=$currentPage&limit=$limit"
            
            // Format dates
            val cal = java.util.Calendar.getInstance()
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            var sDate = ""
            var eDate = ""
            
            when (dateFilter) {
                "TODAY" -> {
                    sDate = sdf.format(cal.time)
                    eDate = sdf.format(cal.time)
                }
                "YESTERDAY" -> {
                    cal.add(java.util.Calendar.DATE, -1)
                    sDate = sdf.format(cal.time)
                    eDate = sdf.format(cal.time)
                }
                "WEEK" -> {
                    val end = sdf.format(cal.time)
                    cal.add(java.util.Calendar.DATE, -7)
                    val start = sdf.format(cal.time)
                    sDate = start
                    eDate = end
                }
                "CUSTOM" -> {
                    sDate = customStartDate
                    eDate = customEndDate
                }
                "ALL" -> {
                    sDate = "2026-01-01" // All time
                    eDate = sdf.format(cal.time)
                }
            }
            
            if (sDate.isNotEmpty()) {
                url += "&start_date=$sDate"
            }
            if (eDate.isNotEmpty()) {
                url += "&end_date=$eDate"
            }
            
            val request = Request.Builder().url(url).headers(headers()).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = response.body?.string() ?: return
                        if (response.isSuccessful) {
                            try {
                                val obj = JSONObject(body)
                                val logs = obj.getJSONArray("sessions")
                                val count = obj.optInt("total", 0)
                                runOnUiThread {
                                    sessionsList.clear()
                                    for (i in 0 until logs.length()) {
                                        sessionsList.add(logs.getJSONObject(i))
                                    }
                                    totalSessionsCount = count
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            })
        }

        LaunchedEffect(currentPage, dateFilter, customStartDate, customEndDate) {
            fetchHistory()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF0F172A),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            title = {
                Text(
                    text = "History: ${device.optString("name")}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.85f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Filters row
                    Text("Date Filter", color = Color.Gray, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("TODAY", "YESTERDAY", "WEEK", "ALL", "CUSTOM").forEach { f ->
                            val isSelected = dateFilter == f
                            Button(
                                onClick = {
                                    dateFilter = f
                                    currentPage = 1
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) Color(0xFF00E676) else Color(0xFF161E2F)
                                ),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(f, color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                    
                    if (dateFilter == "CUSTOM") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customStartDate,
                                onValueChange = { customStartDate = it },
                                label = { Text("Start Date (YYYY-MM-DD)", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E676),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            OutlinedTextField(
                                value = customEndDate,
                                onValueChange = { customEndDate = it },
                                label = { Text("End Date (YYYY-MM-DD)", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E676),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }
                    }
                    
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    
                    if (sessionsList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No sessions found in this date range", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sessionsList) { session ->
                                val game = session.optString("game_name", "Game")
                                val mode = session.optString("billing_mode", "POSTPAID")
                                val dur = session.optInt("duration_minutes", 0)
                                val amt = session.optDouble("amount_charged_kes", 0.0)
                                val startTime = session.optString("start_time", "")
                                
                                val dateStr = try {
                                    if (startTime.contains("T")) {
                                        val parts = startTime.split("T")
                                        parts[0] + " " + parts[1].substringBefore(".")
                                    } else {
                                        startTime
                                    }
                                } catch (e: Exception) {
                                    startTime
                                }

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(game, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text("$dur mins • $mode", color = Color.LightGray, fontSize = 12.sp)
                                            Text(dateStr, color = Color.Gray, fontSize = 10.sp)
                                        }
                                        Text(
                                            text = "KES ${amt.toInt()}",
                                            color = Color(0xFF00E676),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Pagination controls
                        val maxPages = Math.max(1, (totalSessionsCount + limit - 1) / limit)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { if (currentPage > 1) currentPage-- },
                                enabled = currentPage > 1,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E2F))
                            ) {
                                Text("Prev", color = Color.White)
                            }
                            Text("Page $currentPage of $maxPages ($totalSessionsCount total)", color = Color.Gray, fontSize = 12.sp)
                            Button(
                                onClick = { if (currentPage < maxPages) currentPage++ },
                                enabled = currentPage < maxPages,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E2F))
                            ) {
                                Text("Next", color = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("Close", color = Color.Black)
                }
            }
        )
    }

    @Composable
    fun DeviceQueueDialog(
        device: JSONObject,
        queueTickets: List<JSONObject>,
        isLoadingQueue: Boolean,
        gamesList: List<JSONObject>,
        onDismiss: () -> Unit,
        onAddPlayer: (String, String, String, Boolean, Int?) -> Unit, // gameName, playerName, playerPhone, giveWifi, wifiMinutes
        onCallNext: (String) -> Unit, // ticketId
        onServe: (JSONObject) -> Unit, // ticket JSON
        onSkip: (String) -> Unit, // ticketId
        onCancel: (String) -> Unit, // ticketId
        onRelinkClick: () -> Unit,
        onRenameDevice: (String) -> Unit
    ) {
        var newPlayerName by remember { mutableStateOf("") }
        var newPlayerPhone by remember { mutableStateOf("") }
        var giveWifi by remember { mutableStateOf(true) }
        var wifiMinutes by remember { mutableStateOf("") }   // blank = estimated waiting time
        var selectedGame by remember { mutableStateOf(if (gamesList.isNotEmpty()) gamesList[0].optString("game_name", "") else "") }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF0F172A),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Queue: ${device.optString("name")}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = device.optString("console_type", "PS5"),
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.85f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 0. Configure TV Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Configure TV",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            var deviceNameInput by remember { mutableStateOf(device.optString("name", "TV Screen")) }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = deviceNameInput,
                                    onValueChange = { deviceNameInput = it },
                                    label = { Text("TV Name", color = Color.Gray, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00E676),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                                Button(
                                    onClick = { onRenameDevice(deviceNameInput.trim()) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                    modifier = Modifier.height(56.dp)
                                ) {
                                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Button(
                                onClick = onRelinkClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF818CF8)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Relink Screen (Scan QR)", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    // 1. Add Player Form
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Add Player to Waitlist",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            OutlinedTextField(
                                value = newPlayerName,
                                onValueChange = { newPlayerName = it },
                                label = { Text("Player Name", color = Color.Gray, fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E676),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            OutlinedTextField(
                                value = newPlayerPhone,
                                onValueChange = { newPlayerPhone = it },
                                label = { Text("Phone (ticket & WiFi code by SMS)", color = Color.Gray, fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E676),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Text("Select Game", color = Color.Gray, fontSize = 11.sp)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(gamesList) { g ->
                                    val gName = g.optString("game_name", "")
                                    val isSelected = selectedGame == gName
                                    Button(
                                        onClick = { selectedGame = gName },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) Color(0xFF00E676) else Color(0xFF0B0F19),
                                            contentColor = if (isSelected) Color.Black else Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(gName, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Switch(checked = giveWifi, onCheckedChange = { giveWifi = it })
                                Spacer(Modifier.width(8.dp))
                                Text("Free WiFi while waiting", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = wifiMinutes,
                                    onValueChange = { v -> wifiMinutes = v.filter { it.isDigit() }.take(3) },
                                    enabled = giveWifi,
                                    placeholder = { Text("auto", color = Color.Gray, fontSize = 11.sp) },
                                    label = { Text("min", color = Color.Gray, fontSize = 10.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(84.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                            }
                            if (giveWifi && newPlayerPhone.isBlank()) {
                                Text("Add a phone number to SMS the WiFi code.", color = Color(0xFFFFB74D), fontSize = 10.sp)
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    if (newPlayerName.trim().isNotEmpty() && selectedGame.isNotEmpty()) {
                                        onAddPlayer(selectedGame, newPlayerName.trim(), newPlayerPhone.trim(), giveWifi, wifiMinutes.toIntOrNull())
                                        newPlayerName = ""
                                        newPlayerPhone = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = newPlayerName.trim().isNotEmpty() && selectedGame.isNotEmpty()
                            ) {
                                Text("Enqueue Player", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    // 2. Queue Tickets List
                    Text(
                        text = "Current Queue",
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (isLoadingQueue) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF00E676))
                        }
                    } else if (queueTickets.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No players in queue", color = Color.Gray, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(queueTickets) { ticket ->
                                val status = ticket.optString("status", "WAITING")
                                val isWaiting = status == "WAITING"
                                val isCalled = status == "CALLED"
                                
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            if (isCalled) Color(0xFF38BDF8) else Color(0xFF475569),
                                                            RoundedCornerShape(4.dp)
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = String.format(Locale.US, "#%03d", ticket.optInt("ticket_number")),
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Text(
                                                    text = ticket.optString("player_name", "Unknown"),
                                                    color = Color.White,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp
                                                )
                                            }

                                            Text(
                                                text = status,
                                                color = if (isCalled) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Text(
                                            text = "Game: ${ticket.optString("game_name")}",
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (isWaiting) {
                                                Button(
                                                    onClick = { onCallNext(ticket.optString("id")) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                                                    modifier = Modifier.weight(1f).height(32.dp),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) {
                                                    Text("CALL NEXT", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            if (isWaiting || isCalled) {
                                                Button(
                                                    onClick = { onServe(ticket) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                                    modifier = Modifier.weight(1f).height(32.dp),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) {
                                                    Text("SERVE", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Button(
                                                    onClick = { onSkip(ticket.optString("id")) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                                                    modifier = Modifier.weight(1f).height(32.dp),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) {
                                                    Text("SKIP", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Button(
                                                    onClick = { onCancel(ticket.optString("id")) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                                    modifier = Modifier.weight(1f).height(32.dp),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) {
                                                    Text("CANCEL", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E2F))
                ) {
                    Text("Close", color = Color.White)
                }
            }
        )
    }

    @Composable
    fun OrdersTab(server: String, headers: () -> Headers) {
        val context = LocalContext.current
        var selectedLogForShare by remember { mutableStateOf<JSONObject?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Orders & Session Receipts", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Session #LOG-81203", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("KES 200", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text("Game: FIFA 25 • PS5 Station 1 • 60 Mins", color = Color.LightGray, fontSize = 12.sp)
                    Button(
                        onClick = {
                            val dummyLog = JSONObject().apply {
                                put("id", "81203")
                                put("game_name", "FIFA 25")
                                put("amount_charged_kes", 200.0)
                                put("duration_minutes", 60)
                            }
                            selectedLogForShare = dummyLog
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Share Receipt", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (selectedLogForShare != null) {
                ReceiptShareDialog(
                    sessionLog = selectedLogForShare!!,
                    server = server,
                    headers = headers,
                    onDismiss = { selectedLogForShare = null }
                )
            }
        }
    }

    @Composable
    fun ReceiptShareDialog(
        sessionLog: JSONObject,
        server: String,
        headers: () -> Headers,
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        val id = sessionLog.optString("id")
        val game = sessionLog.optString("game_name", "Arcade Game")
        val amt = sessionLog.optDouble("amount_charged_kes", 0.0)
        val publicLink = "https://$server/receipt/session/$id"
        var customerPhone by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF0F172A),
            title = { Text("Receipt Sharing options", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Game: $game • Amount: KES ${amt.toInt()}", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Public Link: $publicLink", color = Color.LightGray, fontSize = 11.sp)

                    OutlinedTextField(
                        value = customerPhone,
                        onValueChange = { customerPhone = it },
                        label = { Text("Customer Phone Number (+254...)", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00E676), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val msgText = "Receipt: $game - KES ${amt.toInt()}. View receipt: $publicLink"
                                val payload = JSONObject().apply {
                                    put("recipient_phone", customerPhone)
                                    put("message_text", msgText)
                                }
                                val client = OkHttpClient()
                                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                                val req = Request.Builder()
                                    .url("https://$server/api/v1/kiosk/sms/send")
                                    .post(body)
                                    .headers(headers())
                                    .build()
                                client.newCall(req).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {}
                                    override fun onResponse(call: Call, response: Response) {
                                        runOnUiThread {
                                            Toast.makeText(context, "SMS Receipt Sent!", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    }
                                })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Send SMS", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val msgText = "Receipt: $game - KES ${amt.toInt()}. View receipt: $publicLink"
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(msgText)}")
                                }
                                context.startActivity(intent)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("WhatsApp", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E2F))) {
                    Text("Close", color = Color.White)
                }
            }
        )
    }

    @Composable
    fun WifiBillingTab(server: String, headers: () -> Headers) {
        val context = LocalContext.current
        var wifiSubTab by remember { mutableStateOf("OVERVIEW") } // OVERVIEW, PACKAGES, SESSIONS, VOUCHERS
        var showAddPackageDialog by remember { mutableStateOf(false) }
        var showVoucherModal by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header & Section Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("WiFi Billing & MikroTik Hotspot", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Powered by CVPAP Hotspot Bridge & M-Pesa", color = Color.Gray, fontSize = 12.sp)
                }
                Surface(
                    color = Color(0xFF00E676).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Gateway Online", color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }

            // Sub-navigation bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("OVERVIEW", "PACKAGES", "SESSIONS", "VOUCHERS").forEach { mode ->
                    val isSel = wifiSubTab == mode
                    Button(
                        onClick = { wifiSubTab = mode },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSel) Color(0xFF00E676) else Color(0xFF161E2F)
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(mode, color = if (isSel) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            when (wifiSubTab) {
                "OVERVIEW" -> {
                    // KPI Grid
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), modifier = Modifier.weight(1f)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Sales Today", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("KES 3,450", color = Color(0xFF00E676), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), modifier = Modifier.weight(1f)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Active Users", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("18 Connected", color = Color(0xFF60A5FA), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("MikroTik Gateway Status", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("• Router Name: Main-Arcade-Hotspot-01 (192.168.88.1)", color = Color.LightGray, fontSize = 12.sp)
                            Text("• M-Pesa STK Push: Ready (Paybill #408123)", color = Color.LightGray, fontSize = 12.sp)
                            Text("• Active WiFi Packages: 4 Active Tiers", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                }

                "PACKAGES" -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Hotspot Rate Packages", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Button(onClick = { showAddPackageDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                            Text("+ Add Package", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            listOf(
                                Triple("1 Hour Unlimited", "1 Hour • 5 Mbps Speed", "KES 20"),
                                Triple("3 Hours Pass", "3 Hours • 8 Mbps Speed", "KES 50"),
                                Triple("24 Hours Day Pass", "24 Hours • 10 Mbps Speed", "KES 100"),
                                Triple("7 Days Weekly Pass", "7 Days • 15 Mbps Speed", "KES 450")
                            )
                        ) { pkg ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(pkg.first, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(pkg.second, color = Color.Gray, fontSize = 11.sp)
                                    }
                                    Text(pkg.third, color = Color(0xFF00E676), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }

                "SESSIONS" -> {
                    Text("Active Hotspot Sessions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            listOf(
                                Pair("A4:C3:F0:12:88:91", "IP: 192.168.88.102 • Uptime: 24m • 3 MB/s"),
                                Pair("D8:50:E6:99:41:00", "IP: 192.168.88.115 • Uptime: 1h 12m • 5 MB/s")
                            )
                        ) { session ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(session.first, color = Color(0xFF60A5FA), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(session.second, color = Color.Gray, fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = { Toast.makeText(context, "Session Disconnected", Toast.LENGTH_SHORT).show() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                                    ) {
                                        Text("Disconnect", color = Color.White, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                "VOUCHERS" -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Hotspot Voucher Codes", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Button(onClick = { showVoucherModal = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                            Text("+ Generate Vouchers", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            listOf(
                                Pair("VOUCHER-7819-A", "1 Hour Unlimited • Unused"),
                                Pair("VOUCHER-9120-B", "3 Hours Pass • Active")
                            )
                        ) { voucher ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161E2F)), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(voucher.first, color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(voucher.second, color = Color.Gray, fontSize = 11.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(
                                            onClick = {
                                                val prefs = context.getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                                                val printerIp = prefs.getString("printer_ip", "") ?: ""
                                                com.example.psbill.ui.screens.WifiThermalPrinter.printVoucher(
                                                    context = context,
                                                    printerIp = printerIp,
                                                    voucherCode = voucher.first,
                                                    planTitle = voucher.second.substringBefore("•").trim(),
                                                    price = "KES 20",
                                                    duration = "1 Hour",
                                                    onResult = { ok, msg ->
                                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("🖨️ Print", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode("Your WiFi Voucher code is: ${voucher.first}")}")
                                                }
                                                context.startActivity(intent)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("WhatsApp", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
            }

            if (showAddPackageDialog) {
                AlertDialog(
                    onDismissRequest = { showAddPackageDialog = false },
                    containerColor = Color(0xFF0F172A),
                    title = { Text("Create WiFi Hotspot Package", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = "",
                                onValueChange = {},
                                label = { Text("Package Name (e.g. 1 Hour Unlimited)", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00E676), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                            OutlinedTextField(
                                value = "",
                                onValueChange = {},
                                label = { Text("Price in KES", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00E676), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showAddPackageDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                            Text("Save Package", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            if (showVoucherModal) {
                AlertDialog(
                    onDismissRequest = { showVoucherModal = false },
                    containerColor = Color(0xFF0F172A),
                    title = { Text("Generate WiFi Voucher Batch", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Vouchers can be printed or sent directly to customers via SMS.", color = Color.Gray, fontSize = 12.sp)
                            OutlinedTextField(
                                value = "10",
                                onValueChange = {},
                                label = { Text("Number of Voucher Codes", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00E676), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showVoucherModal = false
                                Toast.makeText(context, "10 Vouchers Generated!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                        ) {
                            Text("Generate Batch", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
    }
}
