package com.example.psbill.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.ui.theme.AjiriwaColors
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val TAG = "CreateOrderScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateOrderScreen(
    server: String,
    headers: () -> Headers,
    onOrderCreated: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val client = remember { com.example.psbill.core.ActivityLog.client }
    val base = remember(server) { "https://${server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}" }

    // Retrieve auth token from SharedPreferences as fallback
    val prefs = remember { context.getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE) }
    val storedToken = remember { prefs.getString("auth_token", "") ?: prefs.getString("jwt_token", "") ?: "" }

    fun getEffectiveHeaders(): Headers {
        val hBuilder = headers().newBuilder()
        if (headers()["Authorization"].isNull_or_empty() && storedToken.isNotEmpty()) {
            hBuilder.set("Authorization", "Bearer $storedToken")
        }
        return hBuilder.build()
    }

    // Step state (0: Customer, 1: Products, 2: Location & Checkout)
    var currentStep by remember { mutableIntStateOf(0) }

    // Customer states
    var searchQuery by remember { mutableStateOf("") }
    var selectedCustomer by remember { mutableStateOf<JSONObject?>(null) }
    var customersList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isSearchingCustomer by remember { mutableStateOf(false) }

    // Create New Customer Dialog
    var showCreateCustDialog by remember { mutableStateOf(false) }
    var newCustName by remember { mutableStateOf("") }
    var newCustPhone by remember { mutableStateOf("") }
    var isCreatingCust by remember { mutableStateOf(false) }

    // Products states
    var productSearchQuery by remember { mutableStateOf("") }
    var productsList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var serverProblem by remember { mutableStateOf<String?>(null) }   // e.g. STOCK_TOO_LOW
    var isFetchingProducts by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateListOf<JSONObject>() }

    // Location & Delivery states
    var locationName by remember { mutableStateOf("") }
    var capturedLat by remember { mutableStateOf<Double?>(null) }
    var capturedLng by remember { mutableStateOf<Double?>(null) }
    var isLocating by remember { mutableStateOf(false) }

    // Payment states
    var paymentMethod by remember { mutableStateOf("STK") } // "STK" | "CASH"
    var mpesaPhone by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    // Load initial products & customers
    fun searchCustomers(q: String) {
        isSearchingCustomer = true
        val url = if (q.isBlank()) "$base/api/v1/all-whatsapp-users?page=1&limit=20"
                  else "$base/api/v1/all-whatsapp-users?whatsappNumber=$q&q=$q"

        val request = Request.Builder().url(url).headers(getEffectiveHeaders()).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { isSearchingCustomer = false }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isSearchingCustomer = false
                    if (response.isSuccessful) {
                        runCatching {
                            val root = JSONObject(body ?: "{}")
                            val arr = root.optJSONArray("results")
                                ?: root.optJSONArray("whatsapp_users")
                                ?: root.optJSONArray("data")
                                ?: JSONArray()
                            val list = mutableListOf<JSONObject>()
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                            customersList = list
                        }
                    }
                    response.close()
                }
            }
        })
    }

    fun loadProducts() {
        isFetchingProducts = true
        val url = "$base/api/v1/products?limit=100"
        val request = Request.Builder().url(url).headers(getEffectiveHeaders()).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { isFetchingProducts = false }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isFetchingProducts = false
                    if (response.isSuccessful) {
                        runCatching {
                            val root = JSONObject(body ?: "{}")
                            val arr = root.optJSONArray("products")
                                ?: root.optJSONArray("items")
                                ?: root.optJSONArray("data")
                                ?: (if (body?.startsWith("[") == true) JSONArray(body) else JSONArray())
                            val list = mutableListOf<JSONObject>()
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                            productsList = list
                        }
                    }
                    response.close()
                }
            }
        })
    }

    LaunchedEffect(Unit) {
        searchCustomers("")
        loadProducts()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) searchCustomers(searchQuery)
        else if (searchQuery.isEmpty()) searchCustomers("")
    }

    // Capture GPS Location
    fun captureGpsLocation() {
        isLocating = true
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                capturedLat = loc.latitude
                capturedLng = loc.longitude
                if (locationName.isBlank()) locationName = "GPS Pin (${loc.latitude.toString().take(7)}, ${loc.longitude.toString().take(7)})"
                Toast.makeText(context, "📍 GPS Location Captured!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Location unavailable. Ensure GPS is enabled.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Location permission required", Toast.LENGTH_SHORT).show()
        } finally {
            isLocating = false
        }
    }

    val filteredProducts = productsList.filter {
        val name = nameOfProduct(it).lowercase()
        productSearchQuery.isEmpty() || name.contains(productSearchQuery.lowercase())
    }

    val totalAmount = selectedItems.sumOf { priceOf(it) * it.optInt("qty", 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🛒 Create New Order", fontWeight = FontWeight.Bold, color = AjiriwaColors.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AjiriwaColors.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AjiriwaColors.Surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(AjiriwaColors.Canvas)
                .padding(16.dp)
        ) {
            // Stepper Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StepChip("1. Customer", currentStep == 0, currentStep > 0) { currentStep = 0 }
                StepChip("2. Products", currentStep == 1, currentStep > 1) { currentStep = 1 }
                StepChip("3. Checkout", currentStep == 2, false) { currentStep = 2 }
            }

            when (currentStep) {
                // STEP 0: CUSTOMER SELECTION & INLINE CREATION
                0 -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Step 1: Select or Add Customer", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                            if (selectedCustomer != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(nameOf(selectedCustomer!!), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            Text(numberOf(selectedCustomer!!), fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Button(
                                            onClick = { selectedCustomer = null },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Change") }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("Search Customer Name / Phone") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Button(
                                        onClick = { showCreateCustDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("+ New Customer", fontSize = 12.sp)
                                    }

                                    TextButton(onClick = {
                                        val walkIn = JSONObject().apply {
                                            put("whatsappName", "Walk-in / Cash Customer")
                                            put("whatsappNumber", "254000000000")
                                            put("phone_number", "254000000000")
                                        }
                                        selectedCustomer = walkIn
                                    }) {
                                        Text("Use Guest Walk-in")
                                    }
                                }

                                if (isSearchingCustomer) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }

                                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                    items(customersList) { cust ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp)
                                                .clickable {
                                                    selectedCustomer = cust
                                                    mpesaPhone = numberOf(cust).filter { it.isDigit() }
                                                },
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column {
                                                    Text(nameOf(cust), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Text(numberOf(cust), fontSize = 11.sp, color = Color.Gray)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = { currentStep = 1 },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                enabled = selectedCustomer != null
                            ) {
                                Text("Next: Select Products ➔", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // STEP 1: PRODUCT CATALOG
                1 -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Step 2: Add Products to Cart", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                            OutlinedTextField(
                                value = productSearchQuery,
                                onValueChange = { productSearchQuery = it },
                                label = { Text("Filter Product Catalog...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (isFetchingProducts) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                                items(filteredProducts) { prod ->
                                    val id = prod.optString("id")
                                    val name = nameOfProduct(prod)
                                    val price = priceOf(prod)
                                    val existingItem = selectedItems.find { it.optString("id") == id }
                                    val qty = existingItem?.optInt("qty", 0) ?: 0

                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text("KES ${"%.2f".format(price)}", fontSize = 12.sp, color = Color.Gray)
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (qty > 0) {
                                                IconButton(onClick = {
                                                    val index = selectedItems.indexOfFirst { it.optString("id") == id }
                                                    if (index >= 0) {
                                                        if (qty > 1) {
                                                            val updated = JSONObject(selectedItems[index].toString()).apply { put("qty", qty - 1) }
                                                            selectedItems[index] = updated
                                                        } else {
                                                            selectedItems.removeAt(index)
                                                        }
                                                    }
                                                }) { Text("-", fontWeight = FontWeight.Bold, fontSize = 18.sp) }

                                                Text("$qty", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                                            }

                                            IconButton(onClick = {
                                                val index = selectedItems.indexOfFirst { it.optString("id") == id }
                                                if (index >= 0) {
                                                    val updated = JSONObject(selectedItems[index].toString()).apply { put("qty", qty + 1) }
                                                    selectedItems[index] = updated
                                                } else {
                                                    val newItem = JSONObject(prod.toString()).apply { put("qty", 1) }
                                                    selectedItems.add(newItem)
                                                }
                                            }) {
                                                Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
                                }
                            }

                            if (selectedItems.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${selectedItems.size} item(s) in cart", fontWeight = FontWeight.Bold)
                                        Text("Total: KES ${"%.2f".format(totalAmount)}", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { currentStep = 0 },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                                    modifier = Modifier.weight(1f)
                                ) { Text("⬅️ Back") }

                                Button(
                                    onClick = { currentStep = 2 },
                                    modifier = Modifier.weight(1f),
                                    enabled = selectedItems.isNotEmpty()
                                ) { Text("Checkout ➔", fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }

                // STEP 2: CHECKOUT & DELIVERY
                2 -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Step 3: Delivery Location & Payment", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                            // Location input & GPS capture
                            OutlinedTextField(
                                value = locationName,
                                onValueChange = { locationName = it },
                                label = { Text("Delivery Address / Landmark") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = { captureGpsLocation() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isLocating) "Capturing GPS..." else "📍 Pin Current GPS Location")
                            }

                            if (capturedLat != null && capturedLng != null) {
                                Text("✅ Pin Set: ${capturedLat?.toString()?.take(7)}, ${capturedLng?.toString()?.take(7)}", color = Color(0xFF00897B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            HorizontalDivider()

                            // Payment method selector
                            Text("Payment Method", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = paymentMethod == "STK",
                                    onClick = { paymentMethod = "STK" },
                                    label = { Text("📱 M-Pesa STK Push", fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = paymentMethod == "CASH",
                                    onClick = { paymentMethod = "CASH" },
                                    label = { Text("💵 Cash / Walk-in", fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (paymentMethod == "STK") {
                                OutlinedTextField(
                                    value = mpesaPhone,
                                    onValueChange = { mpesaPhone = it },
                                    label = { Text("M-Pesa Number (254...)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Order Summary
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Order Summary", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Customer: ${nameOf(selectedCustomer ?: JSONObject())}", fontSize = 12.sp)
                                    Text("Items: ${selectedItems.size} line item(s)", fontSize = 12.sp)
                                    Text("Grand Total: KES ${"%.2f".format(totalAmount)}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Button(
                                onClick = {
                                    isSubmitting = true
                                    val itemsArr = JSONArray()
                                    selectedItems.forEach { item ->
                                        itemsArr.put(JSONObject().apply {
                                            put("product_id", item.optString("id"))
                                            put("quantity", item.optInt("qty", 1))
                                        })
                                    }

                                    val payload = JSONObject().apply {
                                        if (selectedCustomer != null) {
                                            val cId = selectedCustomer?.optString("id")
                                            if (!cId.isNull_or_empty()) put("customerId", cId)
                                        }
                                        put("items", itemsArr)
                                        put("notes", locationName)
                                        put("pay_via_stk", paymentMethod == "STK")
                                        if (paymentMethod == "STK" && mpesaPhone.isNotBlank()) {
                                            put("mpesa_number", mpesaPhone)
                                        }
                                        if (capturedLat != null && capturedLng != null) {
                                            put("location_details", JSONObject().apply {
                                                put("name", locationName.ifBlank { "GPS Landmark" })
                                                put("lat", capturedLat)
                                                put("lng", capturedLng)
                                            })
                                        }
                                    }

                                    val mediaType = "application/json; charset=utf-8".toMediaType()
                                    val body = payload.toString().toRequestBody(mediaType)
                                    val url = "$base/api/v1/shop/orders/manual"
                                    Log.d(TAG, "Creating manual order: $url")
                                    val request = Request.Builder()
                                        .url(url)
                                        .headers(getEffectiveHeaders())
                                        .post(body)
                                        .build()

                                    client.newCall(request).enqueue(object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                isSubmitting = false
                                                Toast.makeText(context, "Failed to create order: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        override fun onResponse(call: Call, response: Response) {
                                            val respStr = response.body?.string() ?: ""
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                isSubmitting = false
                                                if (response.isSuccessful) {
                                                    Toast.makeText(context, "✅ Order Created Successfully!", Toast.LENGTH_LONG).show()
                                                    onOrderCreated()
                                                } else {
                                                    serverProblem = orderErrorText(respStr, response.code)
                                                }
                                                response.close()
                                            }
                                        }
                                    })
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                enabled = !isSubmitting
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text("Submit Order (KES ${"%.2f".format(totalAmount)})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        serverProblem?.let { msg ->
            AlertDialog(
                onDismissRequest = { serverProblem = null },
                title = { Text("Order not saved") },
                text = { Text(msg, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { serverProblem = null }) { Text("OK") } },
            )
        }

        // Inline Create Customer Dialog
        if (showCreateCustDialog) {
            AlertDialog(
                onDismissRequest = { showCreateCustDialog = false },
                title = { Text("Add New Customer") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newCustName,
                            onValueChange = { newCustName = it },
                            label = { Text("Customer Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newCustPhone,
                            onValueChange = { newCustPhone = it },
                            label = { Text("Phone Number (e.g. 2547...)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newCustPhone.isBlank()) {
                                Toast.makeText(context, "Phone number required", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isCreatingCust = true
                            val payload = JSONObject().apply {
                                put("whatsappName", newCustName.ifBlank { "Customer" })
                                put("whatsappNumber", newCustPhone)
                                put("phone_number", newCustPhone)
                            }
                            val mediaType = "application/json; charset=utf-8".toMediaType()
                            val body = payload.toString().toRequestBody(mediaType)
                            val request = Request.Builder()
                                .url("$base/api/v1/customers")
                                .headers(getEffectiveHeaders())
                                .post(body)
                                .build()

                            client.newCall(request).enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { isCreatingCust = false }
                                }
                                override fun onResponse(call: Call, response: Response) {
                                    val respBody = response.body?.string() ?: ""
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        isCreatingCust = false
                                        if (response.isSuccessful) {
                                            val createdObj = JSONObject(respBody)
                                            val cust = createdObj.optJSONObject("data") ?: createdObj
                                            selectedCustomer = cust
                                            mpesaPhone = newCustPhone
                                            showCreateCustDialog = false
                                            Toast.makeText(context, "Customer Created!", Toast.LENGTH_SHORT).show()
                                        }
                                        response.close()
                                    }
                                }
                            })
                        },
                        enabled = !isCreatingCust
                    ) { Text("Create & Select") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateCustDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun StepChip(label: String, active: Boolean, done: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary else if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun String?.isNull_or_empty(): Boolean = this == null || this.isEmpty()

private fun nameOf(c: JSONObject): String =
    c.optString("whatsappName")
        .ifBlank { c.optString("whatsapp_name")
        .ifBlank { c.optString("user_name")
        .ifBlank { c.optString("name")
        .ifBlank { c.optString("full_name", "Customer") } } } }

private fun numberOf(c: JSONObject): String {
    val raw = c.optString("whatsappNumber")
        .ifBlank { c.optString("phone_number")
        .ifBlank { c.optString("user_phone")
        .ifBlank { c.optString("phonenumber", "") } } }
    return raw.substringBefore("@")
}

private fun nameOfProduct(p: JSONObject): String = p.optString("name").ifBlank { "Product" }

private fun priceOf(p: JSONObject): Double = p.optDouble("price", p.optDouble("selling_price", 0.0))

/** Server error → text for the attendant (stock guard lists each product that would go negative). */
internal fun orderErrorText(body: String, code: Int): String = runCatching {
    val j = JSONObject(body)
    val head = j.optString("message").ifBlank { j.optString("error") }.ifBlank { "Failed (HTTP $code)" }
    val probs = j.optJSONArray("problems")
    if (probs == null || probs.length() == 0) head
    else head + "\n\n" + (0 until probs.length()).mapNotNull { probs.optJSONObject(it)?.optString("message")?.takeIf { m -> m.isNotBlank() } }
        .joinToString("\n") { "• $it" }
}.getOrElse { "Failed (HTTP $code)" }
