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

/*
 * Free WiFi passes and their life (GET /api/v1/kiosk/wifi-passes): a pass ends its minutes
 * after it was GIVEN, used or not. Waiting = not taken yet, In use, Consumed (taken, time up),
 * Expired (never taken, time up), Failed (no voucher). Refreshes every 30 s.
 */
private val PASS_FILTERS = listOf("" to "All", "waiting" to "Waiting", "in_use" to "In use",
    "consumed" to "Consumed", "expired" to "Expired", "failed" to "Failed")

@Composable
fun FreeWifiPassList(serverHost: String, token: String, client: OkHttpClient) {
    var filter by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var counts by remember { mutableStateOf(JSONObject()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0L) }

    LaunchedEffect(filter, token) {
        while (token.isNotBlank()) {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder()
                        .url("$serverHost/api/v1/kiosk/wifi-passes?days=2&limit=100" + (if (filter.isNotBlank()) "&state=$filter" else ""))
                        .header("Authorization", "Bearer $token").build()).execute().use { r ->
                        r.code to runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrElse { JSONObject() }
                    }
                }
            }
            res.onSuccess { (code, body) ->
                if (code in 200..299) {
                    error = null
                    val arr = body.optJSONArray("items")
                    items = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                    counts = body.optJSONObject("counts") ?: JSONObject()
                    tick = System.currentTimeMillis()
                } else error = body.optString("error").ifBlank { "Could not load passes ($code)" }
            }.onFailure { error = it.message ?: "Network error" }
            kotlinx.coroutines.delay(30_000)
        }
    }
    // count the time left down between loads
    val now by produceState(System.currentTimeMillis(), tick) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    val drift = ((now - tick) / 1000).coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("A pass runs from the moment it is given. Not taken in time = expired.",
            color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PASS_FILTERS.size) { i ->
                val (key, label) = PASS_FILTERS[i]
                val n = if (key.isBlank()) counts.keys().asSequence().sumOf { counts.optInt(it) } else counts.optInt(key)
                FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text("$label ($n)") })
            }
        }
        error?.let { Text(it, color = PSBillThemeColors.Amber, fontSize = 12.sp) }
        if (items.isEmpty() && error == null) {
            Text("No passes in the last 2 days" + (if (filter.isNotBlank()) " with this status." else "."),
                color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
        }
        items.forEach { p -> PassRow(p, drift) }
    }
}

@Composable
private fun PassRow(p: JSONObject, drift: Long) {
    val state = p.optString("state")
    val (label, tone) = when (state) {
        "waiting" -> "WAITING" to PsTone.Warn
        "in_use" -> "IN USE" to PsTone.Good
        "consumed" -> "CONSUMED" to PsTone.Idle
        "expired" -> "EXPIRED" to PsTone.Bad
        else -> "FAILED" to PsTone.Bad
    }
    val left = (p.optLong("seconds_left") - drift).coerceAtLeast(0)
    val used = p.optLong("used_seconds") + (if (state == "in_use") drift else 0)
    val who = p.optString("name").takeIf { it.isNotBlank() && it != "null" }
        ?: p.optString("phone").takeIf { it.isNotBlank() && it != "null" } ?: "No phone"
    val reason = when (p.optString("reason")) {
        "queue_wait", "portal_queue" -> "while waiting"
        "session_end" -> "after a game"
        else -> "given by staff"
    }
    val detail = when (state) {
        "waiting" -> "Not taken yet - expires in ${dur(left)}"
        "in_use" -> "On WiFi ${dur(used)} - ${dur(left)} left"
        "consumed" -> "Used ${dur(used)} of ${p.optInt("minutes")} min"
        "expired" -> "Never taken - expired after ${p.optInt("minutes")} min"
        else -> p.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: "No voucher made"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("$who  -  ${p.optInt("minutes")} min $reason", color = PSBillThemeColors.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${givenAt(p.optString("created_at"))}  -  $detail", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp, maxLines = 2)
        }
        PsPill(label, tone)
    }
}

private fun dur(secs: Long): String = when {
    secs >= 3600 -> "${secs / 3600} h ${(secs % 3600) / 60} min"
    secs >= 60 -> "${secs / 60} min"
    else -> "${secs} s"
}

private fun givenAt(iso: String): String = runCatching {
    val p = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    "Given " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(p.parse(iso.take(19))!!)
}.getOrDefault("")
