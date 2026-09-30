package com.example.psbill.customer

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** POST JSON with the attendant's token; returns (http code, body JSON). */
internal suspend fun psbillPost(client: OkHttpClient, url: String, token: String, body: JSONObject): Pair<Int, JSONObject> =
    withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        ).execute().use { r -> r.code to runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrElse { JSONObject() } }
    }

private val fieldColors @Composable get() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PSBillThemeColors.TextPrimary, unfocusedTextColor = PSBillThemeColors.TextPrimary,
    focusedBorderColor = PSBillThemeColors.PrimaryAccent, unfocusedBorderColor = PSBillThemeColors.Border,
)

/**
 * Add a player to a screen's queue. Their ticket and estimated wait go out by
 * SMS; optionally with a WiFi code valid for exactly the waiting time.
 */
@Composable
fun QueueWithWifiForm(
    deviceId: String,
    gameName: String,
    serverHost: String,
    token: String,
    client: OkHttpClient,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var giveWifi by remember { mutableStateOf(true) }
    var minutes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add to queue", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        OutlinedTextField(name, { name = it }, label = { Text("Player name") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone (ticket & WiFi by SMS)") }, singleLine = true, colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = giveWifi, onCheckedChange = { giveWifi = it })
            Spacer(Modifier.width(8.dp))
            Text("Free WiFi while waiting", color = PSBillThemeColors.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            OutlinedTextField(minutes, { v -> minutes = v.filter { it.isDigit() }.take(3) }, enabled = giveWifi, singleLine = true,
                placeholder = { Text("auto") }, label = { Text("min") }, colors = fieldColors, modifier = Modifier.width(84.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(
            enabled = !busy && name.isNotBlank(),
            onClick = {
                busy = true
                scope.launch {
                    val body = JSONObject().apply {
                        put("device_id", deviceId); put("game_name", gameName.ifBlank { "Any" })
                        put("player_name", name.trim()); put("player_phone", phone.trim()); put("send_sms", true)
                        put("give_wifi", giveWifi && phone.isNotBlank())
                        minutes.toIntOrNull()?.let { put("wifi_minutes", it) }
                    }
                    runCatching { psbillPost(client, "$serverHost/api/v1/kiosk/queue/tickets", token, body) }
                        .onSuccess { (code, res) ->
                            if (code in 200..299) {
                                val code2 = res.optJSONObject("wifi_pass")?.optString("voucher_code").orEmpty()
                                Toast.makeText(context, "Ticket #${res.optJSONObject("ticket")?.optInt("ticket_number")} · ~${res.optInt("estimated_wait_minutes")} min" +
                                    (if (code2.isNotBlank()) " · WiFi $code2" else "") + (if (res.optBoolean("sms_sent")) " · SMS sent" else ""), Toast.LENGTH_LONG).show()
                                name = ""; phone = ""; minutes = ""
                                onAdded()
                            } else Toast.makeText(context, res.optString("error").ifBlank { "Failed ($code)" }, Toast.LENGTH_LONG).show()
                        }
                        .onFailure { Toast.makeText(context, it.message ?: "Network error", Toast.LENGTH_LONG).show() }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.PrimaryAccent),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Adding…" else "Add to queue & SMS") }
    }
}

/** Give anyone WiFi for exactly N minutes; the voucher is SMSed. */
@Composable
fun WifiPassForm(serverHost: String, token: String, client: OkHttpClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("30") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Give a WiFi pass (SMS)", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true, colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("15", "30", "60").forEach { m -> FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") }) }
            OutlinedTextField(minutes, { v -> minutes = v.filter { it.isDigit() }.take(3) }, singleLine = true, label = { Text("min") },
                colors = fieldColors, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(
            enabled = !busy && token.isNotBlank() && phone.isNotBlank() && (minutes.toIntOrNull() ?: 0) > 0,
            onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        psbillPost(client, "$serverHost/api/v1/kiosk/wifi-pass", token,
                            JSONObject().put("minutes", minutes.toInt()).put("phone", phone.trim()).put("send_sms", true))
                    }.onSuccess { (code, res) ->
                        if (code in 200..299 && res.optString("voucher_code").isNotBlank()) {
                            Toast.makeText(context, "WiFi code ${res.optString("voucher_code")} (${res.optInt("minutes")} min) sent by SMS", Toast.LENGTH_LONG).show()
                            phone = ""
                        } else Toast.makeText(context, res.optString("error").ifBlank { "Failed ($code)" }, Toast.LENGTH_LONG).show()
                    }.onFailure { Toast.makeText(context, it.message ?: "Network error", Toast.LENGTH_LONG).show() }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.PrimaryAccent),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Creating…" else "Create & send SMS") }
        if (token.isBlank()) Text("Sign in to give WiFi passes.", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
    }
}
