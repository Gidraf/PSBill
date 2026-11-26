package com.example.psbill.ui.screens

import android.widget.Toast
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
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject

/**
 * Walk-in cash sale for the attendant: pick the products, Paid cash = a DELIVERED order (CASH-xxxx) so the stock
 * and the sales/prediction numbers see it. Backend: POST /api/v1/shop/orders/cash-sale (cash_sale.record).
 */
@Composable
fun CashSaleDialog(base: String, headers: () -> Headers, onClose: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var products by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var qty by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var received by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        orderCall(base, headers(), "/api/v1/shop/orders/cash-sale/options", "GET", null) { ok, text, code ->
            loading = false
            if (ok) runCatching {
                val arr = JSONObject(text).optJSONArray("products") ?: JSONArray()
                products = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } else problem = orderErrorText(text, code)
        }
    }
    val total = products.sumOf { (qty[it.optString("id")] ?: 0) * it.optDouble("price", 0.0) }
    val cash = received.trim().toDoubleOrNull()
    val short = cash != null && cash + 0.01 < total

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        containerColor = AjiriwaColors.Surface,
        title = { Text("Cash sale", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loading) CircularProgressIndicator(color = AjiriwaColors.Primary)
                problem?.let { Text(it, color = AjiriwaColors.Danger, fontSize = 13.sp) }
                if (!loading && products.isEmpty() && problem == null)
                    Text("No products with a price.", color = AjiriwaColors.TextMuted, fontSize = 13.sp)
                products.forEach { p ->
                    val id = p.optString("id")
                    val q = qty[id] ?: 0
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.optString("name"), color = AjiriwaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("KSh ${"%.0f".format(p.optDouble("price", 0.0))}", color = AjiriwaColors.TextMuted, fontSize = 11.sp)
                        }
                        TextButton(enabled = q > 0, onClick = { qty = qty + (id to (q - 1).coerceAtLeast(0)) }) { Text("−") }
                        Text("$q", color = AjiriwaColors.TextPrimary)
                        TextButton(onClick = { qty = qty + (id to q + 1) }) { Text("+") }
                    }
                }
                Text("Total KSh ${"%.0f".format(total)}", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = received, onValueChange = { received = it.filter { c -> c.isDigit() || c == '.' } },
                    singleLine = true, label = { Text("Cash received (optional)") }, modifier = Modifier.fillMaxWidth())
                if (cash != null) Text(
                    if (short) "Short by KSh ${"%.0f".format(total - cash)}" else "Change KSh ${"%.0f".format(cash - total)}",
                    color = if (short) AjiriwaColors.Danger else AjiriwaColors.Success, fontSize = 13.sp)
            }
        },
        confirmButton = {
            Button(enabled = !busy && total > 0 && !short, onClick = {
                busy = true; problem = null
                val items = JSONArray()
                qty.filterValues { it > 0 }.forEach { (id, q) -> items.put(JSONObject().put("product_id", id).put("quantity", q)) }
                val body = JSONObject().put("items", items).apply { if (cash != null) put("received", cash) }
                orderCall(base, headers(), "/api/v1/shop/orders/cash-sale", "POST", body) { ok, text, code ->
                    busy = false
                    if (ok) {
                        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                        Toast.makeText(context, msg.ifBlank { "Cash sale saved" }, Toast.LENGTH_SHORT).show()
                        onDone()
                    } else problem = orderErrorText(text, code)
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Success)) {
                Text(if (busy) "Saving..." else "Paid cash")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Close", color = AjiriwaColors.TextSecondary) } },
    )
}
