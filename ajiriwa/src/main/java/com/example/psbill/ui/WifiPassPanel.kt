package com.example.psbill.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val passHttp by lazy { com.example.psbill.core.ActivityLog.client }

/**
 * PlayStation WiFi: give a customer WiFi for exactly N minutes. The voucher is
 * created on the MikroTik billing (nuxbill) and sent by SMS.
 */
@Composable
fun WifiPassPanel(server: String, headers: () -> Headers, presetPhone: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = remember(server) { "https://${server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}" }
    var phone by remember { mutableStateOf(presetPhone) }
    var minutes by remember { mutableStateOf("30") }
    var sending by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        recent = runCatching {
            withContext(Dispatchers.IO) {
                passHttp.newCall(Request.Builder().url("$base/api/v1/kiosk/wifi-passes?limit=30").headers(headers()).build()).execute().use { r ->
                    val arr = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }).optJSONArray("items")
                    if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
                }
            }
        }.getOrElse { emptyList() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Give a WiFi pass", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Valid for exactly the minutes you pick, sent to the customer by SMS.", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
        OutlinedTextField(
            value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AjiriwaColors.TextPrimary, unfocusedTextColor = AjiriwaColors.TextPrimary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("15", "30", "60", "120").forEach { m ->
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
            }
            OutlinedTextField(
                value = minutes, onValueChange = { v -> minutes = v.filter { it.isDigit() }.take(3) }, singleLine = true,
                label = { Text("min") }, modifier = Modifier.width(80.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AjiriwaColors.TextPrimary, unfocusedTextColor = AjiriwaColors.TextPrimary),
            )
        }
        Button(
            enabled = !sending && phone.isNotBlank() && (minutes.toIntOrNull() ?: 0) > 0,
            colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                sending = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val body = JSONObject().put("minutes", minutes.toInt()).put("phone", phone.trim()).put("send_sms", true)
                            passHttp.newCall(
                                Request.Builder().url("$base/api/v1/kiosk/wifi-pass").headers(headers())
                                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                            ).execute().use { r -> r.code to JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }) }
                        }
                    }
                    sending = false
                    result.onSuccess { (code, json) ->
                        if (code in 200..299 && json.optString("voucher_code").isNotBlank()) {
                            Toast.makeText(context, "WiFi code ${json.optString("voucher_code")} (${json.optInt("minutes")} min) sent by SMS", Toast.LENGTH_LONG).show()
                            phone = ""
                            reloadKey++
                        } else {
                            Toast.makeText(context, json.optString("error").ifBlank { "Could not create the WiFi pass" }, Toast.LENGTH_LONG).show()
                        }
                    }.onFailure { Toast.makeText(context, it.message ?: "Network error", Toast.LENGTH_LONG).show() }
                }
            },
        ) { Text(if (sending) "Creating…" else "Create & send SMS") }

        Text("Recent passes", color = AjiriwaColors.Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(recent) { p ->
                val why = when (p.optString("reason")) {
                    "queue_wait" -> "Waiting in queue"; "session_end" -> "After session"; "portal_queue" -> "Portal queue"; else -> "Manual"
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.optString("phone", "—")} · $why", color = AjiriwaColors.TextPrimary, fontSize = 13.sp)
                        Text(p.optString("created_at").take(16).replace('T', ' '), color = AjiriwaColors.TextMuted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(p.optString("voucher_code").ifBlank { "failed" }, color = if (p.optString("voucher_code").isBlank()) AjiriwaColors.Danger else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${p.optInt("minutes")} min${if (p.optBoolean("sms_sent")) " · SMS" else ""}", color = AjiriwaColors.TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
