package com.example.psbill.modules.sms

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.psbill.SmsDispatcherService
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.screens.SmsEngineScreen
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object SmsFeature : FeatureModule() {
    override val slug = "sms"
    override val nav = NavModule("sms", "SMS Engine", Icons.Filled.Email, order = 30)
    override val syncStreams get() = SmsSync.streams

    private val http by lazy { com.example.psbill.core.ActivityLog.client }

    @Composable
    override fun Content(ctx: ModuleContext) {
        val prompt = rememberSmsPermissionPrompt()
        SmsEngineScreen(
            server = ctx.server,
            headers = ctx.headers,
            isAdmin = ctx.isAdmin,
            onRequestPermissions = prompt,
        )
    }

    /** Ask for SMS permissions / default-SMS role once per sign-in and keep the engine running. */
    @Composable
    override fun SessionEffects(ctx: ModuleContext) {
        val context = LocalContext.current
        val prompt = rememberSmsPermissionPrompt()
        LaunchedEffect(ctx.partnerId) {
            prompt()
            startEngine(context)
        }
    }

    @Composable
    override fun TopBarAction(ctx: ModuleContext) {
        if (!ctx.canSendSms) return
        var open by remember { mutableStateOf(false) }
        Button(
            onClick = { open = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
            modifier = Modifier.padding(end = 4.dp)
        ) { Text("SMS", color = Color.White, fontSize = 12.sp) }
        if (open) SmsComposeDialog(ctx) { open = false }
    }

    override fun onLogout(context: Context) {
        runCatching { context.stopService(Intent(context, SmsDispatcherService::class.java)) }
    }

    fun startEngine(context: Context) {
        val canSend = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!canSend) {
            Log.w("SmsFeature", "SEND_SMS not granted yet; engine starts once permissions are granted")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, SmsDispatcherService::class.java))
        } catch (e: Exception) {
            Log.e("SmsFeature", "Failed to start SmsDispatcherService: ${e.message}")
        }
    }

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    @Composable
    fun rememberSmsPermissionPrompt(): () -> Unit {
        val context = LocalContext.current
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            if (granted[Manifest.permission.SEND_SMS] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
            ) startEngine(context)
        }
        val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
        return remember(context) {
            {
                val missing = requiredPermissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                        runCatching { roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)) }
                    }
                } else if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) {
                    runCatching {
                        context.startActivity(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun SmsComposeDialog(ctx: ModuleContext, onDismiss: () -> Unit) {
        val context = LocalContext.current
        var smsPhone by remember { mutableStateOf("") }
        var smsMessage by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }
        val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF161E2F),
            title = { Text("Send SMS", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = smsPhone,
                        onValueChange = { smsPhone = it },
                        label = { Text("Phone Number (+254...)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4FC3F7), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = smsMessage,
                        onValueChange = { smsMessage = it },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4FC3F7), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val parts = if (smsMessage.isEmpty()) 0 else (smsMessage.length + 152) / 153
                        Text("${smsMessage.length} chars · ${maxOf(parts, if (smsMessage.length <= 160 && smsMessage.isNotEmpty()) 1 else parts)} SMS", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (smsPhone.isBlank() || smsMessage.isBlank()) {
                            Toast.makeText(context, "Phone and message are required", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSending = true
                        val payload = JSONObject().apply {
                            put("message", smsMessage.trim())
                            put("phone_numbers", JSONArray().apply { put(smsPhone.trim()) })
                        }
                        val req = Request.Builder()
                            .url("${ctx.baseUrl}/api/v1/kiosk/sms/send")
                            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .headers(ctx.headers())
                            .build()
                        http.newCall(req).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                mainHandler.post {
                                    isSending = false
                                    Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            override fun onResponse(call: Call, response: Response) {
                                val ok = response.isSuccessful
                                val code = response.code
                                response.close()
                                mainHandler.post {
                                    isSending = false
                                    if (ok) {
                                        Toast.makeText(context, "SMS queued", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    } else {
                                        Toast.makeText(context, "Failed ($code)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        })
                    },
                    enabled = !isSending,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7))
                ) {
                    if (isSending) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp))
                    else Text("Send", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
        )
    }
}
