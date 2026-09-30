package com.example.psbill.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.psbill.ui.components.EmptyState
import com.example.psbill.ui.components.GlassCard
import com.example.psbill.ui.components.SectionCard
import com.example.psbill.ui.components.SectionTitle
import com.example.psbill.ui.theme.AjiriwaColors
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val SMS_JSON_MT = "application/json; charset=utf-8".toMediaType()

/**
 * SmsEngineScreen — Ajiriwa Client's primary communication module.
 *
 * 📥 Inbox tab: All SMS received on the device, synced from AllSmsReceiver.
 *              Shows sender, body, timestamp, M-Pesa badge.
 * 📤 Outbox tab: Server-triggered outbound SMS from GET /api/v1/kiosk/sms/queue.
 *              Shows triggered_by attribution (who on server/web sent it).
 *              Compose FAB visible only for admin users.
 */
@Composable
fun SmsEngineScreen(
    server: String,
    headers: () -> Headers,
    isAdmin: Boolean,
    onRequestPermissions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val client = remember { 
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build() 
    }
    val base = remember(server) { "https://${server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}" }

    val hasReadSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    val hasReceiveSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val hasSendSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    val hasReadContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val hasReadPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    val isMissingPermissions = !hasReadSms || !hasReceiveSms || !hasSendSms || !hasReadContacts || !hasReadPhoneState

    var subTab by remember { mutableStateOf("INBOX") }  // INBOX, OUTBOX, DEVICE, CONTACTS

    val prefs = remember { context.getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE) }
    var preferredSubId by remember { mutableIntStateOf(prefs.getInt("preferred_sim_sub_id", -1)) }
    var availableSims by remember { mutableStateOf<List<SubscriptionInfo>>(emptyList()) }

    fun loadSims() {
        if (!hasReadPhoneState || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        try {
            availableSims = sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {}
    }

    LaunchedEffect(hasReadPhoneState) {
        if (hasReadPhoneState) loadSims()
    }

    val isDefaultSmsApp = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    }

    val defaultSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun requestDefaultSms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                defaultSmsLauncher.launch(intent)
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
            context.startActivity(intent)
        }
    }

    // Date & Pagination State
    var dateFilter by remember { mutableStateOf("ALL") }  // ALL, TODAY, 7DAYS, 30DAYS

    var inboxPage by remember { mutableIntStateOf(1) }
    var inboxTotal by remember { mutableIntStateOf(0) }
    val inboxTotalPages = maxOf(1, (inboxTotal + 24) / 25)

    var outboxPage by remember { mutableIntStateOf(1) }
    var outboxTotal by remember { mutableIntStateOf(0) }
    val outboxTotalPages = maxOf(1, (outboxTotal + 24) / 25)

    var deviceSmsPage by remember { mutableIntStateOf(1) }
    var callLogsPage by remember { mutableIntStateOf(1) }

    // Inbox state
    var inboxMessages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var inboxLoading by remember { mutableStateOf(true) }
    var inboxError by remember { mutableStateOf<String?>(null) }

    // Outbox state
    var outboxMessages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var outboxFilter by remember { mutableStateOf("") }
    var outboxLoading by remember { mutableStateOf(true) }

    // Device SMS State
    var deviceSms by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var deviceSmsLoading by remember { mutableStateOf(false) }

    // Contacts State
    var deviceContacts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var contactsLoading by remember { mutableStateOf(false) }

    // Compose modal (admin only)
    var showCompose by remember { mutableStateOf(false) }
    var composePhone by remember { mutableStateOf("") }
    var composeMessage by remember { mutableStateOf("") }
    var composeSending by remember { mutableStateOf(false) }
    var scheduleTime by remember { mutableStateOf("") }

    // Call Logs State
    var deviceCallLogs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var callLogsLoading by remember { mutableStateOf(false) }
    var isSyncingCallLogs by remember { mutableStateOf(false) }

    fun getStartDateForFilter(filter: String): String? {
        val cal = java.util.Calendar.getInstance()
        return when (filter) {
            "TODAY" -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            "7DAYS" -> {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            }
            "30DAYS" -> {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -30)
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            }
            else -> null
        }
    }

    // Chat Details State (Links SMS + Call Logs)
    var selectedChatSender by remember { mutableStateOf<String?>(null) }
    val selectedChatMessages = remember(selectedChatSender, inboxMessages, deviceSms, deviceCallLogs) {
        if (selectedChatSender == null) emptyList()
        else {
            val target = selectedChatSender!!
            val cleanTarget = target.filter { it.isDigit() }.takeLast(9)

            fun isMatch(sender: String): Boolean {
                if (sender == target) return true
                val c = sender.filter { it.isDigit() }.takeLast(9)
                return cleanTarget.isNotBlank() && c == cleanTarget
            }

            val fromInbox = inboxMessages.filter { isMatch(it.optString("sender")) }
            val fromDevice = deviceSms.filter { isMatch(it.optString("sender")) }
            val fromCalls = deviceCallLogs.filter { isMatch(it.optString("caller_number")) }

            (fromInbox + fromDevice + fromCalls).sortedBy { msg ->
                val d = msg.optLong("date", 0L)
                if (d > 0) d else {
                    val rx = msg.optString("received_at", "")
                    val ts = msg.optString("timestamp", "")
                    val str = if (rx.isNotBlank()) rx else ts
                    try {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(str)?.time ?: 0L
                    } catch (e: Exception) { 0L }
                }
            }
        }
    }

    if (selectedChatSender != null) {
        SmsChatScreen(
            sender = selectedChatSender!!,
            messages = selectedChatMessages,
            onBack = { selectedChatSender = null }
        )
        return
    }

    fun loadInbox(page: Int = inboxPage, filterDate: String = dateFilter) {
        inboxLoading = true; inboxError = null
        val startDate = getStartDateForFilter(filterDate)
        val url = buildString {
            append("$base/api/v1/kiosk/sms/inbox?page=$page&limit=25")
            if (!startDate.isNullOrBlank()) append("&start_date=$startDate")
        }
        val req = Request.Builder().url(url).headers(headers()).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    inboxLoading = false; inboxError = e.message
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    inboxLoading = false
                    runCatching {
                        val root = JSONObject(body)
                        val arr = root.optJSONArray("items") ?: JSONArray()
                        inboxTotal = root.optInt("total", arr.length())
                        inboxMessages = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    }.onFailure { inboxError = "Parse error" }
                }
            }
        })
    }

    fun loadOutbox(page: Int = outboxPage, status: String = outboxFilter, filterDate: String = dateFilter) {
        outboxLoading = true
        val startDate = getStartDateForFilter(filterDate)
        val url = buildString {
            append("$base/api/v1/kiosk/sms/queue?page=$page&limit=25")
            if (status.isNotBlank()) append("&status=$status")
            if (!startDate.isNullOrBlank()) append("&start_date=$startDate")
        }
        val req = Request.Builder().url(url).headers(headers()).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { outboxLoading = false }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    outboxLoading = false
                    runCatching {
                        val root = JSONObject(body)
                        val arr = root.optJSONArray("items") ?: JSONArray()
                        outboxTotal = root.optInt("total", arr.length())
                        outboxMessages = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    }
                }
            }
        })
    }

    fun loadDeviceSms() {
        if (!hasReadSms) { onRequestPermissions(); return }
        deviceSmsLoading = true
        Thread {
            val list = mutableListOf<JSONObject>()
            try {
                val cursor = context.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"),
                    null, null, "date DESC"
                )
                cursor?.use { c ->
                    val addrIdx = c.getColumnIndex("address")
                    val bodyIdx = c.getColumnIndex("body")
                    val dateIdx = c.getColumnIndex("date")
                    var count = 0
                    while (c.moveToNext() && count < 100) {
                        list.add(JSONObject().apply {
                            put("sender", if (addrIdx >= 0) c.getString(addrIdx) else "")
                            put("body", if (bodyIdx >= 0) c.getString(bodyIdx) else "")
                            put("date", if (dateIdx >= 0) c.getLong(dateIdx) else 0L)
                        })
                        count++
                    }
                }
            } catch (e: Exception) {}
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                deviceSms = list
                deviceSmsLoading = false
            }
        }.start()
    }

    fun loadCallLogs() {
        if (!hasReadPhoneState) { onRequestPermissions(); return }
        callLogsLoading = true
        Thread {
            val list = mutableListOf<JSONObject>()
            try {
                val cursor = context.contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.CACHED_NAME,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.DURATION,
                        android.provider.CallLog.Calls.DATE
                    ),
                    null, null, android.provider.CallLog.Calls.DATE + " DESC"
                )
                cursor?.use { c ->
                    val numIdx = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                    val nameIdx = c.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                    val typeIdx = c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                    val durIdx = c.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                    val dateIdx = c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                    var count = 0
                    while (c.moveToNext() && count < 300) {
                        val num = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                        val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                        val typeInt = if (typeIdx >= 0) c.getInt(typeIdx) else 1
                        val duration = if (durIdx >= 0) c.getInt(durIdx) else 0
                        val dateLong = if (dateIdx >= 0) c.getLong(dateIdx) else 0L

                        val typeStr = when (typeInt) {
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            android.provider.CallLog.Calls.MISSED_TYPE -> "MISSED"
                            android.provider.CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                            else -> "INCOMING"
                        }
                        if (num.isNotBlank()) {
                            list.add(JSONObject().apply {
                                put("caller_number", num)
                                put("caller_name", name)
                                put("call_type", typeStr)
                                put("duration_seconds", duration)
                                put("date", dateLong)
                                put("direction", "CALL")
                                put("is_call", true)
                            })
                        }
                        count++
                    }
                }
            } catch (e: Exception) {}
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                deviceCallLogs = list
                callLogsLoading = false
            }
        }.start()
    }

    fun syncCallLogsToServer() {
        if (deviceCallLogs.isEmpty()) {
            Toast.makeText(context, "No call logs to sync", Toast.LENGTH_SHORT).show()
            return
        }
        isSyncingCallLogs = true
        Thread {
            try {
                val logsArr = JSONArray()
                deviceCallLogs.take(100).forEach { item ->
                    logsArr.put(JSONObject().apply {
                        put("caller_number", item.optString("caller_number"))
                        put("caller_name", item.optString("caller_name"))
                        put("call_type", item.optString("call_type"))
                        put("duration_seconds", item.optInt("duration_seconds"))
                        put("timestamp", item.optLong("date"))
                    })
                }
                val payload = JSONObject().apply { put("call_logs", logsArr) }
                val req = Request.Builder()
                    .url("$base/api/v1/kiosk/gateway/call-logs")
                    .post(payload.toString().toRequestBody(SMS_JSON_MT))
                    .headers(headers())
                    .build()
                try { client.newCall(req).execute().close() } catch (e: Exception) {}
            } catch (e: Exception) {
                android.util.Log.e("SmsEngine", "Call log sync error: ${e.message}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                isSyncingCallLogs = false
                Toast.makeText(context, "Call logs synced to server", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    fun loadContacts() {
        if (!hasReadContacts) { onRequestPermissions(); return }
        contactsLoading = true
        Thread {
            val list = mutableListOf<Pair<String, String>>()
            try {
                val cursor = context.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                cursor?.use { c ->
                    val nameIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "No Name" else "No Name"
                        val num = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                        list.add(name to num)
                    }
                }
            } catch (e: Exception) {}
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                deviceContacts = list.distinctBy { it.second }
                contactsLoading = false
            }
        }.start()
    }

    var isSyncingPhoneSms by remember { mutableStateOf(false) }

    fun syncDeviceInbox() {
        if (!hasReadSms) {
            onRequestPermissions()
            return
        }
        isSyncingPhoneSms = true
        Thread {
            try {
                val cursor = context.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"),
                    null, null, "date DESC"
                )
                val ISO = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                cursor?.use { c ->
                    val addrIdx = c.getColumnIndex("address")
                    val bodyIdx = c.getColumnIndex("body")
                    val dateIdx = c.getColumnIndex("date")
                    var count = 0
                    while (c.moveToNext() && count < 50) {
                        val sender = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                        val body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else ""
                        val dateLong = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                        val ts = if (dateLong > 0) ISO.format(java.util.Date(dateLong)) else ISO.format(java.util.Date())
                        val isMpesa = sender.contains("MPESA", ignoreCase = true) || (body.contains("Confirmed", ignoreCase = false) && body.contains("Ksh"))

                        val payload = JSONObject().apply {
                            put("sender", sender)
                            put("body", body)
                            put("timestamp", ts)
                            put("is_mpesa", isMpesa)
                        }
                        val req = Request.Builder()
                            .url("$base/api/v1/kiosk/sms/inbox")
                            .post(payload.toString().toRequestBody(SMS_JSON_MT))
                            .headers(headers())
                            .build()
                        try { client.newCall(req).execute().close() } catch (e: Exception) {}
                        count++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SmsEngine", "Sync error: ${e.message}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                isSyncingPhoneSms = false
                loadInbox()
            }
        }.start()
    }

    fun retrySms(smsId: String) {
        val req = Request.Builder()
            .url("$base/api/v1/kiosk/sms/$smsId/retry")
            .post("{}".toRequestBody(SMS_JSON_MT))
            .headers(headers())
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
                android.os.Handler(android.os.Looper.getMainLooper()).post { loadOutbox() }
            }
        })
    }

    var retryAllLoading by remember { mutableStateOf(false) }

    fun retryAllExpired() {
        retryAllLoading = true
        val req = Request.Builder()
            .url("$base/api/v1/kiosk/sms/retry-expired")
            .post("{}".toRequestBody(SMS_JSON_MT))
            .headers(headers())
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { retryAllLoading = false }
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    retryAllLoading = false
                    loadOutbox()
                    Toast.makeText(context, "Expired SMS queued for retry", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    fun sendCompose() {
        if (composePhone.isBlank() || composeMessage.isBlank()) return
        composeSending = true
        val payload = JSONObject().apply {
            put("recipient_phone", composePhone)
            put("message_text", composeMessage)
            if (scheduleTime.isNotBlank()) put("scheduled_at", scheduleTime)
        }
        val req = Request.Builder()
            .url("$base/api/v1/kiosk/sms/send")
            .post(payload.toString().toRequestBody(SMS_JSON_MT))
            .headers(headers())
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { 
                    composeSending = false 
                    Toast.makeText(context, "Network Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val code = response.code
                response.close()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    composeSending = false
                    if (success) {
                        showCompose = false
                        composePhone = ""; composeMessage = ""; scheduleTime = ""
                        loadOutbox()
                        Toast.makeText(context, "SMS queued successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Server Error ($code). Failed to queue SMS.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    LaunchedEffect(subTab) {
        when(subTab) {
            "INBOX" -> loadInbox()
            "OUTBOX" -> loadOutbox()
            "DEVICE" -> loadDeviceSms()
            "CONTACTS" -> loadContacts()
            "CALL_LOGS" -> loadCallLogs()
        }
    }

    // Auto-refresh when service updates status
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (subTab == "DEVICE") loadDeviceSms()
                if (subTab == "INBOX") loadInbox()
                if (subTab == "OUTBOX") loadOutbox()
                if (subTab == "CALL_LOGS") loadCallLogs()
            }
        }
        context.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, observer)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    var isSyncingContacts by remember { mutableStateOf(false) }

    fun syncContactsToServer() {
        if (!hasReadContacts) {
            onRequestPermissions()
            return
        }
        isSyncingContacts = true
        Thread {
            try {
                val contactsArr = JSONArray()
                val cursor = context.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null
                )
                cursor?.use { c ->
                    val nameIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                        val num = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                        if (num.isNotBlank()) {
                            contactsArr.put(JSONObject().apply {
                                put("name", name)
                                put("phone", num)
                            })
                        }
                    }
                }
                val payload = JSONObject().apply { put("contacts", contactsArr) }
                val req = Request.Builder()
                    .url("$base/api/v1/kiosk/gateway/contacts")
                    .post(payload.toString().toRequestBody(SMS_JSON_MT))
                    .headers(headers())
                    .build()
                try { client.newCall(req).execute().close() } catch (e: Exception) {}
            } catch (e: Exception) {
                android.util.Log.e("SmsEngine", "Contacts sync error: ${e.message}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                isSyncingContacts = false
                loadContacts()
            }
        }.start()
    }

    // Compose Modal — available for sending SMS to contacts and customers
    if (showCompose) {
        AlertDialog(
            onDismissRequest = { showCompose = false },
            containerColor = AjiriwaColors.Surface,
            title = { Text("Compose SMS", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Dispatched directly via Ajiriwa SMS Engine.", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)

                    if (availableSims.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        Text("Select SIM Card", color = Color.Gray, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableSims) { sim ->
                                val subId = sim.subscriptionId
                                val sel = preferredSubId == subId
                                Surface(
                                    color = if (sel) AjiriwaColors.Primary else Color(0xFF1E2638),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        preferredSubId = subId
                                        prefs.edit().putInt("preferred_sim_sub_id", preferredSubId).apply()
                                    }
                                ) {
                                    Text(
                                        "${sim.displayName} (${sim.carrierName})",
                                        color = if (sel) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = composePhone,
                        onValueChange = { composePhone = it },
                        label = { Text("Recipient Phone (+254...)", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AjiriwaColors.Primary,
                            focusedTextColor = AjiriwaColors.TextPrimary,
                            unfocusedTextColor = AjiriwaColors.TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = composeMessage,
                        onValueChange = { composeMessage = it },
                        label = { Text("Message", color = Color.Gray) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AjiriwaColors.Primary,
                            focusedTextColor = AjiriwaColors.TextPrimary,
                            unfocusedTextColor = AjiriwaColors.TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = scheduleTime,
                        onValueChange = { scheduleTime = it },
                        label = { Text("Schedule Time (Optional)", color = Color.Gray) },
                        placeholder = { Text("YYYY-MM-DD HH:mm") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AjiriwaColors.Primary,
                            focusedTextColor = AjiriwaColors.TextPrimary,
                            unfocusedTextColor = AjiriwaColors.TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { sendCompose() },
                    enabled = !composeSending && composePhone.isNotBlank() && composeMessage.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Primary)
                ) {
                    if (composeSending) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                    else Text(if (scheduleTime.isBlank()) "Send SMS" else "Schedule SMS", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCompose = false }) {
                    Text("Cancel", color = AjiriwaColors.TextSecondary)
                }
            }
        )
    }

    Column(modifier.fillMaxSize()) {
        if (!isDefaultSmsApp) {
            Surface(
                color = Color(0xFF1565C0),
                modifier = Modifier.fillMaxWidth().clickable { requestDefaultSms() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "🛡️ Not set as default SMS app. Tap to allow Ajiriwa to handle incoming SMS reliably.",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { requestDefaultSms() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Set Default", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // SMS Service Status Indicator
        val serviceConnected = prefs.getBoolean("sms_service_connected", false)
        val serviceError = prefs.getString("sms_service_error", "") ?: ""
        val serviceLastSeen = prefs.getLong("sms_service_last_seen", 0L)
        val isServiceAlive = (System.currentTimeMillis() - serviceLastSeen) < 60000

        Surface(
            color = if (serviceConnected && isServiceAlive) Color(0xFF2E7D32) else Color(0xFFC62828),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (serviceConnected && isServiceAlive) "● SMS Engine Online" else "○ SMS Engine Offline / Connecting...",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = {
                        val intent = Intent(context, com.example.psbill.SmsDispatcherService::class.java)
                        context.stopService(intent)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }) {
                        Text("Restart Engine", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (serviceError.isNotBlank() && (!serviceConnected || !isServiceAlive)) {
                    Text("Last Error: $serviceError", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                }
            }
        }

        if (isMissingPermissions) {
            Surface(
                color = Color(0xFFD84315),
                modifier = Modifier.fillMaxWidth().clickable { onRequestPermissions() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "⚠️ Permissions required to read & sync SMS/Contacts. Tap to grant.",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onRequestPermissions() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Grant Permissions", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("SMS Engine Inbox", "Manage incoming marketing replies, SIM routing, and opt-outs.")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (subTab == "INBOX") {
                    Button(
                        onClick = { syncDeviceInbox() },
                        enabled = !isSyncingPhoneSms,
                        colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isSyncingPhoneSms) CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White)
                        else Text("📲 Sync Phone", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (subTab == "CONTACTS") {
                    Button(
                        onClick = { syncContactsToServer() },
                        enabled = !isSyncingContacts,
                        colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Success),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isSyncingContacts) CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White)
                        else Text("☁️ Sync Contacts", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (isAdmin) {
                    Button(
                        onClick = { showCompose = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("+ Compose", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
                IconButton(onClick = {
                    when (subTab) {
                        "INBOX" -> loadInbox()
                        "OUTBOX" -> loadOutbox()
                        "DEVICE" -> loadDeviceSms()
                        "CONTACTS" -> loadContacts()
                        "CALL_LOGS" -> loadCallLogs()
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = AjiriwaColors.PrimaryAccent)
                }
            }
        }

        // Global SIM Selector (if multiple sims available)
        if (availableSims.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item { Text("Default SIM:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                items(availableSims) { sim ->
                    val subId = sim.subscriptionId
                    val sel = preferredSubId == subId
                    Surface(
                        color = if (sel) AjiriwaColors.Primary else AjiriwaColors.Surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            preferredSubId = subId
                            prefs.edit().putInt("preferred_sim_sub_id", preferredSubId).apply()
                        }
                    ) {
                        Text(
                            sim.displayName.toString(),
                            color = if (sel) Color.Black else AjiriwaColors.TextSecondary,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Sub-tab switcher
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("INBOX" to "📥 Inbox", "OUTBOX" to "📤 Outbox", "DEVICE" to "📱 Device", "CONTACTS" to "👥 Contacts", "CALL_LOGS" to "📞 Calls").forEach { (key, label) ->
                val sel = subTab == key
                Surface(
                    color = if (sel) AjiriwaColors.Primary else AjiriwaColors.Surface,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { subTab = key }
                ) {
                    Text(
                        label, color = if (sel) Color.Black else AjiriwaColors.TextSecondary,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }


        // Date Filter Row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Date:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            listOf("ALL" to "All Time", "TODAY" to "Today", "7DAYS" to "7 Days", "30DAYS" to "30 Days").forEach { (key, label) ->
                val sel = dateFilter == key
                Surface(
                    color = if (sel) AjiriwaColors.Primary.copy(alpha = 0.2f) else AjiriwaColors.Surface,
                    shape = RoundedCornerShape(8.dp),
                    border = if (sel) androidx.compose.foundation.BorderStroke(1.dp, AjiriwaColors.Primary) else null,
                    modifier = Modifier.clickable {
                        dateFilter = key
                        inboxPage = 1; outboxPage = 1; deviceSmsPage = 1; callLogsPage = 1
                        if (subTab == "INBOX") loadInbox(1, key)
                        if (subTab == "OUTBOX") loadOutbox(1, outboxFilter, key)
                    }
                ) {
                    Text(
                        label, color = if (sel) AjiriwaColors.Primary else AjiriwaColors.TextSecondary,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (subTab) {
            "INBOX" -> {
                // ── Inbox ─────────────────────────────────────────────────────────
                when {
                    inboxLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AjiriwaColors.Primary)
                    }
                    inboxError != null -> EmptyState(Icons.Filled.Email, "Couldn't load inbox", inboxError!!)
                    inboxMessages.isEmpty() -> EmptyState(Icons.Filled.Email, "No messages yet", "All SMS received on this device will appear here.")
                    else -> {
                        val groupedMessages = inboxMessages.groupBy { it.optString("sender", "Unknown") }
                        Column(Modifier.fillMaxSize()) {
                            LazyColumn(
                                Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(groupedMessages.keys.toList()) { sender ->
                                    val messagesForSender = groupedMessages[sender] ?: emptyList()
                                    val latestMsg = messagesForSender.firstOrNull()
                                    val body = latestMsg?.optString("body", "") ?: ""
                                    val isMpesa = messagesForSender.any { it.optBoolean("is_mpesa", false) }
                                    val ts = latestMsg?.optString("received_at", "")?.take(16)?.replace("T", " ") ?: ""
                                    
                                    SectionCard(modifier = Modifier.clickable { selectedChatSender = sender }) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(sender, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    if (messagesForSender.size > 1) {
                                                        Surface(color = AjiriwaColors.Primary.copy(alpha = 0.1f), shape = RoundedCornerShape(99.dp)) {
                                                            Text("${messagesForSender.size}", color = AjiriwaColors.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                        }
                                                    }
                                                    if (isMpesa) {
                                                        Surface(color = Color(0xFF00C853), shape = RoundedCornerShape(99.dp)) {
                                                            Text("M-PESA", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                        }
                                                    }
                                                    Text(ts, color = Color.Gray, fontSize = 10.sp)
                                                }
                                            }
                                            Text(body, color = AjiriwaColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                            if (inboxTotalPages > 1) {
                                PaginationBar(
                                    page = inboxPage,
                                    totalPages = inboxTotalPages,
                                    onPrev = { if (inboxPage > 1) { inboxPage--; loadInbox(inboxPage) } },
                                    onNext = { if (inboxPage < inboxTotalPages) { inboxPage++; loadInbox(inboxPage) } }
                                )
                            }
                        }
                    }
                }
            }
            "OUTBOX" -> {
                // ── Outbox ────────────────────────────────────────────────────────
                // Filter chips
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listOf(
                        "" to "ALL",
                        "PENDING" to "⏳ Pending",
                        "PROCESSING" to "⚙️ Processing",
                        "SENT" to "✅ Sent",
                        "FAILED" to "❌ Failed",
                        "EXPIRED" to "⚠️ Expired"
                    )) { (val_, label) ->
                        val sel = outboxFilter == val_
                        Surface(
                            color = if (sel) AjiriwaColors.Primary else AjiriwaColors.Surface,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.clickable { outboxFilter = val_; loadOutbox(status = val_) }
                        ) {
                            Text(label, color = if (sel) Color.Black else AjiriwaColors.TextSecondary,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                }

                // Retry All Expired banner
                val expiredCount = outboxMessages.count { it.optString("status").uppercase() in listOf("EXPIRED", "FAILED") }
                if (expiredCount > 0 && outboxFilter.isBlank()) {
                    Surface(
                        color = Color(0xFFE65100),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "⚠️ $expiredCount message${if (expiredCount > 1) "s" else ""} expired/failed",
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { retryAllExpired() },
                                enabled = !retryAllLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                if (retryAllLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFFE65100))
                                } else {
                                    Text("↻ Retry All", color = Color(0xFFE65100), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                when {
                    outboxLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AjiriwaColors.Primary)
                    }
                    outboxMessages.isEmpty() -> EmptyState(Icons.Filled.Email, "No outbound SMS", "Server-triggered messages will appear here.")
                    else -> Column(Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(outboxMessages) { msg ->
                                val smsId = msg.optString("id")
                                val recipient = msg.optString("recipient_phone", "Unknown")
                                val text = msg.optString("message_text", "")
                                val status = msg.optString("status", "PENDING")
                                val triggeredBy = msg.optString("triggered_by", "").ifBlank { "System" }
                                val ts = msg.optString("created_at", "").take(16).replace("T", " ")
                                val sched = msg.optString("scheduled_at", "")
                                val statusColor = when (status.uppercase()) {
                                    "SENT" -> Color(0xFF00C853)
                                    "FAILED" -> Color(0xFFB71C1C)
                                    "EXPIRED" -> Color(0xFFE65100)
                                    "PROCESSING" -> Color(0xFF1565C0)
                                    else -> Color(0xFFFF8F00) // PENDING
                                }
                                SectionCard {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(recipient, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Surface(color = statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(99.dp)) {
                                                Text(status, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                            }
                                        }
                                        Text(text, color = AjiriwaColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column {
                                                Text("Sent by: $triggeredBy • $ts", color = Color.Gray, fontSize = 10.sp)
                                                if (sched.isNotBlank()) {
                                                    Text("Scheduled: $sched", color = AjiriwaColors.Primary, fontSize = 10.sp)
                                                }
                                            }
                                            if (status.uppercase() in listOf("FAILED", "EXPIRED")) {
                                                TextButton(onClick = { retrySms(smsId) }) {
                                                    Text("↻ Retry", color = AjiriwaColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (outboxTotalPages > 1) {
                            PaginationBar(
                                page = outboxPage,
                                totalPages = outboxTotalPages,
                                onPrev = { if (outboxPage > 1) { outboxPage--; loadOutbox(outboxPage) } },
                                onNext = { if (outboxPage < outboxTotalPages) { outboxPage++; loadOutbox(outboxPage) } }
                            )
                        }
                    }
                }
            }
            "DEVICE" -> {
                if (deviceSmsLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AjiriwaColors.Primary) }
                } else if (deviceSms.isEmpty()) {
                    EmptyState(Icons.Filled.Email, "No local SMS", "SMS directly from this phone's internal storage.")
                } else {
                    val groupedDeviceSms = deviceSms.groupBy { it.optString("sender") }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(groupedDeviceSms.keys.toList()) { sender ->
                            val messagesForSender = groupedDeviceSms[sender] ?: emptyList()
                            val latestSms = messagesForSender.firstOrNull()
                            val body = latestSms?.optString("body") ?: ""
                            val date = latestSms?.optLong("date") ?: 0L
                            val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US).format(java.util.Date(date))
                            SectionCard(modifier = Modifier.clickable { selectedChatSender = sender }) {
                                Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(sender, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (messagesForSender.size > 1) {
                                                Surface(color = AjiriwaColors.Primary.copy(alpha = 0.1f), shape = RoundedCornerShape(99.dp)) {
                                                    Text("${messagesForSender.size}", color = AjiriwaColors.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                }
                                            }
                                            Text(dateStr, color = Color.Gray, fontSize = 10.sp)
                                        }
                                    }
                                    Text(body, color = AjiriwaColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            "CONTACTS" -> {
                if (contactsLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AjiriwaColors.Primary) }
                } else if (deviceContacts.isEmpty()) {
                    EmptyState(Icons.Filled.Email, "No contacts", "Could not find any contacts on this device.")
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(deviceContacts) { contact ->
                            SectionCard(modifier = Modifier.clickable { composePhone = contact.second; showCompose = true }) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(contact.first, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(contact.second, color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                                    }
                                    Text("💬", fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
            "CALL_LOGS" -> {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Device Call Logs", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Button(
                            onClick = { syncCallLogsToServer() },
                            enabled = !isSyncingCallLogs && deviceCallLogs.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Primary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            if (isSyncingCallLogs) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.Black)
                            } else {
                                Text("☁️ Sync to Server", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (callLogsLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AjiriwaColors.Primary) }
                    } else if (deviceCallLogs.isEmpty()) {
                        EmptyState(Icons.Filled.Phone, "No call logs", "Calls from this phone will appear here and link with customer chats.")
                    } else {
                        val itemsPerPage = 25
                        val filteredCallLogs = remember(deviceCallLogs, dateFilter) {
                            val minTime = when (dateFilter) {
                                "TODAY" -> System.currentTimeMillis() - 86400000L
                                "7DAYS" -> System.currentTimeMillis() - 7 * 86400000L
                                "30DAYS" -> System.currentTimeMillis() - 30 * 86400000L
                                else -> 0L
                            }
                            if (minTime > 0) deviceCallLogs.filter { it.optLong("date") >= minTime } else deviceCallLogs
                        }
                        val totalCallPages = maxOf(1, (filteredCallLogs.size + itemsPerPage - 1) / itemsPerPage)
                        val pagedLogs = filteredCallLogs.drop((callLogsPage - 1) * itemsPerPage).take(itemsPerPage)

                        Column(Modifier.weight(1f)) {
                            LazyColumn(
                                Modifier.weight(1f).padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(pagedLogs) { call ->
                                    val num = call.optString("caller_number")
                                    val name = call.optString("caller_name").ifBlank { num }
                                    val callType = call.optString("call_type", "INCOMING").uppercase()
                                    val dur = call.optInt("duration_seconds", 0)
                                    val dateLong = call.optLong("date", 0L)
                                    val dateStr = if (dateLong > 0) java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US).format(java.util.Date(dateLong)) else ""
                                    val durStr = if (dur > 60) "${dur / 60}m ${dur % 60}s" else if (dur > 0) "${dur}s" else ""

                                    val (badgeColor, typeBadge) = when (callType) {
                                        "INCOMING" -> Color(0xFF00C853) to "📥 Incoming"
                                        "OUTGOING" -> Color(0xFF29B6F6) to "📤 Outgoing"
                                        "MISSED" -> Color(0xFFEF5350) to "❌ Missed"
                                        "REJECTED" -> Color(0xFFFF9800) to "🚫 Rejected"
                                        else -> AjiriwaColors.Primary to callType
                                    }

                                    SectionCard(modifier = Modifier.clickable { selectedChatSender = num }) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(name, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Surface(color = badgeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                                        Text(typeBadge, color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                    }
                                                }
                                                Text("$num ${if (durStr.isNotBlank()) "• $durStr" else ""}", color = AjiriwaColors.TextSecondary, fontSize = 11.sp)
                                            }
                                            Text(dateStr, color = Color.Gray, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            if (totalCallPages > 1) {
                                PaginationBar(
                                    page = callLogsPage,
                                    totalPages = totalCallPages,
                                    onPrev = { if (callLogsPage > 1) callLogsPage-- },
                                    onNext = { if (callLogsPage < totalCallPages) callLogsPage++ }
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
fun PaginationBar(
    page: Int,
    totalPages: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev, enabled = page > 1) {
            Text("◀", color = if (page > 1) AjiriwaColors.Primary else Color.Gray, fontSize = 14.sp)
        }
        Text(
            "Page $page of $totalPages",
            color = AjiriwaColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        IconButton(onClick = onNext, enabled = page < totalPages) {
            Text("▶", color = if (page < totalPages) AjiriwaColors.Primary else Color.Gray, fontSize = 14.sp)
        }
    }
}
