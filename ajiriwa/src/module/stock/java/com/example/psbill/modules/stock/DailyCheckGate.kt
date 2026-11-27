package com.example.psbill.modules.stock

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.psbill.core.ActivityLog
import com.example.psbill.core.ModuleContext
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Meter check (server: app/services/daily_check.py). A meter is due 24 h after its last reading; while one is due
 * the whole app is covered by this screen. The meter is drawn like the real one: black drums =
 * whole m3, four red dials x0.1 / x0.01 / x0.001 / x0.0001 m3 (100 L, 10 L, 1 L, 0.1 L): tap the number the red
 * needle points at. Litres since the last reading are worked out as they turn the dials. Nobody can skip it (owner 2026-11-26),
 * not even the owner; offline the last answer is kept and the phone stays locked until it can check.
 * Cash is OPTIONAL and separate (owner 2026-11-27): after closing time (or for a worked day never counted) a small bar
 * offers "Count cash" with the system's estimate (orders, M-Pesa, water metered); the meter screen never asks for cash.
 */
private object DailyApi {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val http get() = ActivityLog.client

    suspend fun call(ctx: ModuleContext, req: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req.headers(ctx.headers()).build()).execute().use { r ->
            val body = runCatching { JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }) }.getOrElse { JSONObject() }
            if (!r.isSuccessful && body.optJSONArray("errors") == null) throw IOException(body.optString("error").ifBlank { "Request failed (${r.code})" })
            body
        }
    }

    fun url(ctx: ModuleContext) = "${ctx.baseUrl}/api/v1/stock/${ctx.partnerId}/daily-check"
    suspend fun status(ctx: ModuleContext) = call(ctx, Request.Builder().url(url(ctx)))
    suspend fun send(ctx: ModuleContext, body: JSONObject) = call(ctx, Request.Builder().url(url(ctx)).post(body.toString().toRequestBody(json)))
}

private const val PREFS = "daily_check"

@Composable
internal fun DailyCheckGate(ctx: ModuleContext) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    // the last answer is kept, so a phone that is offline (or just opened) stays locked instead of letting work start
    var status by remember { mutableStateOf(prefs.getString("status", null)?.let { runCatching { JSONObject(it) }.getOrNull() }) }
    var fresh by remember { mutableStateOf(false) }
    var netError by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var cashOpen by remember { mutableStateOf(false) }
    var reminderHiddenUntil by remember { mutableLongStateOf(0L) }
    fun keep(j: JSONObject) { status = j; fresh = true; netError = null; prefs.edit().putString("status", j.toString()).apply() }

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_START) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick, ctx.partnerId) {
        while (true) {
            runCatching { DailyApi.status(ctx) }.onSuccess { keep(it) }.onFailure { netError = it.message }
            // a locked screen asks more often (another phone may have done it); offline retries every 15 s
            delay(when { !fresh -> 15_000L; status?.optBoolean("blocking") == true -> 60_000L; else -> 5 * 60_000L })
        }
    }
    val s = status ?: return
    if (!s.optBoolean("required")) return
    // owner 2026-11-26: never tolerant, no skip. While the server has not answered, a kept answer whose meter has come
    // due (24 h after its last reading, by the phone clock) locks too; a fresh answer wins over a wrong phone clock.
    val dueByClock = !fresh && s.optJSONArray("meters").objects().any { m ->
        isoMillis(m.optString("next_due"))?.let { it <= System.currentTimeMillis() } ?: false
    }
    if (s.optBoolean("blocking") || dueByClock) {
        if (!fresh) WaitingForCheck(netError) { tick++ }
        else DailyCheckScreen(ctx, s, cashOnly = false, onSaved = { keep(it) }, onClose = null)
        return
    }
    val cash = s.optJSONObject("cash")
    if (cash != null && cashOpen) {
        DailyCheckScreen(ctx, s, cashOnly = true, onSaved = { keep(it); cashOpen = false }, onClose = { cashOpen = false })
        return
    }
    val skipped = prefs.getString("cash_skipped", null)
    if (cash != null && cash.optString("day") != skipped && System.currentTimeMillis() > reminderHiddenUntil) {
        val exp = cash.optJSONObject("estimate")?.optDouble("expected")?.takeIf { !it.isNaN() }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E3A8A)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Count the cash (optional)", color = Color.White, fontWeight = FontWeight.SemiBold)
                    if (exp != null) Text("System expects about ${money(exp)}", color = Color(0xFFBFDBFE), fontSize = 12.sp)
                }
                TextButton(onClick = {
                    reminderHiddenUntil = System.currentTimeMillis() + 60 * 60_000L
                    prefs.edit().putString("cash_skipped", cash.optString("day")).apply()
                }) { Text("Skip", color = Color(0xFFBFDBFE)) }
                Button(onClick = { cashOpen = true }) { Text("Count") }
            }
        }
    }
}

