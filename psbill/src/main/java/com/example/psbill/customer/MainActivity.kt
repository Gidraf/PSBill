package com.example.psbill.customer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

// Obsidian Kinetic Color Tokens
object PSBillThemeColors {
    val Canvas = Color(0xFF0B1326)
    val Surface = Color(0xFF131B2E)
    val SurfaceAlt = Color(0xFF171F33)
    val Border = Color(0xFF334155)
    val PrimaryAccent = Color(0xFF8B5CF6)
    val SecondaryAccent = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)
    val Crimson = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
}
private const val INTERNAL_SERVER_URL = "https://api.ajiriwa.gidraf.dev"

private val stationPinPattern = Regex("""\b(\d{3})[-\s]?(\d{3})\b""")

private fun normalizePinCandidate(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val digits = value.filter { it.isDigit() }
    return if (digits.length == 6) digits else null
}

private fun extractPinFromUri(uri: Uri): String? {
    val queryKeys = listOf("pin", "pin_code", "station_pin", "pair_code", "code")
    for (key in queryKeys) {
        normalizePinCandidate(uri.getQueryParameter(key))?.let { return it }
    }
    for (segment in uri.pathSegments.asReversed()) {
        normalizePinCandidate(segment)?.let { return it }
        stationPinPattern.find(segment)?.value?.let { normalizePinCandidate(it) }?.let { return it }
    }
    return stationPinPattern.find(uri.toString())?.value?.let { normalizePinCandidate(it) }
}

private fun extractStationPinFromQrPayload(payload: String): String? {
    val raw = payload.trim()
    if (raw.isEmpty()) return null

    normalizePinCandidate(raw)?.let { return it }
    stationPinPattern.find(raw)?.value?.let { normalizePinCandidate(it) }?.let { return it }

    if (raw.startsWith("{") && raw.endsWith("}")) {
        runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
            val pinKeys = listOf("pin", "pin_code", "station_pin", "pair_code", "stationPin")
            for (key in pinKeys) {
                normalizePinCandidate(json.optString(key))?.let { return it }
            }

            val linkKeys = listOf("link", "url", "qr_link", "pair_url")
            for (key in linkKeys) {
                val link = json.optString(key).trim()
                if (link.isNotEmpty()) {
                    runCatching { Uri.parse(link) }.getOrNull()?.let { extractPinFromUri(it) }?.let { return it }
                    stationPinPattern.find(link)?.value?.let { normalizePinCandidate(it) }?.let { return it }
                }
            }
        }
    }

    runCatching { Uri.parse(raw) }
        .getOrNull()
        ?.takeIf { !it.scheme.isNullOrBlank() }
        ?.let { extractPinFromUri(it) }
        ?.let { return it }

    return null
}

private fun extractNetworkLinkFromQrPayload(payload: String): String? {
    val raw = payload.trim()
    if (raw.isEmpty()) return null
    if (URLUtil.isNetworkUrl(raw)) return raw

    if (raw.startsWith("{") && raw.endsWith("}")) {
        runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
            val linkKeys = listOf("link", "url", "qr_link", "pair_url", "activation_url")
            for (key in linkKeys) {
                val link = json.optString(key).trim()
                if (URLUtil.isNetworkUrl(link)) return link
            }
        }
    }

    return null
}

