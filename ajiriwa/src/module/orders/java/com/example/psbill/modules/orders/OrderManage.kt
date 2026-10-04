package com.example.psbill.ui.screens

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.ui.theme.AjiriwaColors
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val JSON = "application/json; charset=utf-8".toMediaType()
private val main = Handler(Looper.getMainLooper())

/** Small async JSON call with the result (ok, body) delivered on the main thread. */
internal fun orderCall(base: String, headers: Headers, path: String, method: String, body: JSONObject?, done: (Boolean, String, Int) -> Unit) {
    val b = Request.Builder().url("$base$path").headers(headers)
    when (method) {
        "GET" -> b.get()
        else -> b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
    }
    com.example.psbill.core.ActivityLog.client.newCall(b.build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { main.post { done(false, "No connection: ${e.message}", 0) } }
        override fun onResponse(call: Call, response: Response) {
            val text = response.body?.string().orEmpty()
            val ok = response.isSuccessful
            val code = response.code
            response.close()
            main.post { done(ok, text, code) }
        }
    })
}

private data class Line(val productId: String, val name: String, val qty: Int, val price: Double)

/**
 * Everything the web order drawer does: items (edit with reason, stock-checked),
 * record payment (cash / M-Pesa code), reschedule delivery, cancel.
 */
@Composable
fun OrderManageDialog(base: String, headers: () -> Headers, order: JSONObject, onClose: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val orderId = order.optString("id")
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() = orderCall(base, headers(), "/api/v1/shop/orders/$orderId", "GET", null) { ok, text, code ->
        if (ok) detail = runCatching { JSONObject(text) }.getOrNull()?.let { it.optJSONObject("order") ?: it }
        else problem = orderErrorText(text, code)
    }
    LaunchedEffect(orderId) { reload() }
    val d = detail ?: order
    val status = d.optString("status", "PENDING").uppercase()
    val editable = status == "PENDING" || status == "PROCESSING"

    fun done(ok: Boolean, text: String, code: Int, msg: String) {
        busy = false
        if (ok) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show(); reload(); onChanged() }
        else problem = orderErrorText(text, code)
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = AjiriwaColors.Surface,
        title = { Text("Manage order #${orderId.takeLast(6).uppercase()}", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp, containerColor = AjiriwaColors.Surface) {
                    listOf("Items", "Payment", "Schedule", "Cancel").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 12.sp) }) }
                }
                Text("Status: $status · KSh ${d.optString("amount")}", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                when (tab) {
                    0 -> ItemsEditor(base, headers, d, editable, busy) { items, reason ->
                        busy = true
                        orderCall(base, headers(), "/api/v1/shop/orders/$orderId/items", "PUT",
                            JSONObject().put("items", items).put("reason", reason)) { ok, t, c -> done(ok, t, c, "Items updated") }
                    }
                    1 -> PaymentForm(d, busy) { method, amount, codeTxt ->
                        busy = true
                        orderCall(base, headers(), "/api/v1/shop/orders/$orderId/payment", "POST", JSONObject().apply {
                            put("method", method); put("amount", amount)
                            if (codeTxt.isNotBlank()) put("transaction_code", codeTxt.trim().uppercase())
                        }) { ok, t, c -> done(ok, t, c, "Payment recorded") }
                    }
                    2 -> RescheduleForm(d, busy) { iso ->
                        busy = true
                        orderCall(base, headers(), "/api/v1/shop/orders/$orderId/reschedule", "PATCH",
                            JSONObject().put("scheduled_delivery_date", iso)) { ok, t, c -> done(ok, t, c, "Delivery rescheduled") }
                    }
                    3 -> {
                        Text("Cancelling returns the stock taken by this order and tells the customer if status SMS are on.",
                            color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
                        Button(enabled = editable && !busy, onClick = {
                            busy = true
                            orderCall(base, headers(), "/api/v1/shop/orders/$orderId/status", "PATCH",
                                JSONObject().put("status", "CANCELLED")) { ok, t, c -> done(ok, t, c, "Order cancelled") }
                        }, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Danger), modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel this order")
                        }
                        if (!editable) Text("Only pending or processing orders can be cancelled.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = AjiriwaColors.TextSecondary) } },
    )
    problem?.let { msg ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Not saved") },
            text = { Text(msg, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ItemsEditor(base: String, headers: () -> Headers, order: JSONObject, editable: Boolean, busy: Boolean, save: (JSONArray, String) -> Unit) {
    val start = remember(order.optString("id"), order.optString("amount")) {
        val arr = order.optJSONArray("item") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Line(it.optString("product_id"), it.optString("product_name", it.optString("name")), it.optInt("quantity", 1), it.optDouble("unit_price", 0.0).takeIf { p -> !p.isNaN() } ?: 0.0)
        }
    }
    var lines by remember(start) { mutableStateOf(start) }
    var reason by remember { mutableStateOf("") }
    var products by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    LaunchedEffect(editable) {
        if (editable) orderCall(base, headers(), "/api/v1/products?limit=200", "GET", null) { ok, text, _ ->
            if (ok) runCatching {
                val root = if (text.trimStart().startsWith("[")) JSONObject().put("products", JSONArray(text)) else JSONObject(text)
                val arr = root.optJSONArray("products") ?: root.optJSONArray("items") ?: JSONArray()
                products = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
        }
    }
    lines.forEachIndexed { i, l ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(l.name.ifBlank { "Product" }, color = AjiriwaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (l.price > 0) Text("KSh ${"%.0f".format(l.price)} each", color = AjiriwaColors.TextMuted, fontSize = 11.sp)
            }
            if (editable) {
                TextButton(onClick = { lines = lines.mapIndexed { j, x -> if (j == i) x.copy(qty = (x.qty - 1).coerceAtLeast(0)) else x }.filter { it.qty > 0 } }) { Text("−") }
                Text("${l.qty}", color = AjiriwaColors.TextPrimary)
                TextButton(onClick = { lines = lines.mapIndexed { j, x -> if (j == i) x.copy(qty = x.qty + 1) else x } }) { Text("+") }
            } else Text("× ${l.qty}", color = AjiriwaColors.TextPrimary)
        }
    }
    if (!editable) { Text("Delivered / cancelled orders can't be edited.", color = AjiriwaColors.TextMuted, fontSize = 12.sp); return }
    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true, label = { Text("Add a product") }, modifier = Modifier.fillMaxWidth())
    if (search.isNotBlank()) {
        products.filter { it.optString("name").contains(search, true) }.take(6).forEach { p ->
            Text(p.optString("name"), color = AjiriwaColors.Primary, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable {
                val id = p.optString("id")
                lines = if (lines.any { it.productId == id }) lines.map { if (it.productId == id) it.copy(qty = it.qty + 1) else it }
                else lines + Line(id, p.optString("name"), 1, p.optDouble("price", 0.0).takeIf { v -> !v.isNaN() } ?: 0.0)
                search = ""
            }.padding(vertical = 6.dp))
        }
    }
    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason for the change (required)") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy && reason.isNotBlank() && lines.isNotEmpty() && lines != start, modifier = Modifier.fillMaxWidth(), onClick = {
        save(JSONArray().apply { lines.forEach { put(JSONObject().put("product_id", it.productId).put("quantity", it.qty)) } }, reason.trim())
    }) { Text("Save items") }
}

@Composable
private fun PaymentForm(order: JSONObject, busy: Boolean, save: (String, Double, String) -> Unit) {
    var method by remember { mutableStateOf(order.optString("payment_method").ifBlank { "CASH" }.uppercase()) }
    var amount by remember { mutableStateOf(order.optString("amount")) }
    var code by remember { mutableStateOf("") }
    order.optString("mpesa_receipt").takeIf { it.isNotBlank() && it != "null" }?.let {
        Text("Already paid: $it", color = AjiriwaColors.Success, fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("CASH", "MPESA").forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(if (m == "MPESA") "M-Pesa" else "Cash") }) }
    }
    OutlinedTextField(value = amount, onValueChange = { amount = it }, singleLine = true, label = { Text("Amount (KSh)") }, modifier = Modifier.fillMaxWidth())
    if (method == "MPESA") OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, label = { Text("M-Pesa code") }, modifier = Modifier.fillMaxWidth())
    val amt = amount.toDoubleOrNull()
    Button(enabled = !busy && amt != null && amt > 0 && (method == "CASH" || code.length >= 8), modifier = Modifier.fillMaxWidth(),
        onClick = { save(method, amt ?: 0.0, code) }) { Text("Record payment") }
}