/** "2026-11-27T06:00:00.123+00:00" -> epoch ms (server times are UTC; java.util for Android 5). */
private fun isoMillis(iso: String?): Long? = runCatching {
    if (iso.isNullOrBlank() || iso.length < 19) return null
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso.substring(0, 19))!!.time
}.getOrNull()

/** Locked, but the server could not be asked yet (offline, or the first minute of a new day). */
@Composable
private fun WaitingForCheck(error: String?, retry: () -> Unit) {
    BackHandler(enabled = true) {}
    Box(
        Modifier.fillMaxSize().background(AjiriwaColors.Canvas)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("💧", fontSize = 48.sp)
            Text("Meter check", color = AjiriwaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Read the water meter before you start. The app opens once it is saved.",
                color = AjiriwaColors.TextSecondary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (error != null) Text("No connection: turn on data or WiFi.", color = AjiriwaColors.Danger, fontSize = 13.sp)
            else CircularProgressIndicator(Modifier.size(28.dp))
            Button(onClick = retry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again", fontSize = 16.sp) }
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun money(v: Double) = "KES " + "%,.0f".format(v)

private fun niceDay(iso: String): String = runCatching {
    // java.util, not java.time: java.time needs Android 8+ and the app runs from Android 5
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)!!
    java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.ENGLISH).format(d)
}.getOrDefault(iso)

@Composable
private fun DailyCheckScreen(
    ctx: ModuleContext, status: JSONObject, cashOnly: Boolean,
    onSaved: (JSONObject) -> Unit, onClose: (() -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = true) { onClose?.invoke() }
    val meters = remember(status) { status.optJSONArray("meters").objects().filter { !cashOnly && it.optBoolean("due", !it.optBoolean("done")) } }
    val cash = if (cashOnly) status.optJSONObject("cash") else null
    val est = cash?.optJSONObject("estimate")
    // keyed by the day, not the status object: the status refreshes every minute while the attendant is typing
    val dayKey = status.optString("day")
    val readings = remember(dayKey) { mutableStateMapOf<String, String>() }
    val notMoved = remember(dayKey) { mutableStateMapOf<String, Boolean>() }
    var cashText by remember(dayKey) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    fun last(m: JSONObject) = m.optDouble("last_reading").takeIf { !it.isNaN() }
    // a reading lower than the last one is never sent (the server refuses it too, unless the meter rolled over)
    val lower = meters.filter { m ->
        val r = readings[m.getString("item_id")]?.toBigDecimalOrNull()
        val l = last(m)?.toBigDecimal()
        r != null && l != null && r < l && m.optDouble("rollover").isNaN()
    }
    val missing = meters.filter { m ->
        val r = readings[m.getString("item_id")]?.toBigDecimalOrNull()
        val l = last(m)?.toBigDecimal()
        r == null || m in lower || (l != null && r.compareTo(l) == 0 && notMoved[m.getString("item_id")] != true)
    }
    val cashMissing = cashOnly && cashText.toDoubleOrNull() == null
    val ready = missing.isEmpty() && !cashMissing

    Box(
        Modifier.fillMaxSize().background(AjiriwaColors.Canvas)
            // swallow every touch: nothing under this screen can be used
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // edge-to-edge app: keep the title off the status bar, Save above the nav bar and the cash box above the keyboard
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (cashOnly) "Count the cash" else "Meter check", color = AjiriwaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (cashOnly) "Optional: count the notes and coins in the drawer. The system already estimates it."
                else "Read the water meter${if (meters.size > 1) "s" else ""} - it is asked every 24 hours.",
                color = AjiriwaColors.TextSecondary, fontSize = 14.sp,
            )
            meters.forEach { m ->
                val id = m.getString("item_id")
                MeterCard(m, onReading = { readings[id] = it }, notMoved = notMoved[id] == true, onNotMoved = { notMoved[id] = it })
            }
            if (cash != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AjiriwaColors.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cash for ${niceDay(cash.optString("day"))}", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    val expected = est?.optDouble("expected")?.takeIf { !it.isNaN() }
                    if (expected != null) {
                        Text("System expects about ${money(expected)}", color = AjiriwaColors.Primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        val parts = buildList {
                            est.optDouble("sales").takeIf { !it.isNaN() && it > 0 }?.let { add("orders ${money(it)}") }
                            est.optDouble("water_value").takeIf { !it.isNaN() }?.let { v ->
                                add("water ${"%,.0f".format(est.optDouble("litres") - est.optDouble("washing_litres", 0.0))} L = ${money(v)}")
                            }
                            est.optDouble("cash_on_orders").takeIf { !it.isNaN() && it > 0 }?.let { add("cash on orders ${money(it)}") }
                        }
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · ") + " minus M-Pesa/bank", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                        Text(if (est.optString("confidence") == "good") "Orders and water agree" else "Rough estimate",
                            color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    } else Text("Count all the notes and coins in the drawer.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    OutlinedTextField(cashText, { v -> cashText = v.filter { it.isDigit() || it == '.' } },
                        label = { Text("Cash counted (KES)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    val mi = cash.optDouble("money_in", 0.0)
                    if (mi > 0) Text("M-Pesa / bank received that day (from SMS): ${money(mi)}", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                    cashText.toDoubleOrNull()?.let { c ->
                        Text("Total taken: ${money(c + mi)}", color = AjiriwaColors.Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val expected = est?.optDouble("expected")?.takeIf { !it.isNaN() }
                        if (expected != null && kotlin.math.abs(c - expected) > maxOf(50.0, expected * 0.03)) Text(
                            "${money(kotlin.math.abs(c - expected))} ${if (c > expected) "more" else "less"} than the system expects",
                            color = AjiriwaColors.Danger, fontSize = 12.sp)
                    }
                }
            }
            errors.forEach { Text(it, color = AjiriwaColors.Danger, fontSize = 13.sp) }
            Button(
                enabled = ready && !saving, modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    saving = true
                    scope.launch {
                        val body = JSONObject()
                        if (!cashOnly) body.put("readings", JSONArray().apply {
                            meters.forEach { m ->
                                val id = m.getString("item_id")
                                put(JSONObject().put("item_id", id).put("reading", readings[id])
                                    .put("client_ref", "daily:${status.optString("day")}:$id:${UUID.randomUUID()}"))
                            }
                        })
                        if (cashOnly && cash != null && cashText.isNotBlank()) body.put("cash", JSONObject().put("day", cash.optString("day")).put("amount", cashText))
                        runCatching { DailyApi.send(ctx, body) }.onSuccess { r ->
                            errors = r.optJSONArray("errors").objects().map { e -> (e.optString("name").takeIf { it.isNotBlank() }?.let { "$it: " } ?: "") + e.optString("error") }
                            r.optJSONArray("results").objects().mapNotNull { it.optString("message").takeIf { m -> m.isNotBlank() } }
                                .joinToString("\n").takeIf { it.isNotBlank() }?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            ActivityLog.log("daily_check_saved", detail = JSONObject().put("day", status.optString("day")), context = context)
                            r.optJSONObject("status")?.let(onSaved)
                        }.onFailure { errors = listOf(it.message ?: "Could not save. Check the internet and try again.") }
                        saving = false
                    }
                },
            ) { Text(if (saving) "Saving..." else if (cashOnly) "Save cash count" else "Save and continue", fontSize = 16.sp) }
            if (!ready) Text(
                when {
                    lower.isNotEmpty() -> "${lower.joinToString { it.optString("name") }}: the reading is lower than the last one. Check every number."
                    missing.isNotEmpty() -> "Set the meter for ${missing.joinToString { it.optString("name") }}."
                    else -> "Enter the cash counted."
                }, color = AjiriwaColors.TextMuted, fontSize = 12.sp,
            )
            if (onClose != null) TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
        }
    }
}

/** The dials of one meter, plus what the reading means in litres. */
@Composable
private fun MeterCard(m: JSONObject, onReading: (String) -> Unit, notMoved: Boolean, onNotMoved: (Boolean) -> Unit) {
    val unit = m.optString("meter_unit").takeIf { it.isNotBlank() && it != "null" } ?: "m3"
    val cubic = unit.lowercase() in listOf("m3", "m³")
    val dialCount = when {
        cubic -> 4
        !m.isNull("decimals") -> m.optInt("decimals").coerceIn(0, 4)
        else -> 0
    }
    val last = m.optDouble("last_reading").takeIf { !it.isNaN() }
    val key = m.optString("item_id") + "|" + m.optString("last_reading")
    val startScaled = remember(key) { BigDecimal.valueOf(last ?: 0.0).movePointRight(dialCount).toBigInteger().toString() }
    val drumCount = maxOf(4, startScaled.length - dialCount)
    val start = remember(key) { startScaled.padStart(drumCount + dialCount, '0').map { it - '0' } }
    var digits by remember(key) { mutableStateOf(start) }
    val reading = remember(digits) {
        val s = digits.joinToString("")
        BigDecimal(s.take(drumCount).ifBlank { "0" } + if (dialCount > 0) "." + s.drop(drumCount) else "")
    }
    LaunchedEffect(reading) { onReading(reading.toPlainString()) }
    val factor = m.optDouble("factor", 1.0)
    val litres = last?.let { (reading - BigDecimal.valueOf(it)).toDouble() * factor }
    val itemUnit = m.optString("unit").ifBlank { "L" }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AjiriwaColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(m.optString("name") + (m.optString("meter_number").takeIf { it.isNotBlank() && it != "null" }?.let { "  -  meter $it" } ?: ""),
            color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Make the picture look exactly like your meter.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
        // the meter face: dark rim, white dial
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF111827)).padding(10.dp)
                .clip(RoundedCornerShape(22.dp)).background(Color(0xFFF8FAFC)).padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { BoxWithConstraints(Modifier.fillMaxWidth()) {
            // sized from the width: two dials need 244dp at full size, a 320dp phone leaves ~224dp here
            val dialSize = minOf(116.dp, (maxWidth - 12.dp) / 2)
            val drumWidth = minOf(36.dp, (maxWidth - 40.dp) / drumCount.coerceAtLeast(1) - 4.dp).coerceAtLeast(22.dp)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Black numbers (whole m³)", color = Color(0xFF334155), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFCBD5E1)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                (0 until drumCount).forEach { i ->
                    Drum(digits[i], drumWidth) { by -> digits = digits.mapIndexed { j, d -> if (j == i) ((d + by) % 10 + 10) % 10 else d } }
                }
                Text(" m³", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (dialCount > 0) {
                Text("2. Red needles: tap the number each needle points at", color = Color(0xFF334155), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                val labels = listOf("x0.1  (100 L)", "x0.01  (10 L)", "x0.001  (1 L)", "x0.0001  (0.1 L)")
                (0 until dialCount).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { k ->
                            Dial(digits[drumCount + k], if (cubic) labels[k] else "decimal ${k + 1}", dialSize) { v ->
                                digits = digits.mapIndexed { j, d -> if (j == drumCount + k) v else d }
                            }
                        }
                    }
                }
            }
        } } }
        Text("Reading: ${reading.toPlainString()} $unit", color = AjiriwaColors.TextPrimary, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (last == null) {
            Text("First reading for this meter: it is the starting point.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
        } else {
            val lastAt = m.optString("last_at").takeIf { it.length >= 16 }?.take(16)?.replace('T', ' ') ?: ""
            Text("Last reading ${BigDecimal.valueOf(last).stripTrailingZeros().toPlainString()} $unit $lastAt",
                color = AjiriwaColors.TextMuted, fontSize = 12.sp)
            val l = litres ?: 0.0
            when {
                l < 0 -> Text("Lower than the last reading - check every number again.", color = AjiriwaColors.Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                l == 0.0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = notMoved, onCheckedChange = onNotMoved)
                    Text("The meter has not moved since the last reading", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
                }
                else -> Text("= ${"%,.1f".format(l)} $itemUnit since the last reading", color = Color(0xFF0369A1),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Drum(d: Int, width: androidx.compose.ui.unit.Dp = 36.dp, bump: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text("▲", color = Color(0xFF475569), fontSize = 16.sp, modifier = Modifier.clickable { bump(1) }.padding(6.dp))
        Box(
            Modifier.width(width).height(width * 4 / 3).clip(RoundedCornerShape(4.dp))
                .background(Brush.verticalGradient(listOf(Color.Black, Color(0xFF262626), Color.Black)))
                .clickable { bump(1) },
            contentAlignment = Alignment.Center,
        ) { Text("$d", color = Color.White, fontSize = (28 * width.value / 36).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        Text("▼", color = Color(0xFF475569), fontSize = 16.sp, modifier = Modifier.clickable { bump(-1) }.padding(6.dp))
    }
}

/** One small red-needle dial, 0 at the top, numbers clockwise like the real meter. */
@Composable
private fun Dial(value: Int, label: String, size: androidx.compose.ui.unit.Dp = 116.dp, onPick: (Int) -> Unit) {
    val k = size.value / 116f   // everything inside scales with the dial
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).background(Color.White).border(2.dp, Color(0xFF94A3B8), CircleShape)) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val a = (value / 10.0) * 2 * PI - PI / 2
                val len = this.size.minDimension * 0.27f
                drawLine(Color(0xFFDC2626), c, Offset(c.x + (cos(a) * len).toFloat(), c.y + (sin(a) * len).toFloat()),
                    strokeWidth = 9f, cap = StrokeCap.Round)
                drawCircle(Color(0xFFDC2626), radius = 11f, center = c)
            }
            (0..9).forEach { n ->
                val a = (n / 10.0) * 2 * PI - PI / 2
                val r = 41.0 * k
                val picked = n == value
                Box(
                    Modifier.align(Alignment.Center).offset((cos(a) * r).dp, (sin(a) * r).dp).size((26 * k).coerceAtLeast(22f).dp).clip(CircleShape)
                        .background(if (picked) Color(0xFFFEE2E2) else Color.Transparent)
                        .clickable { onPick(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", color = if (picked) Color(0xFFB91C1C) else Color(0xFF0F172A),
                        fontWeight = if (picked) FontWeight.ExtraBold else FontWeight.Medium, fontSize = (14 * k).coerceAtLeast(11f).sp)
                }
            }
        }
        Text(label, color = Color(0xFFB91C1C), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(top = 2.dp).widthIn(max = size + 8.dp))
    }
}