private fun extractPartnerIdFromToken(rawToken: String?): String? {
    if (rawToken.isNullOrBlank()) return null
    return runCatching {
        val parts = rawToken.split(".")
        if (parts.size < 2) return null
        val payloadBytes = android.util.Base64.decode(
            parts[1],
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
        payloadJson.optString("partner_id").trim().ifEmpty { null }
    }.getOrNull()
}

internal fun jsonArrayToObjectList(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    val items = mutableListOf<JSONObject>()
    for (i in 0 until array.length()) {
        when (val entry = array.opt(i)) {
            is JSONObject -> items.add(entry)
            is String -> items.add(JSONObject().put("code", entry))
        }
    }
    return items
}

private fun mergeVoucherSources(
    kioskVouchers: List<JSONObject>,
    wifiVoucherBatches: List<JSONObject>
): List<JSONObject> {
    val merged = mutableListOf<JSONObject>()
    val seenCodes = mutableSetOf<String>()

    fun appendVoucher(
        codeValue: String?,
        amountValue: String?,
        statusValue: String?,
        sourceValue: String,
        packageName: String? = null,
        batchId: String? = null
    ) {
        val normalizedCode = codeValue?.trim().orEmpty()
        if (normalizedCode.isBlank()) return
        val dedupeKey = normalizedCode.uppercase(Locale.US)
        if (!seenCodes.add(dedupeKey)) return

        val obj = JSONObject().apply {
            put("code", normalizedCode)
            put("source", sourceValue)
            put("status", statusValue?.ifBlank { null } ?: "ACTIVE")
            amountValue?.trim()?.takeIf { it.isNotBlank() }?.let { put("amount", it) }
            packageName?.trim()?.takeIf { it.isNotBlank() }?.let { put("package_name", it) }
            batchId?.trim()?.takeIf { it.isNotBlank() }?.let { put("batch_id", it) }
        }
        merged.add(obj)
    }

    kioskVouchers.forEach { voucher ->
        val code = voucher.optString("code")
            .ifBlank { voucher.optString("voucher_code") }
            .ifBlank { voucher.optString("pin") }
        val amount = voucher.optString("amount")
            .ifBlank { voucher.optString("amount_kes") }
            .ifBlank { voucher.optString("price_kes") }
        val status = if (voucher.optBoolean("is_used", false)) {
            "USED"
        } else {
            voucher.optString("status", "ACTIVE")
        }
        appendVoucher(code, amount, status, sourceValue = "kiosk")
    }

    wifiVoucherBatches.forEach { batch ->
        val batchStatus = batch.optString("status", "GENERATED")
        val packageName = batch.optString("package_name")
        val batchId = batch.optString("id")
        val codeArray = batch.optJSONArray("codes")
        for (i in 0 until (codeArray?.length() ?: 0)) {
            val codeEntry = codeArray?.opt(i)
            val codeValue = when (codeEntry) {
                is JSONObject -> codeEntry.optString("code")
                    .ifBlank { codeEntry.optString("voucher_code") }
                    .ifBlank { codeEntry.optString("pin") }
                is String -> codeEntry
                else -> null
            }
            appendVoucher(
                codeValue = codeValue,
                amountValue = null,
                statusValue = batchStatus,
                sourceValue = "wifi_batch",
                packageName = packageName,
                batchId = batchId,
            )
        }
    }

    return merged
}

private fun extractActivationPayloadFromQr(payload: String): JSONObject? {
    val raw = payload.trim()
    if (raw.isEmpty()) return null

    fun fromUri(uri: Uri): JSONObject? {
        val fingerprint = uri.getQueryParameter("fingerprint")?.trim().orEmpty()
        if (fingerprint.isBlank()) return null
        return JSONObject().apply {
            put("fingerprint", fingerprint)
            put("device_name", uri.getQueryParameter("tv_name")?.trim().orEmpty().ifBlank { "TV Screen" })
            put("console_type", uri.getQueryParameter("console_type")?.trim().orEmpty().ifBlank { "PS5" })
        }
    }

    if (raw.startsWith("{") && raw.endsWith("}")) {
        runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
            val fingerprint = json.optString("fingerprint").trim()
            if (fingerprint.isNotBlank()) {
                return JSONObject().apply {
                    put("fingerprint", fingerprint)
                    put("device_name", json.optString("tv_name").ifBlank { json.optString("device_name", "TV Screen") })
                    put("console_type", json.optString("console_type", "PS5"))
                }
            }
            val link = json.optString("link").ifBlank { json.optString("activation_url") }
            if (link.isNotBlank()) {
                runCatching { Uri.parse(link) }.getOrNull()?.let { fromUri(it) }?.let { return it }
            }
        }
    }

    runCatching { Uri.parse(raw) }.getOrNull()?.let { uri ->
        fromUri(uri)?.let { return it }
    }

    // Fallback: raw SHA-256-like fingerprint pasted directly
    if (raw.length >= 48 && raw.all { it.isLetterOrDigit() }) {
        return JSONObject().apply {
            put("fingerprint", raw)
            put("device_name", "TV Screen")
            put("console_type", "PS5")
        }
    }

    return null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PSBillAppMainView()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PSBillAppMainView() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("PSBillCustomerPrefs", Context.MODE_PRIVATE)
    }
    var selectedTab by remember { mutableStateOf("SESSIONS") } // SESSIONS, MATCHES, WIFI, SCREENS, REPORTS
    var token by remember { mutableStateOf(prefs.getString("auth_token", "") ?: "") }
    var emailInput by remember { mutableStateOf(prefs.getString("login_email", "") ?: "") }
    var passwordInput by remember { mutableStateOf("") }
    var showApiConfig by remember { mutableStateOf(false) }
    var authExpiryHandled by remember { mutableStateOf(false) }
    var showPasswordInSettings by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }

    // Live API Data State
    var nodes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var games by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var reportData by remember { mutableStateOf<JSONObject?>(null) }
    var vouchers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var wifiVoucherBatches by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pairedNode by remember { mutableStateOf<JSONObject?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var reportRange by remember { mutableStateOf("today") }

    val mergedVouchers = remember(vouchers, wifiVoucherBatches) {
        mergeVoucherSources(vouchers, wifiVoucherBatches)
    }

    val client = remember { OkHttpClient() }

    fun loginWithCredentials(onSuccess: () -> Unit = {}) {
        val email = emailInput.trim().lowercase(Locale.US)
        val password = passwordInput
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(context, "Enter email and password.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isLoggingIn) return

        isLoggingIn = true
        val payload = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val request = Request.Builder()
            .url("$INTERNAL_SERVER_URL/auth/login")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as? ComponentActivity)?.runOnUiThread {
                    isLoggingIn = false
                    Toast.makeText(context, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                (context as? ComponentActivity)?.runOnUiThread {
                    isLoggingIn = false
                    if (!response.isSuccessful) {
                        val err = runCatching {
                            val obj = JSONObject(body)
                            obj.optString("error").ifBlank { obj.optString("message") }
                        }.getOrElse { "" }
                        Toast.makeText(context, if (err.isNotBlank()) err else "Login failed (${response.code})", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    val tokenFromLogin = runCatching {
                        val obj = JSONObject(body)
                        obj.optString("refresh_token")
                            .ifBlank { obj.optString("access_token") }
                            .ifBlank { obj.optString("token") }
                    }.getOrElse { "" }

                    if (tokenFromLogin.isBlank()) {
                        Toast.makeText(context, "Login response missing token.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    token = tokenFromLogin
                    authExpiryHandled = false
                    showApiConfig = false
                    passwordInput = ""
                    prefs.edit()
                        .putString("login_email", email)
                        .putString("auth_token", tokenFromLogin)
                        .apply()
                    onSuccess()
                }
            }
        })
    }

    fun logoutExpiredToken() {
        if (authExpiryHandled) return
        authExpiryHandled = true
        token = ""
        passwordInput = ""
        nodes = emptyList()
        games = emptyList()
        vouchers = emptyList()
        wifiVoucherBatches = emptyList()
        reportData = null
        pairedNode = null
        reportRange = "today"
        selectedTab = "SESSIONS"
        showApiConfig = false
        prefs.edit().remove("auth_token").apply()
        Toast.makeText(context, "Session expired. Please sign in again.", Toast.LENGTH_LONG).show()
    }

    fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    // Helper API Fetchers
    fun fetchApiData(selectedReportRange: String = reportRange) {
        if (token.isBlank()) return
        isLoading = true

        val reqNodes = requestBuilder("$INTERNAL_SERVER_URL/api/v1/kiosk/portal/nodes").build()
        client.newCall(reqNodes).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isLoading = false
                (context as? ComponentActivity)?.runOnUiThread {
                    Toast.makeText(context, "Unable to load screens: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "[]"
                try {
                    if (response.code == 401 || response.code == 403) {
                        (context as? ComponentActivity)?.runOnUiThread { logoutExpiredToken() }
                        return
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val arr = if (body.trim().startsWith("[")) JSONArray(body) else JSONArray()
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    nodes = list
                    pairedNode = pairedNode?.let { existing ->
                        list.firstOrNull { it.optString("id") == existing.optString("id") }
                    }
                } catch (e: Exception) {
                    (context as? ComponentActivity)?.runOnUiThread {
                        Toast.makeText(context, "Failed loading screens. Check API token.", Toast.LENGTH_SHORT).show()
                    }
                }
                isLoading = false
            }
        })

        val reqGames = requestBuilder("$INTERNAL_SERVER_URL/api/v1/kiosk/games").build()
        client.newCall(reqGames).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "[]"
                try {
                    if (response.code == 401 || response.code == 403) {
                        (context as? ComponentActivity)?.runOnUiThread { logoutExpiredToken() }
                        return
                    }
                    if (!response.isSuccessful) return
                    val arr = JSONArray(body)
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    games = list
                } catch (e: Exception) {}
            }
        })

        val reportsUrl = "$INTERNAL_SERVER_URL/api/v1/kiosk/reports/daily?range=${Uri.encode(selectedReportRange)}"
        val reqReports = requestBuilder(reportsUrl).build()
        client.newCall(reqReports).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                try {
                    if (response.code == 401 || response.code == 403) {
                        (context as? ComponentActivity)?.runOnUiThread { logoutExpiredToken() }
                        return
                    }
                    if (response.isSuccessful) {
                        reportData = JSONObject(body)
                    }
                } catch (e: Exception) {}
            }
        })

        val reqVouchers = requestBuilder("$INTERNAL_SERVER_URL/api/v1/kiosk/vouchers").build()
        client.newCall(reqVouchers).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                try {
                    if (response.code == 401 || response.code == 403) {
                        (context as? ComponentActivity)?.runOnUiThread { logoutExpiredToken() }
                        return
                    }
                    if (!response.isSuccessful) return
                    val obj = JSONObject(body)
                    vouchers = jsonArrayToObjectList(obj.optJSONArray("vouchers"))
                } catch (e: Exception) {}
            }
        })

        val partnerId = extractPartnerIdFromToken(token)
        if (partnerId.isNullOrBlank()) {
            wifiVoucherBatches = emptyList()
            return
        }

        val reqWifiVoucherBatches = requestBuilder(
            "$INTERNAL_SERVER_URL/api/v1/wifi/${Uri.encode(partnerId)}/voucher-batches"
        ).build()
        client.newCall(reqWifiVoucherBatches).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                try {
                    if (response.code == 401 || response.code == 403) {
                        (context as? ComponentActivity)?.runOnUiThread { logoutExpiredToken() }
                        return
                    }
                    if (!response.isSuccessful) return

                    val root = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                    val dataArray = root.optJSONArray("data")
                        ?: if (body.trim().startsWith("[")) JSONArray(body) else JSONArray()
                    wifiVoucherBatches = jsonArrayToObjectList(dataArray)
                } catch (e: Exception) {}
            }
        })
    }

    // Pair station using 6-digit PIN Code via live API call
    fun pairStationWithPin(pinCode: String) {
        val cleanPin = pinCode.replace("-", "").replace(" ", "").trim()
        if (cleanPin.length < 6) {
            Toast.makeText(context, "Please enter a valid 6-digit station PIN (e.g. 842-195)", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = JSONObject().apply {
            put("pin_code", cleanPin)
            put("device_id", "mobile_app_${System.currentTimeMillis()}")
        }
        val request = requestBuilder("$INTERNAL_SERVER_URL/api/v1/kiosk/pair")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as? ComponentActivity)?.runOnUiThread {
                    Toast.makeText(context, "Unable to link with code right now. Use QR scan.", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                (context as? ComponentActivity)?.runOnUiThread {
                    if (response.code == 401 || response.code == 403) {
                        logoutExpiredToken()
                        return@runOnUiThread
                    }
                    if (!response.isSuccessful) {
                        val err = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                        Toast.makeText(context, if (err.isNotBlank()) err else "Link code not accepted. Scan TV QR instead.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    val linkedNode = runCatching {
                        val obj = JSONObject(body)
                        obj.optJSONObject("device")
                            ?: obj.optJSONObject("node")
                            ?: obj
                    }.getOrNull()
                    if (linkedNode != null && linkedNode.length() > 0) {
                        pairedNode = linkedNode
                    }
                    fetchApiData()
                    Toast.makeText(context, "Screen linked successfully.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    fun activateByQrPayload(qrValue: String): Boolean {
        val activationPayload = extractActivationPayloadFromQr(qrValue) ?: return false
        if (token.isBlank()) {
            Toast.makeText(context, "Please login first to link this TV.", Toast.LENGTH_SHORT).show()
            return true
        }

        val request = requestBuilder("$INTERNAL_SERVER_URL/api/devices/activate-by-qr")
            .post(activationPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as? ComponentActivity)?.runOnUiThread {
                    Toast.makeText(context, "TV linking failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                (context as? ComponentActivity)?.runOnUiThread {
                    if (response.code == 401 || response.code == 403) {
                        logoutExpiredToken()
                        return@runOnUiThread
                    }
                    if (!response.isSuccessful) {
                        val err = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                        Toast.makeText(context, if (err.isNotBlank()) err else "Unable to link TV from QR.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val obj = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                    val deviceObj = obj.optJSONObject("device")
                    if (deviceObj != null) {
                        pairedNode = deviceObj
                    }
                    fetchApiData()
                    Toast.makeText(context, obj.optString("message", "TV linked successfully."), Toast.LENGTH_SHORT).show()
                }
            }
        })
        return true
    }

    LaunchedEffect(token, reportRange) {
        if (token.isBlank()) return@LaunchedEffect
        fetchApiData(reportRange)
        while (token.isNotBlank()) {
            delay(25_000)
            fetchApiData(reportRange)
        }
    }

    if (token.isBlank()) {
        PSBillLoginScreen(
            email = emailInput,
            onEmailChange = { emailInput = it },
            password = passwordInput,
            onPasswordChange = { passwordInput = it },
            isLoading = isLoggingIn,
            onLogin = { loginWithCredentials { fetchApiData() } }
        )
        return
    }

    Scaffold(
        containerColor = PSBillThemeColors.Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(PSBillThemeColors.PrimaryAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text("PSBill Enterprise", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Arcade Telemetry & Gaming Hub", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PSBillThemeColors.Surface),
                actions = {
                    IconButton(onClick = { showApiConfig = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "API settings", tint = PSBillThemeColors.PrimaryAccent)
                    }
                    IconButton(onClick = { fetchApiData() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh API Data", tint = PSBillThemeColors.PrimaryAccent)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = PSBillThemeColors.Surface) {
                NavigationBarItem(
                    selected = selectedTab == "SESSIONS",
                    onClick = { selectedTab = "SESSIONS" },
                    icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                    label = { Text("Sessions", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = PSBillThemeColors.PrimaryAccent, indicatorColor = PSBillThemeColors.SurfaceAlt)
                )
                // Compiled-in tabs (modules.properties → CompiledModules)
                CompiledModules.features.sortedBy { it.order }.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab.key,
                        onClick = { selectedTab = tab.key },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = PSBillThemeColors.PrimaryAccent, indicatorColor = PSBillThemeColors.SurfaceAlt)
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(Modifier.fillMaxWidth().widthIn(max = 1120.dp)) {
            when (selectedTab) {
                "SESSIONS" -> PSBillSessionsView(
                    nodes,
                    pairedNode,
                    isLoading,
                    onPairPin = { pin -> pairStationWithPin(pin) },
                    onActivateByQr = { qr -> activateByQrPayload(qr) },
                    onRefresh = { fetchApiData() },
                    onGoHome = { selectedTab = "SESSIONS" }
                )
                else -> CompiledModules.features.firstOrNull { it.key == selectedTab }?.Content(
                    PsbillTabContext(
                        nodes = nodes,
                        games = games,
                        isLoading = isLoading,
                        vouchers = mergedVouchers,
                        reportData = reportData,
                        reportRange = reportRange,
                        onRangeChange = { reportRange = it },
                        onRefresh = { fetchApiData() },
                        onRefreshReports = { fetchApiData(reportRange) },
                        serverHost = INTERNAL_SERVER_URL,
                        token = token,
                        client = client,
                        onSelectScreen = { pairedNode = it },
                        onTokenExpired = { logoutExpiredToken() },
                    )
                )
            }
            }
        }
    }

    if (showApiConfig) {
        val brightInputColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = PSBillThemeColors.TextSecondary,
            unfocusedLabelColor = PSBillThemeColors.TextSecondary,
            cursorColor = Color.White,
            focusedBorderColor = PSBillThemeColors.PrimaryAccent,
            unfocusedBorderColor = PSBillThemeColors.Border
        )
        AlertDialog(
            onDismissRequest = { showApiConfig = false },
            title = { Text("Account Sign In", color = PSBillThemeColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = brightInputColors
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = brightInputColors,
                        visualTransformation = if (showPasswordInSettings) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPasswordInSettings = !showPasswordInSettings }) {
                                Icon(
                                    imageVector = if (showPasswordInSettings) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPasswordInSettings) "Hide password" else "Show password"
                                )
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    loginWithCredentials { fetchApiData() }
                }, enabled = !isLoggingIn) {
                    Text(if (isLoggingIn) "Signing in..." else "Sign In")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiConfig = false }, enabled = !isLoggingIn) {
                    Text("Cancel", color = PSBillThemeColors.TextSecondary)
                }
            },
            containerColor = PSBillThemeColors.Surface
        )
    }
}

@Composable
private fun PSBillLoginScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isLoading: Boolean,
    onLogin: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    val brightInputColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = PSBillThemeColors.TextSecondary,
        unfocusedLabelColor = PSBillThemeColors.TextSecondary,
        cursorColor = Color.White,
        focusedBorderColor = PSBillThemeColors.PrimaryAccent,
        unfocusedBorderColor = PSBillThemeColors.Border
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PSBillThemeColors.Canvas)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PSBillThemeColors.Surface)
                .border(1.dp, PSBillThemeColors.Border, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PSBill Login", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Sign in with CVPAP account credentials.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)

                OutlinedTextField(
                    value = email,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = brightInputColors
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = brightInputColors,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    }
                )

                Button(
                    onClick = onLogin,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.PrimaryAccent)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Login")
                    }
                }
            }
        }
    }
}

