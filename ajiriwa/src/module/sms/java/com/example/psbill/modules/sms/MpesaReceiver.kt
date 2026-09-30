package com.example.psbill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

class MpesaReceiver : BroadcastReceiver() {
    private val TAG = "MpesaReceiver"
    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.originatingAddress ?: continue
                val body = sms.messageBody ?: continue
                
                Log.d(TAG, "Received SMS from $sender: $body")
                
                // Check if the sender is M-Pesa (often matches 'MPESA' or mobile numbers in testing)
                if (sender.contains("MPESA", ignoreCase = true) || body.contains("MPesa", ignoreCase = true)) {
                    parseAndForwardMpesa(context, body)
                }
            }
        }
    }

    private fun parseAndForwardMpesa(context: Context, body: String) {
        try {
            // Regex to parse M-Pesa format:
            // "JK12345678 Confirmed. Ksh100.00 received from Gidraf Orenja 254712345678..."
            val pattern = Pattern.compile("([A-Z0-9]{10})\\s+Confirmed\\.\\s+Ksh([0-9\\.,]+)\\s+received\\s+from\\s+.*?\\s+([0-9]+)")
            val matcher = pattern.matcher(body)
            
            if (matcher.find()) {
                val txId = matcher.group(1) ?: return
                val amountStr = matcher.group(2)?.replace(",", "") ?: "0.0"
                val amount = amountStr.toDoubleOrNull() ?: 0.0
                val phone = matcher.group(3) ?: return
                
                Log.d(TAG, "Parsed M-Pesa: Tx=$txId, Amt=$amount, Phone=$phone")
                
                // Retrieve server from preferences
                val prefs = context.getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
                val serverDomain = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
                
                val url = "https://$serverDomain/api/v1/kiosk/payments/mpesa"
                val payload = JSONObject().apply {
                    put("transaction_id", txId)
                    put("amount", amount)
                    put("sender_phone", phone)
                }
                
                val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()
                    
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Failed to forward M-Pesa: ${e.message}")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        Log.d(TAG, "M-Pesa forwarded successfully: ${response.code}")
                        response.close()
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing error: ${e.message}")
        }
    }
}
