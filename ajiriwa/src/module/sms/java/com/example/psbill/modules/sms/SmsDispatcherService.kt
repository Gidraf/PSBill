package com.example.psbill

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SmsDispatcherService — Ajiriwa SMS Engine background service.
 *
 * Delivery contract with CVPAP (see app/services/sms_queue.py):
 *  • The server hands out each SMS exactly once (atomic claim) over the
 *    gateway WebSocket and/or the /sms/pending poll.
 *  • The phone reports the REAL radio result (SENT / FAILED_<reason>) through
 *    the durable [GatewayOutbox]; unreported claims are re-queued by the server
 *    with backoff, and failures are retried a bounded number of times there.
 *  • The phone never resends on its own; an SMS id it already handed to the
 *    radio is never sent twice (its last result is re-reported instead).
 */
class SmsDispatcherService : Service() {

    private val TAG = "AjiriwaSmsSvc"

    companion object {
        const val CHANNEL_ID = "ajiriwa_sms_channel"
        private const val SMS_SENT_ACTION = "com.example.psbill.SMS_SENT"
        private const val HEARTBEAT_MS = 15_000L
        private const val SEND_SPACING_MS = 1_500L

        // Memory buffer to prevent loops (incoming SMS that we just sent)
        // Key: normalized phone + body, Value: timestamp
        private val recentlyDispatchedHashes = Collections.synchronizedMap(mutableMapOf<String, Long>())

        private fun loopKey(phone: String, body: String): String =
            "${phone.replace("+", "").replace(" ", "").takeLast(9)}|${body.trim()}"

        fun wasRecentlyDispatched(phone: String, body: String): Boolean {
            val timestamp = recentlyDispatchedHashes[loopKey(phone, body)] ?: return false
            return (System.currentTimeMillis() - timestamp) < 300_000
        }

        fun markAsDispatched(phone: String, body: String) {
            recentlyDispatchedHashes[loopKey(phone, body)] = System.currentTimeMillis()
            if (recentlyDispatchedHashes.size > 200) {
                val now = System.currentTimeMillis()
                synchronized(recentlyDispatchedHashes) {
                    recentlyDispatchedHashes.entries.removeAll { now - it.value > 600_000 }
                }
            }
        }

        /** Stable id for an SMS on this phone, used by the server to de-duplicate. */
        fun clientRef(sender: String, body: String, timestampMs: Long): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("$sender|${body.trim()}|${timestampMs / 1000}".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS) // detect half-dead sockets (NAT / carrier drops)
        .build()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private var serverDomain = "api.ajiriwa.gidraf.dev"
    private var partnerId = ""
    private var authToken = ""

    // Separate handlers: reconnect scheduling must never cancel the heartbeat.
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private var heartbeatTick = 0
    @Volatile private var wsConnected = false

    /** One SMS at a time with spacing, so bursts don't trip carrier/OS limits. */
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val io = Executors.newFixedThreadPool(2)

    private val smsPartsOk = Collections.synchronizedMap(mutableMapOf<String, MutableSet<Int>>())
    private val smsPartFailed = Collections.synchronizedMap(mutableMapOf<String, String>())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE).edit()
                    .putLong("sms_service_last_seen", System.currentTimeMillis()).apply()
                loadPrefsAndConnect()
                io.execute { GatewayOutbox.flush(applicationContext) }
                // The WebSocket push is primary; polling is the safety net
                // (every tick while disconnected, every minute when connected).
                if (!wsConnected || heartbeatTick % 4 == 0) fetchPendingSms()
                if (heartbeatTick % 20 == 0) uploadRecentCallLogs()
                heartbeatTick++
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            } finally {
                heartbeatHandler.postDelayed(this, HEARTBEAT_MS)
            }
        }
    }

    // ── Delivery results from the radio ──────────────────────────────────────
    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val smsId = intent.getStringExtra("sms_id") ?: return
            val partIndex = intent.getIntExtra("part_index", 0)
            val partCount = intent.getIntExtra("part_count", 1)
            val recipient = intent.getStringExtra("recipient") ?: ""
            val body = intent.getStringExtra("body") ?: ""

            if (resultCode != Activity.RESULT_OK) {
                val reason = when (resultCode) {
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "FAILED_GENERIC"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "FAILED_NO_SERVICE"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "FAILED_NULL_PDU"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "FAILED_RADIO_OFF"
                    else -> "FAILED_CODE_$resultCode"
                }
                smsPartFailed[smsId] = reason
                Log.e(TAG, "Part $partIndex/$partCount FAILED for $smsId ($reason)")
            } else {
                smsPartsOk.getOrPut(smsId) { Collections.synchronizedSet(mutableSetOf()) }.add(partIndex)
            }

            val okCount = smsPartsOk[smsId]?.size ?: 0
            val failed = smsPartFailed[smsId]
            if (failed == null && okCount < partCount) return
            if (!SmsLedger.finalize(smsId)) return // late part of an SMS already reported

            // All parts reported (or one failed): final result for this SMS.
            smsPartsOk.remove(smsId)
            smsPartFailed.remove(smsId)
            if (failed != null) {
                SmsLedger.setResult(this@SmsDispatcherService, smsId, failed)
                reportSmsStatus(smsId, "FAILED", failed)
            } else {
                saveSentSmsToProvider(recipient, body)
                SmsLedger.setResult(this@SmsDispatcherService, smsId, "SENT")
                reportSmsStatus(smsId, "SENT", null)
                Log.d(TAG, "SMS $smsId SENT ($partCount part(s))")
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(2, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service safely: ${e.message}")
        }

        // Sent-results arrive via our own PendingIntents, so the receiver does
        // not need to be reachable by other apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION))
        }

        try {
            contentResolver.registerContentObserver(
                android.provider.CallLog.Calls.CONTENT_URI, true,
                object : ContentObserver(heartbeatHandler) {
                    private var pending = false
                    override fun onChange(selfChange: Boolean) {
                        if (pending) return
                        pending = true
                        // debounce bursts of call-log writes
                        heartbeatHandler.postDelayed({ pending = false; uploadRecentCallLogs() }, 10_000)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register CallLog observer: ${e.message}")
        }

        loadPrefsAndConnect(force = true)
        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadPrefsAndConnect()
        fetchPendingSms()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        try { webSocket?.close(1000, "Service destroyed") } catch (_: Exception) {}
        webSocket = null
        try { unregisterReceiver(smsSentReceiver) } catch (_: Exception) {}
        sendExecutor.shutdownNow()
        io.shutdownNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Connection ───────────────────────────────────────────────────────────
    private fun loadPrefsAndConnect(force: Boolean = false) {
        val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
        val newServer = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val newPartner = prefs.getString("partner_id", "") ?: ""
        val newToken = prefs.getString("auth_token", "") ?: ""

        val changed = newServer != serverDomain || newPartner != partnerId || newToken != authToken
        serverDomain = newServer
        partnerId = newPartner
        authToken = newToken

        if (partnerId.isBlank() || authToken.isBlank()) {
            // Signed out: nothing to serve.
            if (webSocket != null) {
                val old = webSocket
                webSocket = null
                try { old?.close(1000, "Signed out") } catch (_: Exception) {}
            }
            updateServiceStatus(false, "Not signed in")
            return
        }
        if (changed || force || webSocket == null) {
            val old = webSocket
            webSocket = null
            try { old?.close(1000, "Config changed") } catch (_: Exception) {}
            connectWebSocket()
        }
    }

    private fun connectWebSocket() {
        reconnectHandler.removeCallbacksAndMessages(null)
        val wsUrl = "wss://$serverDomain/kiosk/ws/mobile/$partnerId/gateway"
        Log.d(TAG, "SMS Engine WS connecting: $wsUrl")
        updateServiceStatus(false)

        val request = Request.Builder().url(wsUrl)
            .addHeader("Authorization", "Bearer $authToken")
            .build()

        lateinit var socket: WebSocket
        socket = client.newWebSocket(request, object : WebSocketListener() {
            // Events from a socket we already replaced are ignored, so an old
            // socket closing can never tear down or double the new one.
            private fun isCurrent() = webSocket === socket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) return
                Log.d(TAG, "SMS Engine WS connected")
                wsConnected = true
                reconnectAttempts = 0
                updateServiceStatus(true, "Connected")
                io.execute { GatewayOutbox.flush(applicationContext) }
                uploadContacts()
                uploadRecentSms()
                uploadRecentCallLogs()
                fetchPendingSms()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent()) return
                handleSocketMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent()) return
                wsConnected = false
                updateServiceStatus(false, "Closed: $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent()) return
                wsConnected = false
                val errorMsg = response?.message?.takeIf { it.isNotBlank() } ?: t.message ?: "Unknown error"
                Log.e(TAG, "WS Failure: $errorMsg")
                updateServiceStatus(false, "Error: $errorMsg")
                scheduleReconnect()
            }
        })
        webSocket = socket
    }

    private fun scheduleReconnect() {
        val delay = minOf(60_000L, 2_000L shl minOf(reconnectAttempts, 5))
        reconnectAttempts++
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            webSocket = null
            loadPrefsAndConnect(force = true)
        }, delay)
    }

    private fun updateServiceStatus(connected: Boolean, lastError: String = "") {
        getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE).edit().apply {
            putBoolean("sms_service_connected", connected)
            putString("sms_service_error", lastError)
            putLong("sms_service_last_seen", System.currentTimeMillis())
            apply()
        }
        sendBroadcast(Intent("com.example.psbill.SMS_ENGINE_STATUS").apply {
            setPackage(packageName)
            putExtra("connected", connected)
            putExtra("error", lastError)
            putExtra("timestamp", System.currentTimeMillis())
        })
    }

    private fun handleSocketMessage(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val data = json.optJSONObject("data")
            when (json.optString("event")) {
                "send_sms" -> if (data != null) {
                    val smsId = data.optString("id")
                    val recipient = data.optString("recipient_phone")
                    val text = data.optString("message_text")
                    if (smsId.isNotBlank() && recipient.isNotBlank() && text.isNotBlank()) {
                        sendSms(smsId, recipient, text)
                    } else {
                        // "wake-up" style event without a payload: go fetch
                        fetchPendingSms()
                    }
                }
                "fetch_contacts" -> uploadContacts()
                "fetch_sms" -> uploadRecentSms()
                "fetch_call_logs" -> uploadRecentCallLogs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WS message parse error: ${e.message}")
        }
    }

    // ── Outbound ─────────────────────────────────────────────────────────────
    private fun fetchPendingSms() {
        if (partnerId.isBlank() || authToken.isBlank()) return
        val req = Request.Builder()
            .url("https://$serverDomain/api/v1/kiosk/sms/pending?partner_id=$partnerId")
            .get()
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("X-Partner-Id", partnerId)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Pending SMS poll failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                // Never let a bad payload crash the OkHttp thread (and the app).
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) return
                        val bodyStr = resp.body?.string().orEmpty()
                        val arr = parseItems(bodyStr)
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val smsId = item.optString("id")
                            val recipient = item.optString("recipient_phone")
                            val text = item.optString("message_text")
                            if (smsId.isNotBlank() && recipient.isNotBlank() && text.isNotBlank()) {
                                sendSms(smsId, recipient, text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Pending SMS parse error: ${e.message}")
                }
            }
        })
    }

    private fun parseItems(body: String): JSONArray = try {
        if (body.trimStart().startsWith("[")) JSONArray(body)
        else JSONObject(body).optJSONArray("items") ?: JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    /**
     * Hand an SMS to the radio at most once per id. If the server offers an id
     * we already handled (e.g. our report got lost), just repeat the result.
     */
    private fun sendSms(smsId: String, recipient: String, text: String) {
        when (val known = SmsLedger.state(this, smsId)) {
            null -> Unit
            SmsLedger.DISPATCHED -> {
                // Handed to the radio but the result never came back (process
                // death). Do not risk a double send; tell the server it went out.
                reportSmsStatus(smsId, "SENT", "result lost after dispatch")
                return
            }
            "SENT" -> {
                reportSmsStatus(smsId, "SENT", null)
                return
            }
            else -> Unit // FAILED_* earlier: the server is retrying it — send again
        }
        if (!SmsLedger.markQueued(this, smsId)) return // already queued in this process

        sendExecutor.execute {
            try {
                val smsManager = resolveSmsManager()
                val parts = smsManager.divideMessage(text)
                markAsDispatched(recipient, text)
                val intents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    intents.add(
                        PendingIntent.getBroadcast(
                            this, "$smsId#$i".hashCode(),
                            Intent(SMS_SENT_ACTION).apply {
                                setPackage(packageName)
                                putExtra("sms_id", smsId)
                                putExtra("part_index", i)
                                putExtra("part_count", parts.size)
                                putExtra("recipient", recipient)
                                putExtra("body", text)
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                SmsLedger.setResult(this, smsId, SmsLedger.DISPATCHED)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(recipient, null, parts, intents, null)
                } else {
                    smsManager.sendTextMessage(recipient, null, text, intents[0], null)
                }
                Log.d(TAG, "SMS $smsId handed to radio → $recipient (${parts.size} part(s))")
                markAsRepliedLocally(recipient)
            } catch (e: Exception) {
                Log.e(TAG, "SmsManager error for $smsId: ${e.message}", e)
                SmsLedger.finalize(smsId)
                SmsLedger.setResult(this, smsId, "FAILED_EXCEPTION")
                reportSmsStatus(smsId, "FAILED", "FAILED_EXCEPTION: ${e.message}")
            }
            try { Thread.sleep(SEND_SPACING_MS) } catch (_: InterruptedException) {}
        }
    }

    private fun resolveSmsManager(): SmsManager {
        val prefs = getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
        val preferredSubId = prefs.getInt("preferred_sim_sub_id", -1)
        val defaultSmsSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
        } else -1
        val subId = if (preferredSubId != -1) preferredSubId else defaultSmsSubId
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sm = applicationContext.getSystemService(SmsManager::class.java)
            if (subId > 0) sm.createForSubscriptionId(subId) else sm
        } else if (subId > 0) {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun reportSmsStatus(smsId: String, status: String, error: String?) {
        val payload = JSONObject().apply {
            put("status", status)
            if (error != null) put("error", error)
            if (partnerId.isNotBlank()) put("partner_id", partnerId)
        }
        GatewayOutbox.enqueueAndFlush(this, "/api/v1/kiosk/sms/$smsId/status", payload, dedupeKey = "status:$smsId")
    }

    private fun saveSentSmsToProvider(recipient: String, body: String) {
        if (recipient.isBlank()) return
        try {
            val now = System.currentTimeMillis()
            val values = android.content.ContentValues().apply {
                put("address", recipient)
                put("body", body)
                put("date", now)
                put("date_sent", now)
                put("read", 1)
                put("type", 2) // Sent
                put("status", -1)
            }
            try {
                val threadId = android.provider.Telephony.Threads.getOrCreateThreadId(this, recipient)
                if (threadId > 0) values.put("thread_id", threadId)
            } catch (_: Exception) {}
            if (contentResolver.insert(Uri.parse("content://sms/sent"), values) != null) {
                contentResolver.notifyChange(Uri.parse("content://sms"), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sent SMS: ${e.message}")
        }
    }

    private fun markAsRepliedLocally(recipient: String) {
        try {
            val values = android.content.ContentValues().apply {
                put("read", 1)
                put("seen", 1)
            }
            val count = contentResolver.update(
                Uri.parse("content://sms/inbox"), values, "address = ? AND read = 0", arrayOf(recipient)
            )
            if (count > 0) contentResolver.notifyChange(Uri.parse("content://sms"), null)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking as replied: ${e.message}")
        }
    }

    // ── Phone → server uploads (all idempotent server-side) ──────────────────
    private fun postJson(path: String, payload: JSONObject) {
        if (partnerId.isBlank() || authToken.isBlank()) return
        val req = Request.Builder()
            .url("https://$serverDomain$path")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("X-Partner-Id", partnerId)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Upload $path failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun uploadContacts() = io.execute {
        val contacts = JSONArray()
        try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null
            )?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    contacts.put(JSONObject().apply {
                        put("name", if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "")
                        put("phone", if (numIdx >= 0) c.getString(numIdx) ?: "" else "")
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact fetch error: ${e.message}")
        }
        if (contacts.length() > 0) {
            postJson("/api/v1/kiosk/gateway/contacts", JSONObject().put("contacts", contacts).put("partner_id", partnerId))
        }
    }

    private fun uploadRecentSms() = io.execute {
        val messages = JSONArray()
        try {
            contentResolver.query(
                Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC"
            )?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val bodyIdx = c.getColumnIndex("body")
                val dateIdx = c.getColumnIndex("date")
                var count = 0
                while (c.moveToNext() && count < 100) {
                    val sender = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else ""
                    val ts = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    messages.put(JSONObject().apply {
                        put("sender", sender)
                        put("body", body)
                        put("timestamp", ts)
                        put("client_ref", clientRef(sender, body, ts))
                    })
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS history read error: ${e.message}")
        }
        if (messages.length() > 0) {
            postJson("/api/v1/kiosk/gateway/sms-history", JSONObject().put("messages", messages).put("partner_id", partnerId))
        }
    }

    private fun uploadRecentCallLogs() = io.execute {
        val logsArr = JSONArray()
        try {
            contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.CACHED_NAME,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DURATION,
                    android.provider.CallLog.Calls.DATE
                ), null, null, android.provider.CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                val numIdx = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val durIdx = c.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                var count = 0
                while (c.moveToNext() && count < 100) {
                    val num = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                    if (num.isNotBlank()) {
                        logsArr.put(JSONObject().apply {
                            put("caller_number", num)
                            put("caller_name", if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "")
                            put("call_type", when (if (typeIdx >= 0) c.getInt(typeIdx) else 1) {
                                android.provider.CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                                android.provider.CallLog.Calls.MISSED_TYPE -> "MISSED"
                                android.provider.CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                                else -> "INCOMING"
                            })
                            put("duration_seconds", if (durIdx >= 0) c.getInt(durIdx) else 0)
                            put("timestamp", if (dateIdx >= 0) c.getLong(dateIdx) else 0L)
                        })
                    }
                    count++
                }
            }
        } catch (e: SecurityException) {
            // READ_CALL_LOG not granted — nothing to sync
        } catch (e: Exception) {
            Log.e(TAG, "Call log fetch error: ${e.message}")
        }
        if (logsArr.length() > 0) {
            postJson("/api/v1/kiosk/gateway/call-logs", JSONObject().put("call_logs", logsArr).put("partner_id", partnerId))
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ajiriwa SMS Engine", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ajiriwa SMS Engine — Active")
            .setContentText("Listening for orders, promotions & notifications...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
}

/**
 * Persistent record of what happened to each server SMS id on this phone, so a
 * re-offered id is never sent twice (survives process death). Bounded LRU.
 */
object SmsLedger {
    const val DISPATCHED = "DISPATCHED"
    private const val PREFS = "SmsLedger"
    private const val KEY = "entries"
    private const val MAX = 2000
    private val lock = Any()
    private var cache: LinkedHashMap<String, String>? = null

    private fun map(context: Context): LinkedHashMap<String, String> {
        cache?.let { return it }
        val m = LinkedHashMap<String, String>()
        try {
            val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                m[o.optString("id")] = o.optString("s")
            }
        } catch (_: Exception) {}
        cache = m
        return m
    }

    private fun persist(context: Context, m: LinkedHashMap<String, String>) {
        while (m.size > MAX) m.remove(m.keys.first())
        val arr = JSONArray()
        m.forEach { (id, s) -> arr.put(JSONObject().put("id", id).put("s", s)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Last known state: null (never handed to the radio), DISPATCHED, SENT or FAILED_*. */
    fun state(context: Context, id: String): String? = synchronized(lock) {
        map(context)[id]
    }

    // In-process only: an id queued before a crash (never dispatched) may be sent again.
    private val inFlight = mutableSetOf<String>()
    private val finalized = LinkedHashSet<String>()

    /** True if this call queued the id (false when it is already queued in this process). */
    fun markQueued(context: Context, id: String): Boolean = synchronized(lock) {
        if (!inFlight.add(id)) return false
        finalized.remove(id) // a retry gets a fresh result
        true
    }

    /** First final result for an id wins; later parts are ignored. */
    fun finalize(id: String): Boolean = synchronized(lock) {
        inFlight.remove(id)
        if (!finalized.add(id)) return false
        while (finalized.size > 500) finalized.remove(finalized.first())
        true
    }

    fun setResult(context: Context, id: String, state: String) = synchronized(lock) {
        val m = map(context)
        m.remove(id)
        m[id] = state
        persist(context, m)
    }
}
