package com.example.psbill.modules.stock

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Stock levels & flow for attendants: every item has storage → ready-to-sell
 * (raw → purified water, store → shelf). Record restocks, meter readings,
 * sales, losses, backwash and stock takes; the office sees the full report.
 */
object StockFeature : FeatureModule() {
    override val slug = "stock"
    override val nav = NavModule("stock", "Stock & Tanks", Icons.AutoMirrored.Filled.List, listOf("inventory"), order = 15)

    private val http by lazy { OkHttpClient() }

    private class Api(val ctx: ModuleContext) {
        private val base get() = "${ctx.baseUrl}/api/v1/stock/${ctx.partnerId}"
        private val json = "application/json; charset=utf-8".toMediaType()
        suspend fun call(req: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
            http.newCall(req.headers(ctx.headers()).build()).execute().use { r ->
                val body = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                if (!r.isSuccessful) throw IOException(body.optString("error").ifBlank { "Request failed (${r.code})" })
                body
            }
        }
        suspend fun items() = call(Request.Builder().url("$base/items"))
        suspend fun movements() = call(Request.Builder().url("$base/movements?limit=40"))
        suspend fun flow(days: Int): JSONObject {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val cal = java.util.Calendar.getInstance()
            val to = fmt.format(cal.time)
            cal.add(java.util.Calendar.DAY_OF_YEAR, -(days - 1))
            return call(Request.Builder().url("$base/reports/flow?from=${fmt.format(cal.time)}&to=$to"))
        }
        suspend fun post(path: String, body: JSONObject) = call(Request.Builder().url("$base$path").post(body.toString().toRequestBody(json)))
    }

