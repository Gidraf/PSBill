package com.example.psbill.modules.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.core.ModuleContext
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// java.util on purpose: the java-time API needs Android 8+ and the app runs from Android 5
private val EAT: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")
private fun fmtIn(p: String) = SimpleDateFormat(p, Locale.ENGLISH).apply { timeZone = EAT }
private fun todayIso(): String = fmtIn("yyyy-MM-dd").format(Calendar.getInstance(EAT).time)
private fun shiftIso(iso: String, days: Int): String {
    val f = fmtIn("yyyy-MM-dd")
    val c = Calendar.getInstance(EAT).apply { time = f.parse(iso)!!; add(Calendar.DAY_OF_YEAR, days) }
    return f.format(c.time)
}
private fun clock(at: String): String = runCatching {
    // "2026-11-26T08:00:00.123456+00:00" -> 11:00 Nairobi
    val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fmtIn("HH:mm").format(utc.parse(at.take(19))!!)
}.getOrDefault("")

/** What this login delivered on a day (server: water-board my-deliveries; stamped on delivery and cash sales). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyDeliveries(ctx: ModuleContext) {
    var day by remember { mutableStateOf(todayIso()) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(day, ctx.partnerId) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url("${ctx.baseUrl}/api/v1/water-board/${ctx.partnerId}/my-deliveries?date=$day")
                    .headers(ctx.headers()).build()
                com.example.psbill.core.ActivityLog.client.newCall(req).execute().use { r ->
                    val b = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                    if (!r.isSuccessful) throw java.io.IOException(b.optString("error").ifBlank { "Could not load (${r.code})" })
                    b
                }
            }
        }.onSuccess { data = it; error = null }.onFailure { error = it.message }
        loading = false
    }
    fun kes(v: Double) = "KES " + "%,.0f".format(v)
    val orders = data?.optJSONArray("orders").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { day = shiftIso(day, -1) }) { Text("◀") }
                Text(
                    if (day == todayIso()) "Today" else fmtIn("EEE d MMM").format(fmtIn("yyyy-MM-dd").parse(day)!!),
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), color = AjiriwaColors.TextPrimary,
                )
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = { day = shiftIso(day, 1) }, enabled = day < todayIso()) { Text("▶") }
            }
        }
        error?.let { item { Text(it, color = AjiriwaColors.Danger) } }
        data?.let { d ->
            item {
                // wraps onto two lines on narrow phones instead of squeezing
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Delivered", d.optInt("count").toString())
                    Stat("Worth", kes(d.optDouble("amount", 0.0)))
                    Stat("Cash", kes(d.optDouble("cash", 0.0)))
                    Stat("Not paid", kes(d.optDouble("unpaid", 0.0)), warn = d.optDouble("unpaid", 0.0) > 0)
                }
            }
            if (orders.isEmpty()) item { Text("Nothing delivered by you on this day.", color = AjiriwaColors.TextSecondary) }
        }
        items(orders, key = { it.optString("id") }) { o ->
            val at = clock(o.optString("at"))
            val what = o.optJSONArray("items").let { a ->
                if (a == null) "" else (0 until a.length()).joinToString(", ") { i -> a.getJSONObject(i).let { "${it.opt("quantity") ?: 1} x ${it.optString("name")}" } }
            }
            val paid = o.optBoolean("paid")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AjiriwaColors.Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("$at  ${o.optString("customer")}", fontWeight = FontWeight.SemiBold, color = AjiriwaColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (what.isNotBlank()) Text(what, fontSize = 12.sp, color = AjiriwaColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(kes(o.optDouble("amount", 0.0)), fontWeight = FontWeight.Bold, color = AjiriwaColors.TextPrimary)
                    Text(if (o.optBoolean("cash")) "Cash" else if (paid) "Paid" else "Not paid", fontSize = 12.sp,
                        color = if (paid) AjiriwaColors.Success else AjiriwaColors.Warning)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, warn: Boolean = false) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(AjiriwaColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, fontSize = 11.sp, color = AjiriwaColors.TextMuted)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (warn) AjiriwaColors.Warning else AjiriwaColors.TextPrimary)
    }
}
