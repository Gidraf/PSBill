package com.example.psbill.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.psbill.core.CustomerOutbox
import com.example.psbill.ui.components.EmptyState
import com.example.psbill.ui.components.GlassCard
import com.example.psbill.ui.components.PillChip
import com.example.psbill.ui.components.SectionTitle
import com.example.psbill.ui.theme.AjiriwaColors
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val JSON_MT = "application/json; charset=utf-8".toMediaType()
private val mainThread = Handler(Looper.getMainLooper())

/** Profile text fields (backend `app/services/customer_profile.py`). */
private val PROFILE_KEYS = listOf("first_name", "last_name", "nickname", "house_name", "house_number", "location_text")

/**
 * Customers: search, add and edit the real name, nickname, house and location. Saves go to
 * `/api/v1/customers/<partner>/profiles`; offline they wait in [CustomerOutbox] and reach the
 * server through the contact sync. SMS greet by the real first name, else "Customer".
 */
@Composable
fun CustomersScreen(server: String, headers: () -> Headers, partnerId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val client = remember { com.example.psbill.core.ActivityLog.client }
    val base = remember(server) { "https://" + server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(CustomerOutbox.pending(context)) }

    fun load(q: String) {
        loading = true; error = null
        val url = runCatching {
            "$base/api/v1/customers/$partnerId/profiles".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "100")
                .apply { if (q.isNotBlank()) addQueryParameter("search", q.trim()) }
                .build()
        }.getOrNull() ?: run { loading = false; error = "Bad server address"; return }
        client.newCall(Request.Builder().url(url).headers(headers()).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainThread.post { loading = false; error = e.message ?: "Network error" }
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = response.body?.string().orEmpty()
                response.close()
                val list = mutableListOf<JSONObject>()
                var count = 0
                val ok = code in 200..299 && runCatching {
                    val root = JSONObject(body)
                    val arr = root.optJSONArray("items")
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                    count = root.optInt("total", list.size)
                }.isSuccess
                mainThread.post {
                    loading = false
                    if (ok) { items = list; total = count }
                    else error = runCatching { JSONObject(body).optString("error") }.getOrNull()?.ifBlank { null } ?: "Server error ($code)"
                }
            }
        })
    }

    // debounce typing; reload after a save
    LaunchedEffect(query, reloadKey, partnerId) {
        if (query.isNotBlank()) delay(350)
        load(query)
    }

    val current = editing
    if (current != null || creating) {
        CustomerEditor(
            customer = current,
            base = base,
            partnerId = partnerId,
            headers = headers,
            client = client,
            onClose = { saved ->
                editing = null; creating = false
                pending = CustomerOutbox.pending(context)
                if (saved) reloadKey++
            },
        )
        return
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Customers", "Real names, houses and locations. SMS greet by first name.") }
            IconButton(onClick = { reloadKey++ }) { Icon(Icons.Filled.Refresh, "Reload", tint = AjiriwaColors.Primary) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Name, phone, house...", color = AjiriwaColors.TextMuted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AjiriwaColors.Primary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$total customer${if (total == 1) "" else "s"}" + if (pending > 0) " - $pending waiting to sync" else "",
                color = AjiriwaColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { creating = true },
                colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AjiriwaColors.PrimaryAccent)
            }
            error != null && items.isEmpty() -> EmptyState(Icons.Filled.Person, "Couldn't load customers", error!!)
            items.isEmpty() -> EmptyState(
                Icons.Filled.Person, if (query.isBlank()) "No customers yet" else "No matches",
                if (query.isBlank()) "Tap Add, or sync the phone's contacts." else "Try a different search.",
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(items, key = { it.optString("id") }) { c -> CustomerRow(c) { editing = c } }
            }
        }
    }
}

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

private fun titleOf(c: JSONObject): String =
    c.str("full_name").ifBlank { c.str("contact_name").ifBlank { c.str("phone_display").ifBlank { c.str("phone").ifBlank { "Customer" } } } }