    @Composable
    override fun Content(ctx: ModuleContext) {
        val api = remember(ctx.server, ctx.partnerId) { Api(ctx) }
        var tab by remember { mutableIntStateOf(0) }
        var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var reload by remember { mutableIntStateOf(0) }
        var error by remember { mutableStateOf<String?>(null) }
        var action by remember { mutableStateOf<Pair<JSONObject, String>?>(null) }

        LaunchedEffect(reload) {
            runCatching { api.items() }.onSuccess { r ->
                items = r.optJSONArray("items").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) } }
                error = null
            }.onFailure { error = it.message }
        }

        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = AjiriwaColors.Canvas, contentColor = AjiriwaColors.Primary) {
                listOf("Levels", "Activity", "Report").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            error?.let { Text(it, color = AjiriwaColors.Danger, modifier = Modifier.padding(16.dp)) }
            when (tab) {
                0 -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items.isEmpty() && error == null) item {
                        Text("No stock items yet — add them on the web dashboard (Inventory → Levels & flow).", color = AjiriwaColors.TextSecondary)
                    }
                    items(items, key = { it.getString("id") }) { item -> LevelCard(item) { a -> action = item to a } }
                }
                1 -> Activity(api, reload) { reload++ }
                2 -> Report(api, reload)
            }
        }

        action?.let { (item, a) ->
            ActionDialog(api, item, a, onDone = { action = null; reload++ }, onDismiss = { action = null })
        }
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) "%,.0f".format(v) else "%,.2f".format(v)

    @Composable
    private fun Gauge(label: String, value: Double, pct: Double?, unit: String, color: Color, modifier: Modifier) {
        val fill = ((pct ?: if (value > 0) 100.0 else 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.height(96.dp).width(78.dp).clip(RoundedCornerShape(12.dp)).background(AjiriwaColors.SurfaceAlt)
                    .border(1.dp, AjiriwaColors.Border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(fill).background(if (value < 0) AjiriwaColors.Danger else color))
                pct?.let { Text("${it.toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center)) }
            }
            Text("${fmt(value)} $unit", color = if (value < 0) AjiriwaColors.Danger else AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(label, color = AjiriwaColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        }
    }

    @Composable
    private fun LevelCard(item: JSONObject, onAction: (String) -> Unit) {
        val unit = item.optString("unit")
        val bulk = item.optString("kind") == "BULK"
        val flags = item.optJSONArray("flags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AjiriwaColors.Surface).padding(14.dp)) {
            Text(item.optString("name"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (item.optBoolean("meter_enabled")) Text("Meter ${item.optString("meter_number")} · last ${fmt(item.optDouble("last_meter_reading", 0.0))}", color = AjiriwaColors.TextMuted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Gauge(item.optString("storage_label"), item.optDouble("storage_qty", 0.0), item.optDouble("storage_pct").takeIf { !it.isNaN() }, unit, Color(0xFF64748B), Modifier.weight(1f))
                Text("→", color = AjiriwaColors.TextMuted, fontSize = 22.sp)
                Gauge(item.optString("ready_label"), item.optDouble("ready_qty", 0.0), item.optDouble("ready_pct").takeIf { !it.isNaN() }, unit,
                    if (bulk) Color(0xFF0EA5E9) else Color(0xFF22C55E), Modifier.weight(1f))
            }
            flags.forEach { f ->
                Text(
                    when (f) { "COUNT_NEEDED" -> "Count needed — sold more than recorded"; "REORDER" -> "Reorder — storage low"; else -> "Low ready stock" },
                    color = AjiriwaColors.Warning, fontSize = 12.sp,
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { onAction("RESTOCK") }, label = { Text("Restock") })
                if (item.optBoolean("meter_enabled")) AssistChip(onClick = { onAction("METER") }, label = { Text("Meter reading") })
                AssistChip(onClick = { onAction("TRANSFER") }, label = { Text(item.optString("transfer_label")) })
                AssistChip(onClick = { onAction("SALE") }, label = { Text("Sale") })
                AssistChip(onClick = { onAction("WASTE") }, label = { Text("Loss / spoiled") })
                if (bulk) AssistChip(onClick = { onAction("BACKWASH") }, label = { Text("Backwash") })
                AssistChip(onClick = { onAction("STOCK_TAKE") }, label = { Text("Stock take") })
            }
        }
    }

    @Composable
    private fun ActionDialog(api: Api, item: JSONObject, action: String, onDone: () -> Unit, onDismiss: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val unit = item.optString("unit")
        var amount by remember { mutableStateOf("") }
        var amount2 by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var reason by remember { mutableStateOf(if (item.optString("kind") == "BULK") "LEAK" else "SPOILED") }
        var bucket by remember { mutableStateOf(if (action in listOf("RESTOCK", "BACKWASH")) "storage" else "ready") }
        var saving by remember { mutableStateOf(false) }
        val title = when (action) {
            "RESTOCK" -> "Restock"; "METER" -> "Meter reading"; "TRANSFER" -> item.optString("transfer_label")
            "SALE" -> "Sale"; "WASTE" -> "Loss / spoiled"; "BACKWASH" -> "Backwash"; else -> "Stock take"
        }
        val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("$title — ${item.optString("name")}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${item.optString("storage_label")}: ${fmt(item.optDouble("storage_qty"))} $unit · ${item.optString("ready_label")}: ${fmt(item.optDouble("ready_qty"))} $unit", fontSize = 12.sp)
                    when (action) {
                        "METER" -> {
                            OutlinedTextField(amount, { amount = it }, label = { Text("Meter now shows") }, keyboardOptions = num, singleLine = true)
                            val last = item.optDouble("last_meter_reading")
                            amount.toDoubleOrNull()?.let { r -> if (!last.isNaN() && r > last) Text("${fmt((r - last) * item.optDouble("meter_factor", 1.0))} $unit will move to ${item.optString("ready_label")}", fontSize = 12.sp) }
                        }
                        "STOCK_TAKE" -> {
                            OutlinedTextField(amount, { amount = it }, label = { Text("${item.optString("storage_label")} counted") }, keyboardOptions = num, singleLine = true)
                            OutlinedTextField(amount2, { amount2 = it }, label = { Text("${item.optString("ready_label")} counted") }, keyboardOptions = num, singleLine = true)
                        }
                        else -> OutlinedTextField(amount, { amount = it }, label = { Text("Quantity ($unit)") }, keyboardOptions = num, singleLine = true)
                    }
                    if (action in listOf("WASTE", "BACKWASH")) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = bucket == "storage", onClick = { bucket = "storage" }, label = { Text(item.optString("storage_label")) })
                        FilterChip(selected = bucket == "ready", onClick = { bucket = "ready" }, label = { Text(item.optString("ready_label")) })
                    }
                    if (action == "WASTE") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("SPOILED", "DAMAGED", "EXPIRED", "LEAK", "THEFT", "OTHER").forEach { r ->
                            FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(r.lowercase().replaceFirstChar(Char::uppercase)) })
                        }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && (amount.isNotBlank() || (action == "STOCK_TAKE" && amount2.isNotBlank())), onClick = {
                    saving = true
                    scope.launch {
                        val id = item.getString("id")
                        runCatching {
                            when (action) {
                                "METER" -> api.post("/items/$id/meter", JSONObject().put("reading", amount.toDouble()).put("note", note))
                                "STOCK_TAKE" -> api.post("/items/$id/stock-take", JSONObject().apply {
                                    amount.toDoubleOrNull()?.let { put("storage", it) }; amount2.toDoubleOrNull()?.let { put("ready", it) }; put("note", note)
                                })
                                else -> api.post("/items/$id/movements", JSONObject().apply {
                                    put("type", action); put("quantity", amount.toDouble()); put("bucket", bucket); put("reason", reason)
                                    put("note", note); put("client_ref", "app:${UUID.randomUUID()}")
                                })
                            }
                        }.onSuccess { onDone() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    @Composable
    private fun Activity(api: Api, reload: Int, onChanged: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        LaunchedEffect(reload) {
            rows = runCatching { api.movements().optJSONArray("items") ?: JSONArray() }.getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.getString("id") }) { m ->
                val voided = m.optBoolean("voided")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${m.optString("item_name")} · ${typeLabel(m.optString("type"))}${if (voided) " (reversed)" else ""}",
                            color = if (voided) AjiriwaColors.TextMuted else AjiriwaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${fmt(m.optDouble("quantity"))} ${m.optString("unit")} · ${m.optString("created_at").take(16).replace('T', ' ')} · ${m.optString("recorded_by_name", "")}",
                            color = AjiriwaColors.TextMuted, fontSize = 11.sp)
                        m.optString("note").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, color = AjiriwaColors.TextSecondary, fontSize = 11.sp) }
                    }
                    if (!voided && m.optString("type") != "VOID") TextButton(onClick = {
                        scope.launch {
                            runCatching { api.post("/movements/${m.getString("id")}/void", JSONObject()) }
                                .onSuccess { onChanged() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Reverse", color = AjiriwaColors.Danger, fontSize = 12.sp) }
                }
            }
        }
    }

    private fun typeLabel(t: String) = when (t) {
        "RESTOCK" -> "Restock"; "TRANSFER" -> "Processed / moved"; "SALE" -> "Sale"; "WASTE" -> "Loss"
        "BACKWASH" -> "Backwash"; "ADJUSTMENT" -> "Stock take"; "VOID" -> "Reversal"; else -> t
    }

    @Composable
    private fun Report(api: Api, reload: Int) {
        var days by remember { mutableIntStateOf(7) }
        var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        LaunchedEffect(days, reload) {
            rows = runCatching { api.flow(days).optJSONArray("items") ?: JSONArray() }.getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (d, l) -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) }) }
                }
            }
            items(rows) { r ->
                val it = r.getJSONObject("item")
                val u = it.optString("unit")
                fun agg(k: String) = r.optJSONObject(k)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AjiriwaColors.Surface).padding(12.dp)) {
                    Text(it.optString("name"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold)
                    listOf(
                        "Restocked" to agg("restocked"), "Processed / moved" to agg("transferred"), "Sold" to agg("sold"),
                        "Lost" to agg("wasted"), "Backwash" to agg("backwash"),
                    ).forEach { (label, a) ->
                        if (a != null && a.optDouble("qty") > 0) Row(Modifier.fillMaxWidth()) {
                            Text(label, color = AjiriwaColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("${fmt(a.optDouble("qty"))} $u · ${a.optInt("count")}×", color = AjiriwaColors.TextPrimary, fontSize = 13.sp)
                        }
                    }
                    val variance = r.optDouble("stock_take_variance")
                    if (variance != 0.0) Text("Stock-take variance: ${fmt(variance)} $u", color = if (variance < 0) AjiriwaColors.Danger else AjiriwaColors.Success, fontSize = 12.sp)
                    val c = r.getJSONObject("closing")
                    Text("Now: ${fmt(c.optDouble("storage"))} + ${fmt(c.optDouble("ready"))} $u", color = AjiriwaColors.Primary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
