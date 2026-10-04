package com.example.psbill.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.psbill.ui.components.EmptyState
import com.example.psbill.ui.components.GlassCard
import com.example.psbill.ui.components.PillChip
import com.example.psbill.ui.components.SectionCard
import com.example.psbill.ui.components.SectionTitle
import com.example.psbill.ui.theme.AjiriwaColors
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private val OUTBOX_JSON_MT = "application/json; charset=utf-8".toMediaType()
private const val TAG_OUTBOX = "SmsOutboxScreen"

private val STATUS_TABS = listOf("ALL", "PENDING", "PROCESSING", "SENT", "FAILED", "EXPIRED")

private fun statusColor(status: String): Color = when (status.uppercase()) {
    "PENDING"    -> Color(0xFFF59E0B)
    "PROCESSING" -> Color(0xFF3B82F6)
    "SENT"       -> Color(0xFF22C55E)
    "FAILED"     -> Color(0xFFEF4444)
    "EXPIRED"    -> Color(0xFFF97316)
    else         -> Color(0xFF9AA0B4)
}

/**
 * SmsOutboxScreen — dedicated outbox view for server-dispatched SMS.
 *
 * Shows status filter tabs (ALL / PENDING / PROCESSING / SENT / FAILED / EXPIRED),
 * per-item Retry for FAILED/EXPIRED, a bulk "Retry All Expired" banner,
 * and a real-time connection status indicator driven by LocalBroadcast from
 * SmsDispatcherService (com.example.psbill.SMS_ENGINE_STATUS).
 *
 * Credentials are read from SharedPreferences AttenderPrefs.
 */
