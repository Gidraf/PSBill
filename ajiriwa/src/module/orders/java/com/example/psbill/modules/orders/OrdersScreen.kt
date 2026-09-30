package com.example.psbill.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.ui.components.EmptyState
import com.example.psbill.ui.components.GlassCard
import com.example.psbill.ui.components.PillChip
import com.example.psbill.ui.components.SectionCard
import com.example.psbill.ui.components.SectionTitle
import com.example.psbill.ui.theme.AjiriwaColors
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val JSON_MT = "application/json; charset=utf-8".toMediaType()
private const val TAG = "OrdersScreen"

/**
 * OrdersScreen — Primary agent screen.
 *
 * Full-featured order management:
 * - Paginated order list (20 per page), status chip filter
 * - Pull-to-refresh (auto-polls every 20s)
 * - Mark order as DELIVERED/PROCESSING (captures device GPS in same PATCH)
 * - STK Push: request M-Pesa payment via POST /api/v1/shop/orders/<id>/checkout-stk
 * - Send Receipt via SMS: POST /api/v1/shop/orders/<id>/send-receipt
 * - Location pin: tap to open Google Maps directions to delivery address
 * - Order detail bottom sheet: line items, customer info, delivery notes
 */
@Composable
fun OrdersScreen(server: String, headers: () -> Headers, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val client = remember { OkHttpClient() }
    val base = remember(server) { "https://${server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}" }

    // ── State ─────────────────────────────────────────────────────────────────
    var orders by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    val perPage = 20
    var statusFilter by remember { mutableStateOf("") } // "" = ALL

    // ── Load Orders ───────────────────────────────────────────────────────────
    fun load(pg: Int = page, status: String = statusFilter) {
        loading = true; error = null
        val url = buildString {
            append("$base/api/v1/shop/orders?page=$pg&per_page=$perPage&tz=Africa/Nairobi")
            if (status.isNotBlank()) append("&status=$status")
        }
        client.newCall(Request.Builder().url(url).headers(headers()).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { loading = false; error = e.message }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    loading = false
                    runCatching {
                        val root = JSONObject(body)
                        val arr = root.optJSONArray("orders") ?: JSONArray()
                        total = root.optInt("total", 0)
                        val list = mutableListOf<JSONObject>()
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                        orders = list
                    }.onFailure { error = "Parse error: ${it.message}" }
                }
            }
        })
    }

    var selectedOrder by remember { mutableStateOf<JSONObject?>(null) }    // detail sheet
    var stkOrder by remember { mutableStateOf<JSONObject?>(null) }         // STK push modal
    var stkPhone by remember { mutableStateOf("") }
    var showCreateOrderScreen by remember { mutableStateOf(false) }

    if (showCreateOrderScreen) {
        CreateOrderScreen(
            server = server,
            headers = headers,
            onOrderCreated = {
                showCreateOrderScreen = false
                load()
            },
            onBack = { showCreateOrderScreen = false }
        )
        return
    }

    val statusChips = listOf("" to "ALL", "PENDING" to "Pending", "PROCESSING" to "Processing",
                             "DELIVERED" to "Delivered", "CANCELLED" to "Cancelled")

    LaunchedEffect(page, statusFilter) { load() }

    // Auto-poll every 20 seconds (same as web)
    LaunchedEffect(page, statusFilter) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            load()
        }
    }

    // ── STK Push ──────────────────────────────────────────────────────────────
    fun triggerStk(orderId: String, phone: String) {
        val payload = JSONObject().apply {
            put("mpesa_number", phone)
        }
        val req = Request.Builder()
            .url("$base/api/v1/shop/orders/$orderId/checkout-stk")
            .post(payload.toString().toRequestBody(JSON_MT))
            .headers(headers())
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "STK Push failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (response.isSuccessful) {
                        Toast.makeText(context, "✅ STK Push sent! Customer will receive M-Pesa prompt.", Toast.LENGTH_LONG).show()
                        stkOrder = null; stkPhone = ""
                    } else {
                        Toast.makeText(context, "STK Push failed (${response.code})", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    // ── Update Status ─────────────────────────────────────────────────────────
    fun updateStatus(
        orderId: String,
        status: String,
        paymentMethod: String = "CASH",
        mpesaCode: String = "",
        mpesaDetails: String = ""
    ) {
        val payload = JSONObject().apply {
            put("status", status)
            put("payment_method", paymentMethod)
            if (mpesaCode.isNotBlank()) put("mpesa_receipt", mpesaCode)
            if (mpesaDetails.isNotBlank()) put("mpesa_details", mpesaDetails)
        }
        val req = Request.Builder()
            .url("$base/api/v1/shop/orders/$orderId/status")
            .patch(payload.toString().toRequestBody(JSON_MT))
            .headers(headers())
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    selectedOrder = null
                    load()
                }
            }
        })
    }


    // ── Send Receipt via SMS ──────────────────────────────────────────────────
    fun sendReceiptSms(orderId: String) {
        val payload = JSONObject().apply {
            put("format", "normal")
            put("channels", JSONArray().apply { put("sms") })
        }
        val req = Request.Builder()
            .url("$base/api/v1/shop/orders/$orderId/send-receipt")
            .post(payload.toString().toRequestBody(JSON_MT))
            .headers(headers())
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "📱 Receipt sent via SMS", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    // ── STK Push Modal ────────────────────────────────────────────────────────
    stkOrder?.let { order ->
        val orderId = order.optString("id")
        val customerPhone = order.optString("user_phone").ifBlank { order.optString("phone", "") }
        LaunchedEffect(orderId) { if (stkPhone.isBlank()) stkPhone = customerPhone }

        AlertDialog(
            onDismissRequest = { stkOrder = null; stkPhone = "" },
            containerColor = AjiriwaColors.Surface,
            title = { Text("Request M-Pesa Payment", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val amount = order.optString("amount").ifBlank { order.optString("total_amount", "—") }
                    Text("Order #${orderId.takeLast(6).uppercase()} • KSh $amount", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
                    Text("An M-Pesa STK Push will be sent to the customer's phone.", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = stkPhone,
                        onValueChange = { stkPhone = it },
                        label = { Text("Customer M-Pesa Number (07...)", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AjiriwaColors.Primary,
                            focusedTextColor = AjiriwaColors.TextPrimary,
                            unfocusedTextColor = AjiriwaColors.TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { if (stkPhone.isNotBlank()) triggerStk(orderId, stkPhone) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                ) { Text("Send STK Push", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { stkOrder = null; stkPhone = "" }) {
                    Text("Cancel", color = AjiriwaColors.TextSecondary)
                }
            }
        )
    }

    // ── Order Detail Sheet ────────────────────────────────────────────────────
    selectedOrder?.let { order ->
        val orderId = order.optString("id")
        val customer = order.optString("whatsapp_name").ifBlank { order.optString("user_phone", "Customer") }
        val customerPhone = order.optString("user_phone", "")
        val amount = order.optString("amount").ifBlank { order.optString("total_amount", "0") }
        val status = order.optString("status", "PENDING")
        val notes = order.optString("notes", "").ifBlank { "No delivery notes" }
        val locDetails = order.optJSONObject("location_details")
        val lat = locDetails?.optDouble("lat", 0.0) ?: 0.0
        val lng = locDetails?.optDouble("lng", 0.0) ?: 0.0
        val locName = locDetails?.optString("name", "").ifNullOrBlank("No location saved")
        val mapsUrl = locDetails?.optString("maps_url", "")

        var paymentMethod by remember { mutableStateOf(order.optString("payment_method").ifBlank { "CASH" }) }
        var mpesaCode by remember { mutableStateOf(order.optString("mpesa_receipt").ifBlank { order.optString("transactionId", "") }) }
        var mpesaDetails by remember { mutableStateOf(order.optString("mpesa_details", "")) }

        val mpesaOptions = remember(amount, customerPhone) {
            fetchDeviceMpesaSms(context, amount, customerPhone)
        }

        val scrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { selectedOrder = null },
            containerColor = AjiriwaColors.Surface,
            title = {
                Text("Order #${orderId.takeLast(6).uppercase()}", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(customer, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("KSh $amount", color = AjiriwaColors.Primary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        }
                        val statusColor = statusChipColor(status)
                        PillChip(status.replaceFirstChar { it.uppercase() }, statusColor)
                    }
                    HorizontalDivider(color = AjiriwaColors.Surface.copy(alpha = 0.4f))

                    // Notes
                    Text("📝 $notes", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)

                    // Map Location Action Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AjiriwaColors.Surface.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .clickable {
                                val uri = when {
                                    !mapsUrl.isNullOrBlank() -> mapsUrl
                                    lat != 0.0 && lng != 0.0 -> "geo:$lat,$lng?q=$lat,$lng($customer)"
                                    else -> "https://maps.google.com/?q=$locName"
                                }
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AjiriwaColors.Primary, modifier = Modifier.size(18.dp))
                            Text(locName, color = AjiriwaColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("📍 Open Map", color = AjiriwaColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = AjiriwaColors.Surface.copy(alpha = 0.4f))

                    // Payment Method Selector
                    Text("Payment Method", color = AjiriwaColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = paymentMethod == "CASH",
                            onClick = { paymentMethod = "CASH" },
                            label = { Text("💵 CASH", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = paymentMethod == "MPESA",
                            onClick = { paymentMethod = "MPESA" },
                            label = { Text("📱 M-PESA", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // M-Pesa Code Input & Auto-suggested SMS List
                    if (paymentMethod == "MPESA") {
                        OutlinedTextField(
                            value = mpesaCode,
                            onValueChange = { mpesaCode = it },
                            label = { Text("M-Pesa Receipt Code (e.g. QHK89234X)", color = Color.Gray, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AjiriwaColors.Primary,
                                focusedTextColor = AjiriwaColors.TextPrimary,
                                unfocusedTextColor = AjiriwaColors.TextPrimary
                            )
                        )
                        OutlinedTextField(
                            value = mpesaDetails,
                            onValueChange = { mpesaDetails = it },
                            label = { Text("Full M-Pesa SMS Details (optional)", color = Color.Gray, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AjiriwaColors.Primary,
                                focusedTextColor = AjiriwaColors.TextPrimary,
                                unfocusedTextColor = AjiriwaColors.TextPrimary
                            )
                        )
                        if (mpesaOptions.isNotEmpty()) {
                            Text("Suggest Recent Device M-Pesa SMS:", color = AjiriwaColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                mpesaOptions.take(3).forEach { opt ->
                                    Surface(
                                        color = if (opt.isMatch) Color(0xFF1B5E20).copy(alpha = 0.4f) else AjiriwaColors.Surface,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (opt.txCode.isNotBlank()) mpesaCode = opt.txCode
                                                mpesaDetails = opt.fullBody
                                                Toast.makeText(context, "M-Pesa details pasted!", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(6.dp)
                                    ) {
                                        Column {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(if (opt.txCode.isNotBlank()) opt.txCode else "M-Pesa SMS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AjiriwaColors.Primary)
                                                if (opt.isMatch) Text("⭐ 90% Match", color = Color(0xFF00C853), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Text(opt.fullBody.take(80) + "...", fontSize = 10.sp, color = AjiriwaColors.TextSecondary, maxLines = 2)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = AjiriwaColors.Surface.copy(alpha = 0.4f))

                    // Delivery Tracking Controls
                    Text("🛵 Delivery & Tracking Controls", color = AjiriwaColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                
                                if (!hasFine && !hasCoarse) {
                                    Toast.makeText(context, "Location permission required for delivery tracking", Toast.LENGTH_LONG).show()
                                    return@Button
                                }

                                val intent = Intent(context, com.example.psbill.DeliveryTrackingService::class.java).apply {
                                    action = com.example.psbill.DeliveryTrackingService.ACTION_START_DELIVERY
                                    putExtra("order_id", orderId)
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }

                                // Directly notify backend API to start delivery tracking session & send SMS
                                val sessionBody = JSONObject().apply { put("action", "start") }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                                val sessionReq = Request.Builder()
                                    .url("$base/api/v1/delivery/orders/$orderId/session")
                                    .headers(headers())
                                    .post(sessionBody)
                                    .build()

                                client.newCall(sessionReq).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {
                                        Log.e(TAG, "Backend delivery session start failed: ${e.message}")
                                    }
                                    override fun onResponse(call: Call, response: Response) {
                                        Log.d(TAG, "Backend delivery session start response: ${response.code}")
                                        response.close()
                                    }
                                })

                                updateStatus(orderId, "PROCESSING", paymentMethod, mpesaCode, mpesaDetails)
                                Toast.makeText(context, "🛵 Delivery Tracking Started & SMS Sent!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                            modifier = Modifier.weight(1f)
                        ) { Text("▶️ Start Delivery", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                        Button(
                            onClick = {
                                val intent = Intent(context, com.example.psbill.DeliveryTrackingService::class.java).apply {
                                    action = com.example.psbill.DeliveryTrackingService.ACTION_STOP_DELIVERY
                                }
                                context.startService(intent)
                                updateStatus(orderId, "DELIVERED", paymentMethod, mpesaCode, mpesaDetails)
                                Toast.makeText(context, "✅ Delivery Session Completed", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                            modifier = Modifier.weight(1f)
                        ) { Text("✅ Mark Delivered", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }

                    HorizontalDivider(color = AjiriwaColors.Surface.copy(alpha = 0.4f))

                    // Order Actions
                    Text("Order Actions", color = AjiriwaColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { updateStatus(orderId, "DELIVERED", paymentMethod, mpesaCode, mpesaDetails) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                            modifier = Modifier.weight(1f)
                        ) { Text("✅ Delivered", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { updateStatus(orderId, "PROCESSING", paymentMethod, mpesaCode, mpesaDetails) },
                            colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Primary),
                            modifier = Modifier.weight(1f)
                        ) { Text("🚀 Processing", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                com.example.psbill.core.AndroidPrintHelper.printOrderReceipt(
                                    context = context,
                                    orderId = orderId,
                                    customer = customer,
                                    amount = amount,
                                    status = status,
                                    paymentMethod = paymentMethod,
                                    mpesaCode = mpesaCode
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                            modifier = Modifier.weight(1f)
                        ) { Text("🖨️ Print Receipt", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { sendReceiptSms(orderId) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1)),
                            modifier = Modifier.weight(1f)
                        ) { Text("📱 SMS Receipt", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedOrder = null }) {
                    Text("Close", color = AjiriwaColors.TextSecondary)
                }
            }
        )
    }

    // ── Main UI ───────────────────────────────────────────────────────────────
    Column(modifier.fillMaxSize().padding(16.dp)) {
        // Header + refresh + Create Order
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Order Items & Ledger", "$total total orders • Order dispatch & payment ledger.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { showCreateOrderScreen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("+ Create Order", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                IconButton(onClick = { load() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = AjiriwaColors.PrimaryAccent)
                }
            }
        }
        Spacer(Modifier.height(10.dp))


        // Status filter chips
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(statusChips) { (value, label) ->
                val selected = statusFilter == value
                Surface(
                    color = if (selected) AjiriwaColors.Primary else AjiriwaColors.Surface,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { statusFilter = value; page = 1 }
                ) {
                    Text(
                        label,
                        color = if (selected) Color.Black else AjiriwaColors.TextSecondary,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Content
        when {
            loading && orders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AjiriwaColors.Primary)
            }
            error != null -> EmptyState(Icons.Filled.ShoppingCart, "Couldn't load orders", error!!)
            orders.isEmpty() -> EmptyState(Icons.Filled.ShoppingCart, "No orders", "No ${statusFilter.lowercase().ifBlank { "" }} orders found.")
            else -> {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(orders) { order -> OrderCard(order, onTap = { selectedOrder = order }) }
                }

                // Pagination
                if (total > perPage) {
                    val totalPages = (total + perPage - 1) / perPage
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { if (page > 1) { page--; load() } }, enabled = page > 1) {
                            Text("← Prev", color = if (page > 1) AjiriwaColors.Primary else Color.Gray)
                        }
                        Text("Page $page / $totalPages", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                        TextButton(onClick = { if (page < totalPages) { page++; load() } }, enabled = page < totalPages) {
                            Text("Next →", color = if (page < totalPages) AjiriwaColors.Primary else Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderCard(order: JSONObject, onTap: () -> Unit) {
    val id = order.optString("id").takeLast(6).uppercase()
    val customer = order.optString("whatsapp_name").ifBlank { order.optString("user_phone").ifBlank { "Customer" } }
    val amount = order.optString("amount").ifBlank { order.optString("total_amount", "0") }
    val status = order.optString("status", "PENDING")
    val locDetails = order.optJSONObject("location_details")
    val hasLocation = locDetails != null && (locDetails.optDouble("lat", 0.0) != 0.0)

    GlassCard(
        modifier = Modifier.clickable { onTap() },
        borderColor = AjiriwaColors.Border,
        backgroundColor = AjiriwaColors.Surface.copy(alpha = 0.7f)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Order #$id", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (hasLocation) Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AjiriwaColors.PrimaryAccent, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(customer, color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "KSh $amount",
                    color = AjiriwaColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                PillChip(status.replaceFirstChar { it.uppercase() }, statusChipColor(status), showDot = true)
            }
        }
    }
}

private fun statusChipColor(status: String): Color = when (status.uppercase()) {
    "DELIVERED", "COMPLETED", "PAID" -> AjiriwaColors.Success
    "PENDING" -> AjiriwaColors.Warning
    "PROCESSING", "AWAITING_DELIVERY" -> Color(0xFF3B82F6)
    "CANCELLED", "FAILED" -> AjiriwaColors.Danger
    else -> AjiriwaColors.TextSecondary
}


private fun String?.ifNullOrBlank(default: String): String =
    if (isNullOrBlank()) default else this!!

data class MpesaOption(
    val txCode: String,
    val amount: String,
    val sender: String,
    val fullBody: String,
    val isMatch: Boolean
)

fun fetchDeviceMpesaSms(context: Context, orderAmount: String, customerPhone: String): List<MpesaOption> {
    val list = mutableListOf<MpesaOption>()
    try {
        val uri = Uri.parse("content://sms/inbox")
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("address", "body", "date"),
            "address LIKE ? OR body LIKE ?",
            arrayOf("%MPESA%", "%Ksh%"),
            "date DESC"
        )
        val cleanOrderAmt = orderAmount.replace(",", "").toDoubleOrNull() ?: -1.0
        val cleanPhone = customerPhone.replace("+", "").takeLast(9)

        cursor?.use {
            val bodyIdx = it.getColumnIndex("body")
            val addrIdx = it.getColumnIndex("address")
            var count = 0
            while (it.moveToNext() && count < 15) {
                count++
                val body = if (bodyIdx != -1) it.getString(bodyIdx) else ""
                val addr = if (addrIdx != -1) it.getString(addrIdx) else "MPESA"

                if (body.contains("Confirmed", ignoreCase = true) || body.contains("Ksh", ignoreCase = true) || addr.contains("MPESA", ignoreCase = true)) {
                    val pattern = java.util.regex.Pattern.compile("([A-Z0-9]{10})\\s+Confirmed\\.\\s+Ksh([0-9\\.,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    val matcher = pattern.matcher(body)
                    var code = ""
                    var amtStr = ""
                    if (matcher.find()) {
                        code = matcher.group(1) ?: ""
                        amtStr = matcher.group(2) ?: ""
                    }
                    val numAmt = amtStr.replace(",", "").toDoubleOrNull() ?: -1.0
                    val isAmtMatch = cleanOrderAmt > 0 && Math.abs(cleanOrderAmt - numAmt) < 0.01
                    val isPhoneMatch = cleanPhone.isNotBlank() && body.contains(cleanPhone)
                    val isMatch = isAmtMatch || isPhoneMatch

                    list.add(MpesaOption(code, amtStr, addr, body, isMatch))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

