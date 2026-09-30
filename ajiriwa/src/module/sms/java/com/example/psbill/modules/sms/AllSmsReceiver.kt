package com.example.psbill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.Collections

/**
 * AllSmsReceiver — The Ajiriwa SMS Engine.
 *
 * Receives EVERY incoming SMS on the device (not just M-Pesa) and:
 * 1. Syncs all messages to POST /api/v1/kiosk/sms/inbox for full server-side inbox history
 * 2. Parses M-Pesa messages and also posts to /api/v1/kiosk/payments/mpesa
 * 3. Fires proximity SMS to nearby-order customers when location match detected
 */
class AllSmsReceiver : BroadcastReceiver() {

    private val TAG = "AllSmsReceiver"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    companion object {
        // Prevent syncing the same message twice in quick succession
        private val recentlySyncedHashes = Collections.synchronizedSet(mutableSetOf<String>())
        
        fun wasRecentlySynced(sender: String, body: String): Boolean {
            val hash = "$sender|${body.trim()}"
            return recentlySyncedHashes.contains(hash)
        }
        
        fun markAsSynced(sender: String, body: String) {
            val hash = "$sender|${body.trim()}"
            recentlySyncedHashes.add(hash)
            // Cleanup after 2 minutes
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                recentlySyncedHashes.remove(hash)
            }, 120_000)
        }
    }

    private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // M-Pesa message pattern: "JK12345678 Confirmed. Ksh200.00 received from NAME 254712345678..."
    private val MPESA_PATTERN = Pattern.compile(
        "([A-Z0-9]{10})\\s+Confirmed\\.\\s+Ksh([0-9\\.,]+)\\s+received\\s+from\\s+.*?\\s+([0-9]+)"
    )

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val prefs = context.getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
        val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val partnerId = prefs.getString("partner_id", "") ?: ""
        val authToken = prefs.getString("auth_token", "") ?: ""

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val now = ISO_FORMAT.format(Date())

        // ── Reassemble multipart SMS ──────────────────────────────────────────
        // Android delivers long SMS as multiple SmsMessage objects in ONE intent.
        // We group all parts by originating address so that the full message body
        // is evaluated together, preventing "half-replied" detection.
        data class SmsEntry(val sender: String, val body: String, val timestampMillis: Long)
        val grouped = LinkedHashMap<String, SmsEntry>()
        for (sms in messages) {
            val sender = sms.originatingAddress ?: continue
            val part  = sms.messageBody ?: continue
            val existing = grouped[sender]
            if (existing != null) {
                grouped[sender] = existing.copy(body = existing.body + part)
            } else {
                grouped[sender] = SmsEntry(sender, part, sms.timestampMillis)
            }
        }

        for ((_, entry) in grouped) {
            val sender = entry.sender
            val body   = entry.body

            // 1. Loop Prevention: Check if we JUST sent this message ourselves
            if (SmsDispatcherService.wasRecentlyDispatched(sender, body)) {
                Log.d(TAG, "Loop detected (Memory): Ignoring message sent by this device to $sender")
                continue
            }
            if (isRecentlySentByUs(context, sender, body)) {
                Log.d(TAG, "Loop detected (DB): Ignoring message sent by this device to $sender")
                continue
            }

            // 2. Deduplication: Don't sync the same message twice
            if (wasRecentlySynced(sender, body)) {
                Log.d(TAG, "Deduplication: Already synced message from $sender")
                continue
            }
            markAsSynced(sender, body)

            Log.d(TAG, "Received SMS from $sender: ${body.take(80)}...")

            // Check for Remote Commands (e.g., #CMD: STATUS)
            if (body.startsWith("#CMD:", ignoreCase = true)) {
                handleRemoteCommand(context, sender, body.substring(5).trim())
            }

            val isMpesa = sender.contains("MPESA", ignoreCase = true) ||
                          body.contains("Confirmed", ignoreCase = false) && body.contains("Ksh")

            // Parse M-Pesa details if applicable
            var mpesaAmount: Double? = null
            var mpesaTxid: String? = null
            if (isMpesa) {
                val matcher = MPESA_PATTERN.matcher(body)
                if (matcher.find()) {
                    mpesaTxid   = matcher.group(1)
                    mpesaAmount = matcher.group(2)?.replace(",", "")?.toDoubleOrNull()
                    val phone   = matcher.group(3)
                    // Also post to kiosk payments endpoint to auto-unlock kiosk sessions
                    if (mpesaTxid != null && mpesaAmount != null && phone != null) {
                        postMpesaPayment(context, mpesaTxid!!, mpesaAmount!!, phone)
                    }
                }
            }

            // Sync FULL reassembled SMS to server inbox (durable: survives offline/restarts)
            syncToInbox(
                context      = context,
                partnerId    = partnerId,
                sender       = sender,
                body         = body,
                timestampMs  = entry.timestampMillis,
                isMpesa      = isMpesa,
                mpesaAmount  = mpesaAmount,
                mpesaTxid    = mpesaTxid
            )

            // Save full reassembled message to local SMS provider
            saveIncomingSmsToProvider(context, sender, body, entry.timestampMillis)

            // Show Notification
            showNotification(context, sender, body)
        }
    }

    private fun showNotification(context: Context, sender: String, body: String) {
        val channelId = "sms_notifications"
        val notificationId = System.currentTimeMillis().toInt()

        // Create Notification Channel (required for Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SMS Notifications"
            val descriptionText = "Notifications for new incoming messages"
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Fallback to app icon
            .setContentTitle("New SMS from $sender")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                // Check for permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notify(notificationId, builder.build())
                    } else {
                        Log.w(TAG, "Notification permission not granted")
                    }
                } else {
                    notify(notificationId, builder.build())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification: ${e.message}")
        }
    }

    private fun saveIncomingSmsToProvider(context: Context, sender: String, body: String, timestamp: Long) {
        try {
            val values = android.content.ContentValues().apply {
                put("address", sender)
                put("body", body)
                put("date", timestamp)
                put("date_sent", timestamp)
                put("read", 0)
                put("type", 1) // MESSAGE_TYPE_INBOX
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val threadId = android.provider.Telephony.Threads.getOrCreateThreadId(context, sender)
                    if (threadId > 0) values.put("thread_id", threadId)
                } catch (e: Exception) {}
            }
            val uri = context.contentResolver.insert(Uri.parse("content://sms/inbox"), values)
            if (uri != null) {
                Log.d(TAG, "Incoming SMS saved to local provider: $uri")
                context.contentResolver.notifyChange(Uri.parse("content://sms"), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving incoming SMS: ${e.message}")
        }
    }

    private fun handleRemoteCommand(context: Context, sender: String, command: String) {
        Log.d(TAG, "Executing Remote Command: $command from $sender")
        when (command.uppercase()) {
            "STATUS" -> {
                sendReply(context, sender, "Ajiriwa SMS Gateway: ONLINE. Service active.")
            }
            "PING" -> {
                sendReply(context, sender, "PONG! Gateway is reachable.")
            }
        }
    }

    private fun sendReply(context: Context, recipient: String, text: String) {
        try {
            SmsDispatcherService.markAsDispatched(recipient, text)
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, text, null, null)
            }
            saveSentSmsToProvider(context, recipient, text)
        } catch (e: Exception) {
            Log.e(TAG, "Reply failed: ${e.message}")
        }
    }

    private fun saveSentSmsToProvider(context: Context, recipient: String, body: String) {
        try {
            val now = System.currentTimeMillis()
            val values = android.content.ContentValues().apply {
                put("address", recipient)
                put("body", body)
                put("date", now)
                put("date_sent", now)
                put("read", 1)
                put("type", 2) // MESSAGE_TYPE_SENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val threadId = android.provider.Telephony.Threads.getOrCreateThreadId(context, recipient)
                    if (threadId > 0) values.put("thread_id", threadId)
                } catch (e: Exception) {}
            }
            val uri = context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
            if (uri != null) {
                Log.d(TAG, "Reply saved to sent provider: $uri")
                context.contentResolver.notifyChange(Uri.parse("content://sms"), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving reply to provider: ${e.message}")
        }
    }

    private fun isRecentlySentByUs(context: Context, address: String, body: String): Boolean {
        try {
            val now = System.currentTimeMillis()
            // Check if there's a sent message in the last 60 seconds with same body/address
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/sent"),
                arrayOf("body"),
                "address = ? AND date > ?",
                arrayOf(address, (now - 60000).toString()),
                "date DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val sentBody = it.getString(0) ?: ""
                    if (sentBody.trim() == body.trim()) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking self-sent: ${e.message}")
        }
        return false
    }

    private fun syncToInbox(
        context: Context,
        partnerId: String,
        sender: String,
        body: String,
        timestampMs: Long,
        isMpesa: Boolean,
        mpesaAmount: Double?,
        mpesaTxid: String?
    ) {
        val ts = if (timestampMs > 0) timestampMs else System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("sender", sender)
            put("body", body)
            put("timestamp", ISO_FORMAT.format(Date(ts)))
            put("timestamp_ms", ts)
            put("client_ref", SmsDispatcherService.clientRef(sender, body, ts))
            put("is_mpesa", isMpesa)
            // The server decides what is automated (is_system_or_automated_sms);
            // everything arriving on the SIM is inbound.
            put("direction", "INBOUND")
            if (partnerId.isNotBlank()) put("partner_id", partnerId)
            if (mpesaAmount != null) put("mpesa_amount", mpesaAmount)
            if (mpesaTxid != null) put("mpesa_txid", mpesaTxid)
        }
        GatewayOutbox.enqueueAndFlush(context, "/api/v1/kiosk/sms/inbox", payload)
    }

    private fun checkIfRepliedLocally(sender: String): Boolean {
        // Check if the latest message in this thread is a SENT message
        // This helps the server know if the mobile user already responded manually
        return false // Defaulting to false as most incoming messages are new
    }

    private fun postMpesaPayment(context: Context, txId: String, amount: Double, phone: String) {
        val payload = JSONObject().apply {
            put("transaction_id", txId)
            put("amount", amount)
            put("sender_phone", phone)
        }
        GatewayOutbox.enqueueAndFlush(context, "/api/v1/kiosk/payments/mpesa", payload, dedupeKey = "mpesa:$txId")
    }
}
