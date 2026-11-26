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
 * Morning check (server: app/services/daily_check.py). Until every water meter is read today (and a worked day's
 * cash is counted), the whole app is covered by this screen. The meter is drawn like the real one: black drums =
 * whole m3, four red dials x0.1 / x0.01 / x0.001 / x0.0001 m3 (100 L, 10 L, 1 L, 0.1 L): tap the number the red
 * needle points at. Litres since the last reading are worked out as they turn the dials. Owners may skip a day.
 * After closing time a small bar asks for the cash count.
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
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var cashOpen by remember { mutableStateOf(false) }
    var reminderHiddenUntil by remember { mutableLongStateOf(0L) }

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_START) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick, ctx.partnerId) {
        while (true) {
            runCatching { DailyApi.status(ctx) }.onSuccess { status = it }
            // a locked screen asks more often (another phone may have done it)
            delay(if (status?.optBoolean("blocking") == true) 60_000L else 5 * 60_000L)
        }
    }
    val s = status ?: return
    if (!s.optBoolean("required")) return
    val day = s.optString("day")
    val skipped = prefs.getString("skipped", "") == day
    if (s.optBoolean("blocking") && !skipped) {
        DailyCheckScreen(ctx, s, cashOnly = false, onSaved = { status = it }, onSkip = {
            prefs.edit().putString("skipped", day).apply()
            ActivityLog.log("daily_check_skipped", detail = JSONObject().put("day", day), context = context)
            tick++
        }, onClose = null)
        return
    }
    val cash = s.optJSONObject("cash")
    if (cash != null && cashOpen) {
        DailyCheckScreen(ctx, s, cashOnly = true, onSaved = { status = it; cashOpen = false }, onSkip = null,
            onClose = { cashOpen = false })
        return
    }
    if (cash != null && System.currentTimeMillis() > reminderHiddenUntil) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E3A8A)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Closing time: count the cash", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { reminderHiddenUntil = System.currentTimeMillis() + 60 * 60_000L }) { Text("Later", color = Color(0xFFBFDBFE)) }
                Button(onClick = { cashOpen = true }) { Text("Count now") }
            }
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun money(v: Double) = "KES " + "%,.0f".format(v)

private fun niceDay(iso: String): String = runCatching {
    val d = java.time.LocalDate.parse(iso)
    d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
}.getOrDefault(iso)

