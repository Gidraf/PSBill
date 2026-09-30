package com.example.psbill

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.psbill.ui.theme.PSBillTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections

class DeviceAuthActivity : ComponentActivity() {

    private sealed class StartupScreen {
        data object Loading : StartupScreen()
        data class Activation(val initialMode: String = "NONE") : StartupScreen()
        data class Recovery(val message: String) : StartupScreen()
    }

    private val TAG = "DeviceAuthActivity"
    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val SERVER_URL = "https://api.ajiriwa.gidraf.dev"

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var startupScreen by mutableStateOf<StartupScreen>(StartupScreen.Loading)
    private var savedFingerprint: String = ""
    private var savedNickname: String = ""
    private var savedConsoleType: String = "PS5"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val securePrefs = getEncryptedPrefs(this)
        val savedToken = securePrefs.getString("auth_token", "") ?: ""
        val fingerprint = getDeviceFingerprint(this)
        savedFingerprint = fingerprint
        savedNickname = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            .getString("device_name", "TV Screen ${fingerprint.takeLast(4).uppercase()}") ?: "TV Screen"
        savedConsoleType = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
            .getString("console_type", "PS5") ?: "PS5"

        setContent {
            PSBillTheme {
                when (val screen = startupScreen) {
                    StartupScreen.Loading -> LoadingScreen()
                    is StartupScreen.Activation -> ActivationScreen(screen.initialMode)
                    is StartupScreen.Recovery -> RecoveryScreen(screen.message)
                }
            }
        }