@Composable
private fun RescheduleForm(order: JSONObject, busy: Boolean, save: (String) -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    val initial = remember {
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0) }.time
    }
    var text by remember { mutableStateOf(fmt.format(initial)) }
    order.optString("scheduled_delivery_date").takeIf { it.isNotBlank() && it != "null" }?.let {
        Text("Currently: ${it.take(16).replace('T', ' ')} UTC", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Today 5pm" to (0 to 17), "Tomorrow 10am" to (1 to 10), "In 2 days" to (2 to 10)).forEach { (label, v) ->
            AssistChip(onClick = {
                text = fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, v.first); set(Calendar.HOUR_OF_DAY, v.second); set(Calendar.MINUTE, 0) }.time)
            }, label = { Text(label, fontSize = 11.sp) })
        }
    }
    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Deliver at (YYYY-MM-DD HH:mm)") }, modifier = Modifier.fillMaxWidth())
    val parsed = runCatching { fmt.parse(text) }.getOrNull()
    Button(enabled = !busy && parsed != null, modifier = Modifier.fillMaxWidth(), onClick = {
        // phone local time → ISO with offset; the server stores UTC
        val off = java.util.TimeZone.getDefault().getOffset(parsed!!.time) / 60_000
        val sign = if (off < 0) "-" else "+"
        save(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(parsed) + "%s%02d:%02d".format(sign, Math.abs(off) / 60, Math.abs(off) % 60))
    }) { Text("Reschedule delivery") }
}