@Composable
fun SmsOutboxScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE) }

    val serverDomain = remember { prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev" }
    val partnerId    = remember { prefs.getString("partner_id", "") ?: "" }
    val authToken    = remember { prefs.getString("auth_token", "") ?: "" }
    val base         = remember(serverDomain) {
        "https://${serverDomain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}"
    }

    val client = remember {
        com.example.psbill.core.ActivityLog.builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Connection status — seeded from SharedPrefs, updated live via LocalBroadcast
    var isConnected  by remember { mutableStateOf(prefs.getBoolean("sms_service_connected", false)) }
    var serviceError by remember { mutableStateOf(prefs.getString("sms_service_error", "") ?: "") }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                isConnected  = intent.getBooleanExtra("connected", false)
                serviceError = intent.getStringExtra("error") ?: ""
                Log.d(TAG_OUTBOX, "SMS engine status: connected=$isConnected error=$serviceError")
            }
        }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter("com.example.psbill.SMS_ENGINE_STATUS"))
        onDispose { LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver) }
    }

    var selectedTab   by remember { mutableStateOf("ALL") }
    var messages      by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isLoading     by remember { mutableStateOf(true) }
    var loadError     by remember { mutableStateOf<String?>(null) }
    var retryingId    by remember { mutableStateOf<String?>(null) }
    var isRetryingAll by remember { mutableStateOf(false) }

    val expiredItems = remember(messages) {
        messages.filter { it.optString("status").uppercase() == "EXPIRED" }
    }

    fun buildHeaders(): Headers {
        val b = Headers.Builder()
        if (authToken.isNotBlank()) b.add("Authorization", "Bearer $authToken")
        if (partnerId.isNotBlank()) b.add("X-Partner-Id", partnerId)
        return b.build()
    }

    fun loadMessages(status: String = selectedTab) {
        isLoading = true; loadError = null
        val url = buildString {
            append("$base/api/v1/kiosk/sms/queue?limit=100")
            if (status != "ALL") append("&status=$status")
            if (partnerId.isNotBlank()) append("&partner_id=$partnerId")
        }
        client.newCall(Request.Builder().url(url).headers(buildHeaders()).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post { isLoading = false; loadError = e.message }
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    response.close()
                    mainHandler.post {
                        isLoading = false
                        runCatching {
                            val arr: JSONArray = try {
                                JSONObject(body).optJSONArray("items") ?: JSONArray()
                            } catch (e: Exception) {
                                try { JSONArray(body) } catch (e2: Exception) { JSONArray() }
                            }
                            messages = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        }.onFailure { loadError = "Parse error: ${it.message}" }
                    }
                }
            })
    }

    fun retrySingleSms(smsId: String) {
        retryingId = smsId
        client.newCall(
            Request.Builder()
                .url("$base/api/v1/kiosk/sms/$smsId/retry")
                .post("".toRequestBody(OUTBOX_JSON_MT))
                .headers(buildHeaders())
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    retryingId = null
                    Toast.makeText(context, "Retry failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful; response.close()
                mainHandler.post {
                    retryingId = null
                    Toast.makeText(
                        context,
                        if (ok) "Retry queued" else "Server error on retry",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) loadMessages()
                }
            }
        })
    }

    fun retryAllExpired() {
        isRetryingAll = true
        client.newCall(
            Request.Builder()
                .url("$base/api/v1/kiosk/sms/retry-expired")
                .post("".toRequestBody(OUTBOX_JSON_MT))
                .headers(buildHeaders())
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    isRetryingAll = false
                    Toast.makeText(context, "Bulk retry failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful; response.close()
                mainHandler.post {
                    isRetryingAll = false
                    Toast.makeText(
                        context,
                        if (ok) "All expired SMS queued for retry" else "Server error on bulk retry",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) loadMessages()
                }
            }
        })
    }

    LaunchedEffect(selectedTab) { loadMessages(selectedTab) }

    Column(modifier.fillMaxSize().background(AjiriwaColors.Canvas)) {

        // --- Connection status banner ---
        val serviceLastSeen = prefs.getLong("sms_service_last_seen", 0L)
        val isServiceAlive  = (System.currentTimeMillis() - serviceLastSeen) < 60_000

        Surface(
            color = if (isConnected && isServiceAlive) Color(0xFF1B5E20) else Color(0xFF7F0000),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected && isServiceAlive) "Connected" else "Disconnected",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    if (serviceError.isNotBlank() && (!isConnected || !isServiceAlive)) {
                        Text(serviceError, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                    }
                }
                IconButton(onClick = { loadMessages() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                }
            }
        }

        // --- Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Outbox Queue", "Real-time status of outgoing promotional messages.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (expiredItems.isNotEmpty()) {
                    Button(
                        onClick = { retryAllExpired() },
                        enabled = !isRetryingAll,
                        colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Danger),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Retry All Failed (${expiredItems.size})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = { loadMessages() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = AjiriwaColors.PrimaryAccent)
                }
            }
        }


        // --- Retry All Expired banner ---
        if (expiredItems.isNotEmpty()) {
            Surface(
                color = Color(0xFF7C2D12),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${expiredItems.size} expired SMS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = { retryAllExpired() },
                        enabled = !isRetryingAll,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        if (isRetryingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Retry All Expired",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // --- Status filter tabs ---
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(STATUS_TABS) { tab ->
                val sel = selectedTab == tab
                Surface(
                    color = if (sel) AjiriwaColors.Primary else AjiriwaColors.Surface,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { selectedTab = tab }
                ) {
                    Text(
                        tab,
                        color = if (sel) Color.Black else AjiriwaColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // --- Error banner ---
        if (loadError != null) {
            Surface(
                color = Color(0xFF7F0000),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "Error: $loadError",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // --- Empty state / list ---
        if (!isLoading && messages.isEmpty() && loadError == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No messages", fontSize = 36.sp, color = AjiriwaColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No SMS in ${if (selectedTab == "ALL") "outbox" else selectedTab.lowercase()} queue",
                        color = AjiriwaColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    SmsOutboxItem(
                        msg = msg,
                        isRetrying = retryingId == msg.optString("id"),
                        onRetry = { retrySingleSms(msg.optString("id")) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SmsOutboxItem(
    msg: JSONObject,
    isRetrying: Boolean,
    onRetry: () -> Unit
) {
    val status      = msg.optString("status", "UNKNOWN").uppercase()
    val phone       = msg.optString("recipient_phone", "-")
    val text        = msg.optString("message_text", "")
    val createdAt   = msg.optString("created_at", "")
    val triggeredBy = msg.optString("triggered_by", "")
    val canRetry    = status == "FAILED" || status == "EXPIRED"
    val chipColor   = statusColor(status)
    val displayTs   = remember(createdAt) { formatOutboxTimestamp(createdAt) }
    val preview     = if (text.length > 60) text.take(60) + "..." else text

    Surface(
        color = AjiriwaColors.Surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        phone,
                        color = AjiriwaColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Surface(color = chipColor.copy(alpha = 0.18f), shape = RoundedCornerShape(4.dp)) {
                        Text(
                            status,
                            color = chipColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (canRetry) {
                    if (isRetrying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AjiriwaColors.Primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Retry",
                                color = AjiriwaColors.Primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(preview.ifBlank { "(empty)" }, color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(displayTs, color = AjiriwaColors.TextSecondary.copy(alpha = 0.7f), fontSize = 10.sp)
                if (triggeredBy.isNotBlank()) {
                    Text("via $triggeredBy", color = AjiriwaColors.TextSecondary.copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }
        }
    }
}

/** Formats an ISO-8601 timestamp to a human-readable local string. */
private fun formatOutboxTimestamp(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        var parsed: java.util.Date? = null
        for (fmt in formats) {
            try {
                parsed = SimpleDateFormat(fmt, Locale.US).parse(raw)
                if (parsed != null) break
            } catch (_: Exception) {}
        }
        if (parsed != null) {
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(parsed)
        } else raw
    } catch (_: Exception) { raw }
}
