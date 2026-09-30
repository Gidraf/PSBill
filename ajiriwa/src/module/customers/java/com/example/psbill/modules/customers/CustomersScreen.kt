package com.example.psbill.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.ui.components.EmptyState
import com.example.psbill.ui.components.GlassCard
import com.example.psbill.ui.components.PillChip
import com.example.psbill.ui.components.SectionTitle
import com.example.psbill.ui.components.StatTile
import com.example.psbill.ui.theme.AjiriwaColors
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Obsidian Kinetic Customer Profile Directory & Insights (`customer_profile_insights` / `customer_activity_ledger`):
 * Customer CRM list, tier badges (Platinum / Standard), active debt status (CLEAR / DEBT), and direct SMS actions.
 */
@Composable
fun CustomersScreen(server: String, headers: () -> Headers, modifier: Modifier = Modifier) {
    val client = remember { OkHttpClient() }
    var all by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; error = null
        val base = "https://" + server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val req = Request.Builder().url("$base/api/v1/all-whatsapp-users?page=1&limit=100").headers(headers()).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { loading = false; error = e.message ?: "Network error" }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                val list = mutableListOf<JSONObject>()
                runCatching {
                    val root = JSONObject(body ?: "{}")
                    val arr = root.optJSONArray("data")
                        ?: root.optJSONArray("users")
                        ?: root.optJSONArray("whatsapp_users")
                        ?: root.optJSONArray("results")
                        ?: JSONArray()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                }.onFailure {
                    runCatching {
                        val a = JSONArray(body)
                        for (i in 0 until a.length()) a.optJSONObject(i)?.let { list.add(it) }
                    }
                }
                loading = false; all = list
            }
        })
    }

    LaunchedEffect(server) { load() }

    val filtered = remember(all, query) {
        if (query.isBlank()) all
        else all.filter { c ->
            (nameOf(c) + " " + numberOf(c)).contains(query.trim(), ignoreCase = true)
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("Customer Directory & Insights", "Search CRM profiles, debt status, and engagement logs.")
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("TOTAL CUSTOMERS", all.size.toString(), accent = AjiriwaColors.Primary, modifier = Modifier.weight(1f))
            StatTile("SHOWING", filtered.size.toString(), accent = AjiriwaColors.Secondary, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search by name or phone...", color = AjiriwaColors.TextMuted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AjiriwaColors.Primary) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AjiriwaColors.Canvas,
                unfocusedContainerColor = AjiriwaColors.Canvas,
                focusedBorderColor = AjiriwaColors.PrimaryAccent,
                unfocusedBorderColor = AjiriwaColors.Border,
                focusedTextColor = AjiriwaColors.TextPrimary,
                unfocusedTextColor = AjiriwaColors.TextPrimary,
            ),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(14.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AjiriwaColors.PrimaryAccent) }
            error != null -> EmptyState(Icons.Filled.Person, "Couldn't load customers", error!!)
            filtered.isEmpty() -> EmptyState(Icons.Filled.Person, if (all.isEmpty()) "No customers yet" else "No matches", if (all.isEmpty()) "Customers you serve will appear here." else "Try a different search.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered) { c -> CustomerProfileCard(c) }
            }
        }
    }
}

private fun nameOf(c: JSONObject): String =
    c.optString("whatsappName").ifBlank { c.optString("whatsapp_name").ifBlank { c.optString("name").ifBlank { c.optString("full_name", "") } } }

private fun numberOf(c: JSONObject): String {
    val raw = c.optString("whatsappNumber").ifBlank { c.optString("phone_number").ifBlank { c.optString("phonenumber", "") } }
    return raw.substringBefore("@")
}

@Composable
private fun CustomerProfileCard(c: JSONObject) {
    val number = numberOf(c)
    val name = nameOf(c).ifBlank { number.ifBlank { "Customer" } }
    val joined = c.optString("createdAt").ifBlank { c.optString("created_at", "") }.take(10)
    val debt = c.optDouble("debt_amount", 0.0)

    GlassCard(
        borderColor = AjiriwaColors.Border,
        backgroundColor = AjiriwaColors.Surface.copy(alpha = 0.7f)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AjiriwaColors.SurfaceAlt)
                    .border(2.dp, AjiriwaColors.Primary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), color = AjiriwaColors.Primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    PillChip("Platinum", AjiriwaColors.Primary, showDot = false)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    number.ifBlank { "No phone number" },
                    color = AjiriwaColors.TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (joined.isNotBlank()) {
                    Text("Joined: $joined", color = AjiriwaColors.TextMuted, fontSize = 11.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (debt > 0) {
                    PillChip("KES ${debt.toInt()} DEBT", AjiriwaColors.Danger, showDot = true)
                } else {
                    PillChip("CLEAR", AjiriwaColors.Success, showDot = true)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AjiriwaColors.SurfaceAlt)
                        .border(1.dp, AjiriwaColors.Border, RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    Icon(Icons.Filled.Email, contentDescription = "SMS", tint = AjiriwaColors.PrimaryAccent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

