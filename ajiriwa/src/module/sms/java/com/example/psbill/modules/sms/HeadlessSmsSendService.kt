package com.example.psbill

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log

/**
 * Default SMS app: "reply with a message" when declining a call (RESPOND_VIA_MESSAGE). Sends the SMS from this phone
 * and keeps a copy in the sent box, so the conversation shows it.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (number, text) = SmsCompose.parse(intent) ?: (null to null)
        if (!number.isNullOrBlank() && !text.isNullOrBlank()) {
            runCatching {
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                val parts = sms.divideMessage(text)
                if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
                else sms.sendTextMessage(number, null, text, null, null)
                contentResolver.insert(android.net.Uri.parse("content://sms/sent"), ContentValues().apply {
                    put("address", number); put("body", text); put("date", System.currentTimeMillis()); put("read", 1); put("type", 2)
                })
                com.example.psbill.core.ActivityLog.log("sms_quick_reply", context = this)
            }.onFailure { Log.e("HeadlessSms", "send failed: ${it.message}") }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
