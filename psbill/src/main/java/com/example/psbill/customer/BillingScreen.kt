package com.example.psbill.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Billing (owner): next month's estimate, invoices (pay by M-Pesa or tokens), token balance + top-up.
 * Amounts always come from the server (psbill_billing.quote / the invoice), never typed here.
 */
@Composable
fun BillingScreen(onBack: () -> Unit) {
    val api = LocalPsApi.current ?: return
    var invoices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var estimate by remember { mutableStateOf<JSONObject?>(null) }
    var wallet by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf<JSONObject?>(null) }
    var topup by remember { mutableStateOf(false) }
    var making by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    fun load() {
        loading = true; error = null
        api.get("/api/v1/billing/invoices") { code, body ->
            loading = false
            if (code in 200..299) runCatching {
                val o = JSONObject(body)
                invoices = objects(o.optJSONArray("data"))
                summary = o.optJSONObject("summary")
                estimate = o.optJSONObject("estimate")
            } else error = api.errorText(code, body, "Could not load invoices")
        }
        api.get("/api/v1/billing/wallet") { code, body ->
            if (code in 200..299) wallet = runCatching { JSONObject(body).optJSONObject("wallet") }.getOrNull()
        }
    }
    LaunchedEffect(refreshTick) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PsSubHeader("Billing", "Your PSBill plan and invoices", onBack) {
            IconButton(onClick = { refreshTick++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
        val wide = isWide()
        val est: @Composable (Modifier) -> Unit = { m -> EstimateCard(m, estimate) }
        val bal: @Composable (Modifier) -> Unit = { m -> WalletCard(m, wallet) { topup = true } }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { est(Modifier.weight(1f)); bal(Modifier.weight(1f)) }
        else { est(Modifier.fillMaxWidth()); bal(Modifier.fillMaxWidth()) }

        summary?.takeIf { it.optInt("unpaid_count") > 0 }?.let { s ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PsTone.Warn.copy(alpha = 0.14f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = PsTone.Warn)
                Spacer(Modifier.width(10.dp))
                Text("${s.optInt("unpaid_count")} unpaid invoice(s): KES ${kesText(s.optDouble("unpaid_kes"))}" +
                    if (s.optInt("overdue_count") > 0) " - ${s.optInt("overdue_count")} overdue" else "",
                    color = PSBillThemeColors.TextPrimary, fontSize = 13.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            PsSectionLabel("Invoices", invoices.size)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                making = true
                api.post("/api/v1/billing/invoices/generate", JSONObject()) { code, body ->
                    making = false
                    if (code in 200..299) { api.toast("This month's invoice is ready"); refreshTick++ }
                    else api.toast(api.errorText(code, body, "Could not make the invoice"))
                }
            }, enabled = !making) { Text(if (making) "Making..." else "Make this month's invoice") }
        }
        PsLoadState(loading, error, invoices.isEmpty(), "No invoices yet. The first one comes on the 1st of next month.", { refreshTick++ }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                invoices.forEach { inv -> InvoiceRow(inv) { paying = inv } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    paying?.let { inv -> PayInvoiceSheet(inv, wallet, onDismiss = { paying = null }) { paying = null; refreshTick++ } }
    if (topup) TopupSheet(onDismiss = { topup = false }) { topup = false; refreshTick++ }
}

private fun kesText(v: Double): String = if (v.isNaN()) "0" else String.format(java.util.Locale.US, "%,.0f", v)

@Composable
private fun EstimateCard(modifier: Modifier, est: JSONObject?) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(PSBillThemeColors.Surface).padding(16.dp)) {
        Text("Next invoice", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        if (est == null) { Text("-", color = PSBillThemeColors.TextPrimary, fontSize = 26.sp); return@Column }
        Text(if (est.optBoolean("free")) "Free" else "KES ${kesText(est.optDouble("total_kes"))}",
            color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Text("${est.optInt("screens")} screen(s) - KES ${kesText(est.optDouble("screens_kes"))}" +
            (if (est.optBoolean("wifi_in_use")) " + Wi-Fi KES ${kesText(est.optDouble("wifi_kes"))}" else ""),
            color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
        est.optString("next_invoice_date").takeIf { it.isNotBlank() }?.let {
            Text("On $it", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WalletCard(modifier: Modifier, wallet: JSONObject?, onTopup: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(PSBillThemeColors.Surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tokens", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            val t = wallet?.optInt("token_balance") ?: 0
            Text(String.format(java.util.Locale.US, "%,d", t), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text("= KES ${kesText(t / 10.0)} - pay invoices or SMS with them", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        }
        FilledTonalButton(onClick = onTopup) { Text("Top up") }
    }
}

@Composable
private fun InvoiceRow(inv: JSONObject, onPay: () -> Unit) {
    val status = inv.optString("status", "UNPAID")
    val overdue = inv.optBoolean("overdue")
    val tone = when { status == "PAID" -> PsTone.Good; status == "VOID" -> PsTone.Idle; overdue -> PsTone.Bad; else -> PsTone.Warn }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PSBillThemeColors.Surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(inv.optString("number").ifBlank { "Invoice" }, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("${inv.optString("period_start")} to ${inv.optString("period_end")} - ${inv.optInt("screens")} screen(s)",
                color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            PsPill(if (overdue && status == "UNPAID") "Overdue" else status.lowercase().replaceFirstChar { it.uppercase() }, tone)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("KES ${kesText(inv.optDouble("total_amount_kes"))}", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
            if (status == "UNPAID") Button(onClick = onPay, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Pay") }
        }
    }
}

@Composable
private fun PayInvoiceSheet(inv: JSONObject, wallet: JSONObject?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val api = LocalPsApi.current ?: return
    val needed = inv.optInt("tokens_needed", (inv.optDouble("total_amount_kes") * 10).toInt())
    val balance = wallet?.optInt("token_balance") ?: 0
    var method by remember { mutableStateOf(if (balance >= needed) "tokens" else "mpesa") }
    var phone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // After an M-Pesa prompt: check the invoice every 5 s for 2 minutes
    LaunchedEffect(waiting) {
        if (!waiting) return@LaunchedEffect
        repeat(24) {
            delay(5_000)
            var paid = false
            api.get("/api/v1/billing/invoices/${inv.optString("id")}") { code, body ->
                if (code in 200..299 && runCatching { JSONObject(body).optString("status") }.getOrNull() == "PAID") paid = true
            }
            delay(1_500)
            if (paid) { api.toast("Invoice paid - thank you"); onDone(); return@LaunchedEffect }
        }
        waiting = false
        error = "No payment yet. If you paid, it shows here in a few minutes."
    }

    PsFormSheet("Pay ${inv.optString("number")}", onDismiss, if (method == "tokens") "Pay with tokens" else "Send M-Pesa prompt",
        busy = busy || waiting,
        confirmEnabled = method == "tokens" && balance >= needed || method == "mpesa" && Regex("^(\\+?254|0)[17][0-9]{8}$").matches(phone.trim()),
        onConfirm = {
            busy = true; error = null
            val body = JSONObject().put("method", method).apply { if (method == "mpesa") put("phone", phone.trim()) }
            api.post("/api/v1/billing/invoices/${inv.optString("id")}/pay", body) { code, resp ->
                busy = false
                if (code !in 200..299) { error = api.errorText(code, resp, "Payment failed"); return@post }
                if (method == "tokens") { api.toast("Paid with tokens"); onDone() }
                else { api.toast("Check your phone and enter your M-Pesa PIN"); waiting = true }
            }
        }) {
        Text("KES ${kesText(inv.optDouble("total_amount_kes"))}", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        PsChips(listOf("mpesa" to "M-Pesa", "tokens" to "Tokens (${String.format(java.util.Locale.US, "%,d", balance)})"), method, { method = it })
        if (method == "tokens") {
            Text(if (balance >= needed) "Uses $needed tokens from your balance." else "You need $needed tokens. Top up first or pay with M-Pesa.",
                color = if (balance >= needed) PSBillThemeColors.TextSecondary else PsTone.Warn, fontSize = 13.sp)
        } else {
            OutlinedTextField(phone, { phone = it }, label = { Text("M-Pesa number") }, placeholder = { Text("07XX XXX XXX") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            if (waiting) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Waiting for the payment...", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
            }
        }
        error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
    }
}

@Composable
private fun TopupSheet(onDismiss: () -> Unit, onDone: () -> Unit) {
    val api = LocalPsApi.current ?: return
    var amount by remember { mutableStateOf("500") }
    var phone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val kes = amount.toIntOrNull() ?: 0
    PsFormSheet("Top up tokens", onDismiss, "Send M-Pesa prompt", busy = busy,
        confirmEnabled = kes >= 50 && Regex("^(\\+?254|0)[17][0-9]{8}$").matches(phone.trim()),
        onConfirm = {
            busy = true; error = null
            api.post("/api/v1/billing/topup", JSONObject().put("phone", phone.trim()).put("amount_kes", kes)) { code, body ->
                busy = false
                if (code in 200..299) { api.toast("Check your phone and enter your M-Pesa PIN"); onDone() }
                else error = api.errorText(code, body, "Could not start the top-up")
            }
        }) {
        PsChips(listOf("200" to "KES 200", "500" to "KES 500", "1000" to "KES 1,000", "2000" to "KES 2,000"), amount, { amount = it })
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Amount (KES)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), isError = kes in 1..49,
            supportingText = { Text("KES 1 = 10 tokens. At least KES 50.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(phone, { phone = it }, label = { Text("M-Pesa number") }, placeholder = { Text("07XX XXX XXX") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
    }
}