@Composable
private fun DailyCheckScreen(
    ctx: ModuleContext, status: JSONObject, cashOnly: Boolean,
    onSaved: (JSONObject) -> Unit, onSkip: (() -> Unit)?, onClose: (() -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = true) { onClose?.invoke() }
    val meters = remember(status) { status.optJSONArray("meters").objects().filter { !cashOnly && !it.optBoolean("done") } }
    val cash = status.optJSONObject("cash")
    // keyed by the day, not the status object: the status refreshes every minute while the attendant is typing
    val dayKey = status.optString("day")
    val readings = remember(dayKey) { mutableStateMapOf<String, String>() }
    val notMoved = remember(dayKey) { mutableStateMapOf<String, Boolean>() }
    var cashText by remember(dayKey) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    fun last(m: JSONObject) = m.optDouble("last_reading").takeIf { !it.isNaN() }
    val missing = meters.filter { m ->
        val r = readings[m.getString("item_id")]?.toBigDecimalOrNull()
        val l = last(m)?.toBigDecimal()
        r == null || (l != null && r.compareTo(l) == 0 && notMoved[m.getString("item_id")] != true)
    }
    val cashMissing = cash != null && (cash.optBoolean("blocking") || cashOnly) && cashText.toDoubleOrNull() == null
    val ready = missing.isEmpty() && !cashMissing

    Box(
        Modifier.fillMaxSize().background(AjiriwaColors.Canvas)
            // swallow every touch: nothing under this screen can be used
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (cashOnly) "Close the day" else "Morning check", color = AjiriwaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (cashOnly) "Count the cash in the drawer and enter it."
                else "Before you start: read the water meter${if (meters.size > 1) "s" else ""}" +
                    (if (cash?.optBoolean("blocking") == true) " and enter the cash counted on ${niceDay(cash.optString("day"))}" else "") + ".",
                color = AjiriwaColors.TextSecondary, fontSize = 14.sp,
            )
            meters.forEach { m ->
                val id = m.getString("item_id")
                MeterCard(m, onReading = { readings[id] = it }, notMoved = notMoved[id] == true, onNotMoved = { notMoved[id] = it })
            }
            if (cash != null && (cashOnly || cash.optBoolean("blocking"))) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AjiriwaColors.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cash for ${niceDay(cash.optString("day"))}", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Count all the notes and coins in the drawer.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    OutlinedTextField(cashText, { v -> cashText = v.filter { it.isDigit() || it == '.' } },
                        label = { Text("Cash counted (KES)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    val mi = cash.optDouble("money_in", 0.0)
                    if (mi > 0) Text("M-Pesa / bank received that day (from SMS): ${money(mi)}", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                    cashText.toDoubleOrNull()?.let { c ->
                        Text("Total taken: ${money(c + mi)}", color = AjiriwaColors.Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                        body.put("readings", JSONArray().apply {
                            meters.forEach { m ->
                                val id = m.getString("item_id")
                                put(JSONObject().put("item_id", id).put("reading", readings[id])
                                    .put("client_ref", "daily:${status.optString("day")}:$id:${UUID.randomUUID()}"))
                            }
                        })
                        if (cash != null && cashText.isNotBlank()) body.put("cash", JSONObject().put("day", cash.optString("day")).put("amount", cashText))
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
            ) { Text(if (saving) "Saving..." else "Save and continue", fontSize = 16.sp) }
            if (!ready) Text(
                when {
                    missing.isNotEmpty() -> "Set the meter for ${missing.joinToString { it.optString("name") }}."
                    else -> "Enter the cash counted."
                }, color = AjiriwaColors.TextMuted, fontSize = 12.sp,
            )
            if (onSkip != null && status.optBoolean("can_skip")) TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Skip for today (owner)", color = AjiriwaColors.TextMuted)
            }
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
        ) {
            Text("1. Black numbers (whole m³)", color = Color(0xFF334155), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFCBD5E1)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                (0 until drumCount).forEach { i ->
                    Drum(digits[i]) { by -> digits = digits.mapIndexed { j, d -> if (j == i) ((d + by) % 10 + 10) % 10 else d } }
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
                            Dial(digits[drumCount + k], if (cubic) labels[k] else "decimal ${k + 1}") { v ->
                                digits = digits.mapIndexed { j, d -> if (j == drumCount + k) v else d }
                            }
                        }
                    }
                }
            }
        }
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
private fun Drum(d: Int, bump: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text("▲", color = Color(0xFF475569), fontSize = 16.sp, modifier = Modifier.clickable { bump(1) }.padding(6.dp))
        Box(
            Modifier.width(36.dp).height(48.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.verticalGradient(listOf(Color.Black, Color(0xFF262626), Color.Black)))
                .clickable { bump(1) },
            contentAlignment = Alignment.Center,
        ) { Text("$d", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        Text("▼", color = Color(0xFF475569), fontSize = 16.sp, modifier = Modifier.clickable { bump(-1) }.padding(6.dp))
    }
}

/** One small red-needle dial, 0 at the top, numbers clockwise like the real meter. */
@Composable
private fun Dial(value: Int, label: String, onPick: (Int) -> Unit) {
    val size = 116.dp
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
                val r = 41.0
                val picked = n == value
                Box(
                    Modifier.align(Alignment.Center).offset((cos(a) * r).dp, (sin(a) * r).dp).size(26.dp).clip(CircleShape)
                        .background(if (picked) Color(0xFFFEE2E2) else Color.Transparent)
                        .clickable { onPick(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", color = if (picked) Color(0xFFB91C1C) else Color(0xFF0F172A),
                        fontWeight = if (picked) FontWeight.ExtraBold else FontWeight.Medium, fontSize = 14.sp)
                }
            }
        }
        Text(label, color = Color(0xFFB91C1C), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
    }
}