@Composable
private fun CustomerRow(c: JSONObject, onClick: () -> Unit) {
    val name = titleOf(c)
    val phone = c.str("phone_display").ifBlank { c.str("phone") }
    val house = listOf(c.str("house_name"), c.str("house_number")).filter { it.isNotBlank() }.joinToString(", ")
    GlassCard(
        modifier = Modifier.clickable(onClick = onClick),
        borderColor = AjiriwaColors.Border,
        backgroundColor = AjiriwaColors.Surface.copy(alpha = 0.7f),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(AjiriwaColors.SurfaceAlt)
                    .border(2.dp, AjiriwaColors.Primary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(name.take(1).uppercase(), color = AjiriwaColors.Primary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (phone.isNotBlank()) Text(phone, color = AjiriwaColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                val extra = listOfNotNull(
                    c.str("nickname").ifBlank { null }?.let { "\"$it\"" },
                    house.ifBlank { null },
                    c.str("location_text").ifBlank { null },
                ).joinToString(" - ")
                if (extra.isNotBlank()) Text(extra, color = AjiriwaColors.TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            PillChip("Hi ${c.str("sms_name").ifBlank { "Customer" }}", AjiriwaColors.Secondary, showDot = false)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AjiriwaColors.Canvas,
    unfocusedContainerColor = AjiriwaColors.Canvas,
    focusedBorderColor = AjiriwaColors.PrimaryAccent,
    unfocusedBorderColor = AjiriwaColors.Border,
    focusedTextColor = AjiriwaColors.TextPrimary,
    unfocusedTextColor = AjiriwaColors.TextPrimary,
    focusedLabelColor = AjiriwaColors.Primary,
    unfocusedLabelColor = AjiriwaColors.TextSecondary,
)

/** Same rule as the server's `sms_name` (close enough for a preview). */
private fun smsPreview(first: String): String {
    val w = java.text.Normalizer.normalize(first.trim().substringBefore(' '), java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
    return if (Regex("^[A-Za-z][A-Za-z'.-]*$").matches(w)) w.replaceFirstChar { it.uppercase() } else "Customer"
}

private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

/** Add / edit one customer. The form scrolls; the app root keeps it above the keyboard. */
@Composable
private fun CustomerEditor(
    customer: JSONObject?,
    base: String,
    partnerId: String,
    headers: () -> Headers,
    client: OkHttpClient,
    onClose: (saved: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val isNew = customer == null
    var phone by remember { mutableStateOf(customer?.str("phone").orEmpty()) }
    var contactName by remember { mutableStateOf(customer?.str("contact_name").orEmpty()) }
    val fields = remember { mutableStateMapOf<String, String>().apply { PROFILE_KEYS.forEach { put(it, customer?.str(it).orEmpty()) } } }
    var lat by remember { mutableStateOf(customer?.str("location_lat").orEmpty()) }
    var lng by remember { mutableStateOf(customer?.str("location_lng").orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun profileJson(): JSONObject = JSONObject().apply {
        PROFILE_KEYS.forEach { put(it, fields[it].orEmpty().trim()) }
        put("location_lat", lat.trim().toDoubleOrNull() ?: JSONObject.NULL)
        put("location_lng", lng.trim().toDoubleOrNull() ?: JSONObject.NULL)
    }

    fun queueOffline(reason: String?) {
        CustomerOutbox.enqueue(context, phone, contactName, profileJson())
        CustomerOutbox.flushAsync(context)
        mainThread.post {
            saving = false
            Toast.makeText(context, "Saved on the phone; it syncs when online" + (reason?.let { " ($it)" } ?: ""), Toast.LENGTH_LONG).show()
            onClose(true)
        }
    }

    fun save() {
        error = null
        val badGeo = (lat.isBlank() != lng.isBlank()) ||
            (lat.isNotBlank() && (lat.trim().toDoubleOrNull() == null || lng.trim().toDoubleOrNull() == null))
        if (badGeo) { error = "Latitude and longitude go together (numbers)."; return }
        if (phone.filter { it.isDigit() }.length < 9) { error = "Enter the customer's phone number."; return }
        saving = true
        val body = profileJson().put("profile_updated_at", isoNow())
        if (contactName.isNotBlank()) body.put("name", contactName.trim())
        if (isNew) body.put("phone", phone.trim())
        val url = if (isNew) "$base/api/v1/customers/$partnerId/profiles"
        else "$base/api/v1/customers/$partnerId/profiles/${customer!!.str("id")}"
        val rb = body.toString().toRequestBody(JSON_MT)
        val req = Request.Builder().url(url).headers(headers()).apply { if (isNew) post(rb) else patch(rb) }.build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = queueOffline(null)
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val text = response.body?.string().orEmpty()
                response.close()
                when (code) {
                    in 200..299 -> mainThread.post {
                        saving = false
                        Toast.makeText(context, "Customer saved", Toast.LENGTH_SHORT).show()
                        onClose(true)
                    }
                    in 400..499 -> mainThread.post {
                        saving = false
                        error = runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "Not saved ($code)"
                    }
                    else -> queueOffline("server $code")
                }
            }
        })
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) currentLocation(context, { locating = it }) { a, b -> lat = a; lng = b }
        else Toast.makeText(context, "Location permission refused", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onClose(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AjiriwaColors.Primary) }
            Text(
                if (isNew) "New customer" else titleOf(customer!!), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold,
                fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (!isNew && phone.isNotBlank()) {
                IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) } }) {
                    Icon(Icons.Filled.Phone, "Call", tint = AjiriwaColors.Primary)
                }
                IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))) } }) {
                    Icon(Icons.Filled.Email, "SMS", tint = AjiriwaColors.Primary)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(color = AjiriwaColors.SurfaceAlt, shape = RoundedCornerShape(10.dp)) {
                Text(
                    "SMS will greet them as \"${smsPreview(fields["first_name"].orEmpty())}\". The contact name is never sent to the customer.",
                    color = AjiriwaColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(10.dp),
                )
            }
            error?.let { Text(it, color = AjiriwaColors.Danger, fontSize = 13.sp) }
            Field("Phone", phone, { phone = it }, enabled = isNew, keyboard = KeyboardType.Phone)
            Field("First name", fields["first_name"].orEmpty(), { fields["first_name"] = it }, caps = KeyboardCapitalization.Words)
            Field("Last name", fields["last_name"].orEmpty(), { fields["last_name"] = it }, caps = KeyboardCapitalization.Words)
            Field("Nickname", fields["nickname"].orEmpty(), { fields["nickname"] = it }, caps = KeyboardCapitalization.Words)
            Field("Contact name (as on the phone)", contactName, { contactName = it }, caps = KeyboardCapitalization.Words)
            Field("House / building name", fields["house_name"].orEmpty(), { fields["house_name"] = it }, caps = KeyboardCapitalization.Words)
            Field("House number", fields["house_number"].orEmpty(), { fields["house_number"] = it })
            Field(
                "Location / directions", fields["location_text"].orEmpty(), { fields["location_text"] = it },
                singleLine = false, caps = KeyboardCapitalization.Sentences,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("Latitude", lat, { lat = it }, keyboard = KeyboardType.Decimal) }
                Box(Modifier.weight(1f)) { Field("Longitude", lng, { lng = it }, keyboard = KeyboardType.Decimal) }
            }
            OutlinedButton(
                onClick = {
                    val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                    if (granted) currentLocation(context, { locating = it }) { a, b -> lat = a; lng = b }
                    else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                enabled = !locating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (locating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("I am at their house: use my location")
            }
            if (lat.isNotBlank() && lng.isNotBlank()) {
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) } }) {
                    Text("Open the pin in Maps")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onClose(false) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = { save() }, enabled = !saving, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Save")
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard, capitalization = caps,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors(),
        shape = RoundedCornerShape(10.dp),
    )
}

@SuppressLint("MissingPermission")
private fun currentLocation(context: Context, busy: (Boolean) -> Unit, onFix: (String, String) -> Unit) {
    busy(true)
    runCatching {
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                busy(false)
                if (loc != null) onFix("%.6f".format(Locale.US, loc.latitude), "%.6f".format(Locale.US, loc.longitude))
                else Toast.makeText(context, "No GPS fix yet, try outside", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { busy(false); Toast.makeText(context, it.message ?: "Location failed", Toast.LENGTH_SHORT).show() }
    }.onFailure { busy(false) }
}