// Dynamic Ticking Countdown Timer Composable
@Composable
fun LiveTickingCountdownTimer(initialSeconds: Int) {
    var secondsLeft by remember(initialSeconds) { mutableStateOf(initialSeconds) }

    LaunchedEffect(initialSeconds) {
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft -= 1
        }
    }

    val mins = secondsLeft / 60
    val secs = secondsLeft % 60
    val timeFormatted = String.format("%02d:%02d", mins, secs)

    Box(
        Modifier.size(150.dp).clip(CircleShape).background(PSBillThemeColors.SurfaceAlt).border(3.dp, PSBillThemeColors.PrimaryAccent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(timeFormatted, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 32.sp, fontFamily = FontFamily.Monospace)
            Text(if (secondsLeft > 0) "REMAINING" else "EXPIRED", color = if (secondsLeft > 0) PSBillThemeColors.TextSecondary else PSBillThemeColors.Crimson, fontSize = 10.sp)
        }
    }
}

// 1. Sessions View (Fully Functional Station PIN Pairing & Camera QR Scanner)
@Composable
fun PSBillSessionsView(
    nodes: List<JSONObject>,
    pairedNode: JSONObject?,
    isLoading: Boolean,
    onPairPin: (String) -> Unit,
    onActivateByQr: (String) -> Boolean,
    onRefresh: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            showQrScannerDialog = true
        } else {
            Toast.makeText(context, "Camera permission is required to scan QR codes.", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PSBillCard(title = "Station Pair & Camera Scanner") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter 6-digit PIN code shown on TV screen or scan QR code:", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text("Station PIN Code (e.g. 842-195)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PSBillThemeColors.PrimaryAccent)
                    )
                    BoxWithConstraints {
                        val isCompact = maxWidth < 460.dp
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        if (pinInput.trim().isEmpty()) {
                                            Toast.makeText(context, "Please enter a 6-digit station PIN", Toast.LENGTH_SHORT).show()
                                        } else {
                                            onPairPin(pinInput)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.PrimaryAccent)
                                ) {
                                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Pair Station")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (hasCameraPermission) {
                                            showQrScannerDialog = true
                                        } else {
                                            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PSBillThemeColors.PrimaryAccent)
                                ) {
                                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Scan QR")
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        if (pinInput.trim().isEmpty()) {
                                            Toast.makeText(context, "Please enter a 6-digit station PIN", Toast.LENGTH_SHORT).show()
                                        } else {
                                            onPairPin(pinInput)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.PrimaryAccent)
                                ) {
                                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Pair Station")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (hasCameraPermission) {
                                            showQrScannerDialog = true
                                        } else {
                                            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PSBillThemeColors.PrimaryAccent)
                                ) {
                                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Scan QR")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("⏱ Live Gaming Session HUD", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PSBillThemeColors.PrimaryAccent, strokeWidth = 2.dp)
                }
            }
        }
        val activeSessionNodes = nodes.filter { node ->
            val status = node.optString("status", "").uppercase(Locale.US)
            val hasGame = node.optString("current_game").isNotBlank()
            status in setOf("ACTIVE", "OVERTIME", "PENDING") && hasGame
        }
        val endedSessionNodes = nodes.filter { node ->
            node.optBoolean("has_ended_session", false)
        }

        if (activeSessionNodes.isEmpty()) {
            item {
                PSBillCard(title = "No Active Game Session") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                        Icon(Icons.Filled.TvOff, contentDescription = null, tint = PSBillThemeColors.TextSecondary, modifier = Modifier.size(48.dp))
                        Text("No screen currently has an active session.", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Sessions only appear here when a game is actually running.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                        OutlinedButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh")
                        }
                    }
                }
            }
        } else {
            items(activeSessionNodes) { targetNode ->
                val name = targetNode.optString("device_name", targetNode.optString("name", "TV Station"))
                val status = targetNode.optString("status", "ACTIVE").uppercase(Locale.US)
                val game = targetNode.optString("current_game", "Unknown Game")
                val ratePerMin = targetNode.optDouble("rate_per_minute_kes", targetNode.optDouble("rate_per_minute", 0.0))
                val runningBill = targetNode.optDouble("active_billing_kes", targetNode.optDouble("total_paid_kes", 0.0))
                PSBillCard(title = name) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(name, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Playing: $game", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                            }
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(
                                    if (status == "ACTIVE") PSBillThemeColors.SecondaryAccent.copy(alpha = 0.2f) else PSBillThemeColors.Crimson.copy(alpha = 0.2f)
                                ).padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(status, color = if (status == "ACTIVE") PSBillThemeColors.SecondaryAccent else PSBillThemeColors.Crimson, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Rate", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                                Text(
                                    if (ratePerMin > 0) "KSh ${String.format("%.2f", ratePerMin)}/min" else "Not set",
                                    color = PSBillThemeColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Amount Due", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                                Text("KSh ${String.format("%.2f", runningBill)}", color = PSBillThemeColors.SecondaryAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("🧾 Ended Sessions Waiting Reset", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        if (endedSessionNodes.isEmpty()) {
            item {
                Text("No ended sessions pending payment/reset.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            }
        } else {
            items(endedSessionNodes) { endedNode ->
                val name = endedNode.optString("device_name", endedNode.optString("name", "TV Station"))
                val game = endedNode.optString("current_game").ifBlank { "No game recorded" }
                val due = endedNode.optDouble("active_billing_kes", 0.0)
                PSBillCard(title = name) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Session ended • payment pending/reset", color = PSBillThemeColors.Crimson, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Game: $game", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                        Text("Due: KSh ${String.format("%.2f", due)}", color = PSBillThemeColors.SecondaryAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Use Screens tab to mark paid + reset this screen and make it available for next game.", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    // Camera QR Scanner Modal Dialog
    if (showQrScannerDialog) {
        QRScannerDialog(
            onDismiss = { showQrScannerDialog = false },
            onScan = { qrValue ->
                showQrScannerDialog = false
                if (onActivateByQr(qrValue)) {
                    return@QRScannerDialog
                }
                extractStationPinFromQrPayload(qrValue)?.let { pin ->
                    pinInput = pin.chunked(3).joinToString("-")
                    onPairPin(pin)
                    return@QRScannerDialog
                }

                val scannedLink = extractNetworkLinkFromQrPayload(qrValue)
                if (scannedLink != null) {
                    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(scannedLink))
                    try {
                        context.startActivity(openIntent)
                        Toast.makeText(context, "Opening QR link...", Toast.LENGTH_SHORT).show()
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app available to open this QR link.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    onGoHome()
                    Toast.makeText(
                        context,
                        "Scanned QR has no valid station PIN or link. Try another code.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}

@Composable
private fun QRScannerDialog(
    onDismiss: () -> Unit,
    onScan: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ScannerView(onScan = onScan)

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = Color.White)
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    color = PSBillThemeColors.Surface.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Align the TV QR code inside the camera view.",
                        color = PSBillThemeColors.TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerView(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var hasScanned by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
        }
    }

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
                val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageAnalysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                Camera2Interop.Extender(imageAnalysisBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                val imageAnalysis = imageAnalysisBuilder.build()
                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    processImageProxy(imageProxy) { scannedValue ->
                        if (!hasScanned) {
                            hasScanned = true
                            onScan(scannedValue)
                        }
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
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
                            val action = FocusMeteringAction.Builder(
                                focusPoint,
                                FocusMeteringAction.FLAG_AF
                            ).disableAutoCancel().build()
                            camera.cameraControl.startFocusAndMetering(action)
                        }
                    }
                } catch (_: Exception) {
                    hasScanned = true
                    Toast.makeText(context, "Unable to start camera scanner.", Toast.LENGTH_SHORT).show()
                }
            }, executor)

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun processImageProxy(
    imageProxy: androidx.camera.core.ImageProxy,
    onScan: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        BarcodeScanning.getClient()
            .process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let {
                        onScan(it)
                        return@addOnSuccessListener
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}

// 2. Matches View
@Composable
fun PSBillCard(title: String, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PSBillThemeColors.Surface).border(1.dp, PSBillThemeColors.Border, RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            content()
        }
    }
}
