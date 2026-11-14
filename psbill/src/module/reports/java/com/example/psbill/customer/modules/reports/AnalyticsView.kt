package com.example.psbill.customer.modules.reports

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.customer.*
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

private val RANGES = listOf("today" to "Today", "yesterday" to "Yesterday", "7d" to "7 days", "30d" to "30 days",
    "month" to "This month", "custom" to "Pick dates")

private fun money(v: Double) = String.format(Locale.US, "%,.0f", if (v.isNaN()) 0.0 else v)
private fun hm(minutes: Int) = if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"

/**
 * Lounge analytics (PSBill Admin): sales per day, per screen and per game, session lengths, busy
 * hours and how customers paid, for any period; export the same as PDF, Excel or CSV and share it.
 * GET /api/v1/kiosk/analytics, /api/v1/kiosk/analytics/export (owner-only on the server too).
 */
@Composable
fun AnalyticsView(nodes: List<JSONObject>) {
    val api = LocalPsApi.current ?: return
    val context = LocalContext.current
    var range by rememberSaveableString("7d")
    var from by rememberSaveableString("")
    var to by rememberSaveableString("")
    var screen by rememberSaveableString("")
    var game by rememberSaveableString("")
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var exportMenu by remember { mutableStateOf(false) }

    fun query(): String {
        val q = StringBuilder(if (range == "custom" && from.isNotBlank()) "date_from=$from&date_to=${to.ifBlank { from }}" else "range=$range")
        if (screen.isNotBlank()) q.append("&device_id=").append(android.net.Uri.encode(screen))
        if (game.isNotBlank()) q.append("&game=").append(android.net.Uri.encode(game))
        return q.toString()
    }

    fun load() {
        if (range == "custom" && from.isBlank()) return
        loading = true; error = null
        api.get("/api/v1/kiosk/analytics?${query()}") { code, body ->
            loading = false
            if (code in 200..299) data = runCatching { JSONObject(body) }.getOrNull()
            else error = api.errorText(code, body, "Could not load the report")
        }
    }
    LaunchedEffect(range, from, to, screen, game) { load() }

    fun export(fmt: String) {
        exporting = fmt
        val r = data?.optJSONObject("range")
        val name = "psbill-report-${r?.optString("date_from") ?: "today"}-to-${r?.optString("date_to") ?: "today"}.$fmt"
        api.download("/api/v1/kiosk/analytics/export?format=$fmt&${query()}", name) { code, file, body ->
            exporting = null
            val mime = when (fmt) { "pdf" -> "application/pdf"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; else -> "text/csv" }
            if (file != null) shareFile(context, file, mime, "Send the report")
            else api.toast(api.errorText(code, body, "Could not make the file"))
        }
    }

    fun pickDate(initial: String, onPick: (String) -> Unit) {
        val c = Calendar.getInstance()
        runCatching { initial.split("-").map { it.toInt() } }.getOrNull()?.takeIf { it.size == 3 }?.let { c.set(it[0], it[1] - 1, it[2]) }
        DatePickerDialog(context, { _, y, m, d -> onPick(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)) },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PsPageHeader("Analytics", data?.optJSONObject("range")?.let { "${it.optString("date_from")} to ${it.optString("date_to")}" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                Box {
                    Button(onClick = { exportMenu = true }, enabled = exporting == null && data != null) {
                        if (exporting != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Export")
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        listOf(Triple("pdf", "PDF report", Icons.Filled.PictureAsPdf), Triple("xlsx", "Excel", Icons.Filled.TableChart),
                            Triple("csv", "CSV", Icons.Filled.Description)).forEach { (f, label, icon) ->
                            DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null) },
                                onClick = { exportMenu = false; export(f) })
                        }
                    }
                }
            }
        }

        PsChips(RANGES, range, { r -> range = r; if (r == "custom" && from.isBlank()) pickDate("") { from = it; to = it } })
        if (range == "custom") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { pickDate(from) { from = it; if (to.isBlank() || to < it) to = it } }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(from.ifBlank { "From" })
            }
            OutlinedButton(onClick = { pickDate(to.ifBlank { from }) { to = it } }, modifier = Modifier.weight(1f), enabled = from.isNotBlank()) {
                Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(to.ifBlank { "To" })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                DropdownField("Screen", screen, listOf("") + nodes.map { it.optString("id") },
                    display = { id -> if (id.isBlank()) "All screens" else nodes.firstOrNull { it.optString("id") == id }?.let { nodeName(it) } ?: id }) { screen = it }
            }
            Box(Modifier.weight(1f)) {
                val gameNames = data?.optJSONArray("games")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("game") } }.orEmpty()
                DropdownField("Game", game, listOf("") + (gameNames + game).filter { it.isNotBlank() }.distinct(),
                    display = { if (it.isBlank()) "All games" else it }) { game = it }
            }
        }

        val d = data
        PsLoadState(loading, error, d == null, "Pick a period to see your numbers.", ::load) {
            if (d != null) AnalyticsBody(d, onScreen = { screen = it })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun rememberSaveableString(initial: String): MutableState<String> =
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@Composable
private fun AnalyticsBody(d: JSONObject, onScreen: (String) -> Unit) {
    val t = d.optJSONObject("totals") ?: JSONObject()
    val cols = if (isExpanded()) 4 else 2
    val tiles = listOf(
        Triple("Revenue", "KES ${money(t.optDouble("revenue_kes"))}", PsTone.Good),
        Triple("Sessions", t.optInt("sessions").toString(), PsTone.Busy),
        Triple("Played", hm(t.optInt("minutes")), PsTone.Busy),
        Triple("Average session", "${t.optDouble("avg_minutes", 0.0)} min", PsTone.Idle),
        Triple("Average per session", "KES ${money(t.optDouble("avg_kes"))}", PsTone.Idle),
        Triple("Not paid yet", "KES ${money(t.optDouble("unpaid_kes"))}", if (t.optDouble("unpaid_kes") > 0) PsTone.Warn else PsTone.Idle),
        Triple("Screens used", t.optInt("screens_used").toString(), PsTone.Idle),
        Triple("Busiest hour", if (t.isNull("busiest_hour")) "-" else String.format(Locale.US, "%02d:00", t.optInt("busiest_hour")), PsTone.Idle),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (l, v, tone) -> PsStat(l, v, tone, Modifier.weight(1f)) }
            }
        }

        val perDay = objects(d.optJSONArray("per_day"))
        val wide = isWide()
        val chart: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m, "Revenue per day") {
                if (perDay.size <= 1) HourBars(objects(d.optJSONArray("hours")))
                else DayBars(perDay)
            }
        }
        val modes: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m, "How customers paid") { Breakdown(objects(d.optJSONArray("modes")), "label") }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { chart(Modifier.weight(2f)); modes(Modifier.weight(1f)) }
        else { chart(Modifier.fillMaxWidth()); modes(Modifier.fillMaxWidth()) }

        val screens: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m, "Screens") {
                val list = objects(d.optJSONArray("screens"))
                if (list.isEmpty()) Text("No sessions", color = PSBillThemeColors.TextSecondary)
                val max = list.maxOfOrNull { it.optDouble("revenue_kes") }?.takeIf { it > 0 } ?: 1.0
                list.forEach { s ->
                    BarRow(s.optString("name"), "${s.optInt("sessions")} sessions - ${hm(s.optInt("minutes"))} - busy ${s.optDouble("busy_pct")}%",
                        "KES ${money(s.optDouble("revenue_kes"))}", (s.optDouble("revenue_kes") / max).toFloat(), PSBillThemeColors.PrimaryAccent) {
                        onScreen(s.optString("device_id"))
                    }
                }
            }
        }
        val games: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m, "Games") {
                val list = objects(d.optJSONArray("games"))
                if (list.isEmpty()) Text("No games played", color = PSBillThemeColors.TextSecondary)
                val max = list.maxOfOrNull { it.optDouble("revenue_kes") }?.takeIf { it > 0 } ?: 1.0
                list.take(15).forEach { g ->
                    BarRow(g.optString("game"), "${g.optInt("sessions")} sessions - avg ${g.optDouble("avg_minutes")} min",
                        "KES ${money(g.optDouble("revenue_kes"))}", (g.optDouble("revenue_kes") / max).toFloat(), PsTone.Good, null)
                }
            }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { screens(Modifier.weight(1f)); games(Modifier.weight(1f)) }
        else { screens(Modifier.fillMaxWidth()); games(Modifier.fillMaxWidth()) }

        val lengths: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m, "How long people play") { Breakdown(objects(d.optJSONArray("durations")), "label") }
        }
        val hours: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m, "Busy hours") { HourBars(objects(d.optJSONArray("hours"))) }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { lengths(Modifier.weight(1f)); hours(Modifier.weight(1f)) }
        else { lengths(Modifier.fillMaxWidth()); if (perDay.size > 1) hours(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ChartCard(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(PSBillThemeColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun DayBars(days: List<JSONObject>) {
    val max = days.maxOfOrNull { it.optDouble("revenue_kes") }?.takeIf { it > 0 } ?: 1.0
    val accent = PSBillThemeColors.PrimaryAccent
    val grid = PSBillThemeColors.Border
    var picked by remember(days) { mutableStateOf<Int?>(null) }
    Column {
        val p = picked?.let { days.getOrNull(it) }
        Text(if (p != null) "${p.optString("date")}: KES ${money(p.optDouble("revenue_kes"))} - ${p.optInt("sessions")} sessions"
            else "Best day KES ${money(max)} - tap a bar", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(160.dp).clickableBars(days.size) { picked = it }) {
            val n = days.size
            val gap = if (n > 40) 1f else 4f
            val w = (size.width - gap * (n - 1)) / n
            for (i in 1..3) drawLine(grid, Offset(0f, size.height * i / 4f), Offset(size.width, size.height * i / 4f), 1f)
            days.forEachIndexed { i, x ->
                val h = (x.optDouble("revenue_kes") / max).toFloat() * size.height
                drawRoundRect(if (picked == i) Color.White else accent, Offset(i * (w + gap), size.height - h), Size(w, maxOf(h, 2f)),
                    CornerRadius(3f, 3f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(days.first().optString("date").drop(5), color = PSBillThemeColors.TextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(days.last().optString("date").drop(5), color = PSBillThemeColors.TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun HourBars(hours: List<JSONObject>) {
    if (hours.isEmpty()) return
    val max = hours.maxOfOrNull { it.optInt("sessions") }?.takeIf { it > 0 } ?: 1
    val accent = PsTone.Warn
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width / 24f
            hours.forEach { h ->
                val v = h.optInt("sessions").toFloat() / max * size.height
                drawRoundRect(accent, Offset(h.optInt("hour") * w + 1f, size.height - v), Size(w - 2f, maxOf(v, 2f)), CornerRadius(2f, 2f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "23").forEach { Text(it, color = PSBillThemeColors.TextSecondary, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun Breakdown(rows: List<JSONObject>, labelKey: String) {
    val total = rows.sumOf { it.optInt("sessions") }.takeIf { it > 0 } ?: 1
    val palette = listOf(PSBillThemeColors.PrimaryAccent, PsTone.Good, PsTone.Warn, PsTone.Bad, PsTone.Idle)
    if (rows.isEmpty()) Text("No sessions", color = PSBillThemeColors.TextSecondary)
    rows.forEachIndexed { i, r ->
        BarRow(r.optString(labelKey), "${r.optInt("sessions")} sessions", "KES ${money(r.optDouble("revenue_kes"))}",
            r.optInt("sessions").toFloat() / total, palette[i % palette.size], null)
    }
}

@Composable
private fun BarRow(title: String, sub: String, value: String, fraction: Float, tone: Color, onClick: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = PSBillThemeColors.TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = PSBillThemeColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(PSBillThemeColors.SurfaceAlt)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(tone))
        }
    }
}

private fun Modifier.clickableBars(n: Int, onPick: (Int) -> Unit): Modifier =
    this.pointerInput(n) {
        detectTapGestures { pos -> if (n > 0) onPick((pos.x / size.width * n).toInt().coerceIn(0, n - 1)) }
    }