        val initialMode = intent.getStringExtra("initial_mode") ?: "NONE"
        if (savedToken.isNotEmpty() && initialMode == "NONE") {
            verifySavedToken(savedToken, fingerprint)
        } else {
            startupScreen = StartupScreen.Activation(initialMode)
            if (initialMode == "QR") {
                startPollingForActivation(fingerprint, savedNickname)
            }
        }
    }

    @Composable
    private fun LoadingScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF00E676))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RecoveryScreen(message: String) {
        val context = this@DeviceAuthActivity
        val fingerprint = remember { savedFingerprint.ifBlank { getDeviceFingerprint(context) } }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.width(640.dp)
            ) {
                Text(
                    text = "Device Recovery",
                    color = Color(0xFFFFB74D),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = message,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Fingerprint: ${fingerprint.take(16)}... (SHA-256)",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { reconnectExistingDevice(fingerprint) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reconnect", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            stopPolling()
                            startupScreen = StartupScreen.Activation("QR")
                            startPollingForActivation(fingerprint, savedNickname)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF818CF8)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Relink Device", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { clearFreshActivationState() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Connect Fresh", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    private fun verifySavedToken(savedToken: String, fingerprint: String) {
        val request = Request.Builder()
            .url("$SERVER_URL/api/devices/verify-token")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $savedToken")
            .header("X-Device-Fingerprint", fingerprint)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    startupScreen = StartupScreen.Recovery("Unable to reach the server. Reconnect or connect fresh.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    when {
                        response.isSuccessful -> runOnUiThread { launchMainActivity() }
                        response.code == 404 -> runOnUiThread {
                            startupScreen = StartupScreen.Recovery("This device was deleted from the server. Reconnect if it still exists, or connect fresh.")
                        }
                        response.code == 401 || response.code == 403 -> runOnUiThread {
                            startupScreen = StartupScreen.Recovery("Your session expired or is no longer valid. Reconnect or connect fresh.")
                        }
                        else -> runOnUiThread {
                            startupScreen = StartupScreen.Recovery("Device check failed (${response.code}). Reconnect or connect fresh.")
                        }
                    }
                }
            }
        })
    }

    private fun reconnectExistingDevice(fingerprint: String) {
        val securePrefs = getEncryptedPrefs(this)
        val token = securePrefs.getString("auth_token", "") ?: ""
        if (token.isBlank()) {
            Toast.makeText(this, "No saved device to reconnect.", Toast.LENGTH_SHORT).show()
            return
        }
        verifySavedToken(token, fingerprint)
    }

    private fun clearFreshActivationState() {
        stopPolling()
        val securePrefs = getEncryptedPrefs(this)
        securePrefs.edit().remove("auth_token").commit()
        val sharedPrefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .remove("partner_id")
            .remove("device_name")
            .remove("console_type")
            .remove("last_sync_data")
            .remove("last_sync_time")
            .commit()
        savedNickname = "TV Screen " + savedFingerprint.takeLast(4).uppercase()
        startupScreen = StartupScreen.Activation()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ActivationScreen(initialMode: String = "NONE") {
        val context = this
        val fingerprint = remember { getDeviceFingerprint(context) }
        
        var selectedMode by remember { mutableStateOf(initialMode) }
        var activationKey by remember { mutableStateOf("") }
        var tvNickname by remember { mutableStateOf(savedNickname.ifBlank { "TV Screen " + fingerprint.takeLast(4).uppercase() }) }
        var consoleType by remember { mutableStateOf(savedConsoleType) }
        var isActivating by remember { mutableStateOf(false) }

        LaunchedEffect(initialMode) {
            if (initialMode == "QR") {
                startPollingForActivation(fingerprint, tvNickname)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.width(600.dp)
            ) {
                Text(
                    text = "PlayGate TV Activation",
                    color = Color(0xFF00E676),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Fingerprint: ${fingerprint.take(16)}... (SHA-256)",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedMode == "NONE") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Button(
                            onClick = { selectedMode = "KEY" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E2F)),
                            modifier = Modifier
                                .weight(1f)
                                .height(120.dp)
                                .border(1.dp, Color(0xFF00E676).copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Enter Activation Key", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Type key manually on screen", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }

                        Button(
                            onClick = { 
                                selectedMode = "QR" 
                                startPollingForActivation(fingerprint, tvNickname)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E2F)),
                            modifier = Modifier
                                .weight(1f)
                                .height(120.dp)
                                .border(1.dp, Color(0xFF00E676).copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Show Linking QR Code", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Scan QR with Agent App to link", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                if (selectedMode == "KEY") {
                    OutlinedTextField(
                        value = tvNickname,
                        onValueChange = { tvNickname = it },
                        label = { Text("TV Nickname / Location", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.Gray
                        )
                    )

                    OutlinedTextField(
                        value = activationKey,
                        onValueChange = { activationKey = it.uppercase() },
                        label = { Text("Activation Key (PGXXX-XXXXX-XXXXX)", color = Color.Gray) },
                        placeholder = { Text("PG7K2-A3MNQ-9TZ4W") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.Gray
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = { selectedMode = "NONE" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Back", color = Color.Black)
                        }

                        Button(
                            onClick = { 
                                isActivating = true
                                activateDeviceByKey(activationKey, fingerprint, tvNickname, consoleType) { success, errMsg ->
                                    isActivating = false
                                    if (success) {
                                        launchMainActivity()
                                    } else {
                                        Toast.makeText(context, errMsg ?: "Activation failed", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            modifier = Modifier.weight(2f),
                            enabled = !isActivating && activationKey.isNotEmpty()
                        ) {
                            if (isActivating) {
                                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp))
                            } else {
                                Text("Activate Device", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (selectedMode == "QR") {
                    val activationLink = remember(fingerprint, tvNickname, consoleType) {
                        buildActivationQrLink(fingerprint, tvNickname, consoleType)
                    }
                    val qrData = JSONObject().apply {
                        put("fingerprint", fingerprint)
                        put("tv_name", tvNickname)
                        put("console_type", consoleType)
                        put("link", activationLink)
                    }.toString()

                    val qrBitmap = remember(qrData) { generateQrCodeBitmap(qrData) }

                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Device Registration QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Text(
                        text = "Scan this code with the PlayGate Agent app on your phone. The TV screen will activate automatically once linked.",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    Text(
                        text = activationLink,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    Button(
                        onClick = { 
                            stopPolling()
                            selectedMode = "NONE" 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.width(200.dp)
                    ) {
                        Text("Cancel", color = Color.Black)
                    }
                }
            }
        }
    }

    private fun activateDeviceByKey(key: String, fingerprint: String, name: String, console: String, callback: (Boolean, String?) -> Unit) {
        val url = "$SERVER_URL/api/devices/activate-by-key"
        val payload = JSONObject().apply {
            put("activation_key", key.trim())
            put("fingerprint", fingerprint)
            put("device_name", name.trim())
            put("console_type", console)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback(false, "Server unreachable: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(bodyStr)
                            val token = json.getString("token")
                            
                            // Save token to Encrypted SharedPreferences
                            val securePrefs = getEncryptedPrefs(this@DeviceAuthActivity)
                            securePrefs.edit().putString("auth_token", token).commit()

                            val deviceName = json.getJSONObject("device").optString("device_name", name)
                            
                            // Cache custom settings values for backend pings
                            val sharedPrefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().apply {
                                putString("server_domain", "api.ajiriwa.gidraf.dev")
                                putString("partner_id", json.getJSONObject("device").getString("partner_id"))
                                putString("device_name", deviceName)
                            }.commit()
                            runOnUiThread { callback(true, null) }
                        } catch (e: Exception) {
                            runOnUiThread { callback(false, "Invalid response payload") }
                        }
                    } else {
                        val err = try { JSONObject(bodyStr).getString("error") } catch (e: Exception) { "Invalid key" }
                        runOnUiThread { callback(false, err) }
                    }
                }
            }
        }
    )
}

    private fun startPollingForActivation(fingerprint: String, defaultNickname: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(3000) // Poll status every 3 seconds
                checkActivationStatus(fingerprint, defaultNickname)
            }
        }
    }

    private fun checkActivationStatus(fingerprint: String, defaultNickname: String) {
        val url = "$SERVER_URL/api/devices/check-activation/$fingerprint"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) return
                    val bodyStr = response.body?.string() ?: return
                    try {
                        val json = JSONObject(bodyStr)
                        if (json.optBoolean("activated", false)) {
                            val token = json.getString("token")
                            
                            val securePrefs = getEncryptedPrefs(this@DeviceAuthActivity)
                            securePrefs.edit().putString("auth_token", token).commit()

                            // Decode JWT to extract partner_id and cache in KioskPrefs
                            try {
                                val parts = token.split(".")
                                if (parts.size >= 2) {
                                    val payloadEncoded = parts[1]
                                    val payloadBytes = android.util.Base64.decode(payloadEncoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                                    val payloadString = String(payloadBytes, Charsets.UTF_8)
                                    val payloadJson = JSONObject(payloadString)
                                    val partnerId = payloadJson.optString("partner_id", "default_partner")
                                    
                                    var deviceName = defaultNickname
                                    try {
                                        val deviceObj = json.optJSONObject("device")
                                        if (deviceObj != null) {
                                            deviceName = deviceObj.optString("device_name", defaultNickname)
                                        }
                                    } catch (e: Exception) {}

                                    val sharedPrefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
                                    sharedPrefs.edit().apply {
                                        putString("server_domain", "api.ajiriwa.gidraf.dev")
                                        putString("partner_id", partnerId)
                                        putString("device_name", deviceName)
                                    }.commit()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode JWT on activation: ${e.message}")
                            }

                            // Auto-fetch properties and run MainActivity
                            runOnUiThread {
                                stopPolling()
                                launchMainActivity()
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        })
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun launchMainActivity() {
        try {
            stopService(Intent(this, KioskService::class.java))
        } catch (e: Exception) {}
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun getDeviceFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_ID"
        val mac = getMacAddress()
        val model = Build.MODEL ?: "UNKNOWN_MODEL"
        val rawFingerprint = androidId + mac + model
        return sha256(rawFingerprint)
    }

    private fun buildActivationQrLink(fingerprint: String, tvName: String, consoleType: String): String {
        return Uri.Builder()
            .scheme("https")
            .authority(Uri.parse(SERVER_URL).host ?: "api.ajiriwa.gidraf.dev")
            .appendPath("device-link")
            .appendQueryParameter("fingerprint", fingerprint)
            .appendQueryParameter("tv_name", tvName)
            .appendQueryParameter("console_type", consoleType)
            .build()
            .toString()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
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

    private fun generateQrCodeBitmap(content: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "SecureKioskPrefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
    }
}
