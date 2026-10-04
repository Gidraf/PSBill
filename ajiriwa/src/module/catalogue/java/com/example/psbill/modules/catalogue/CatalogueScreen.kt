package com.example.psbill.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
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
 * Obsidian Kinetic Product Catalogue (`catalogue_inventory_hub`):):
 * Search bar, Stat metrics, Bento glass cards, Live/Disabled pill chips & KSh monospaced pricing.
 */
@Composable
fun CatalogueScreen(server: String, headers: () -> Headers, modifier: Modifier = Modifier) {
    val client = remember { com.example.psbill.core.ActivityLog.client }
    var products by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; error = null
        val base = "https://" + server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val req = Request.Builder().url("$base/api/v1/products").headers(headers()).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { loading = false; error = e.message ?: "Network error" }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                val list = mutableListOf<JSONObject>()
                runCatching {
                    val root = JSONObject(body ?: "{}")
                    val arr = root.optJSONArray("data") ?: root.optJSONArray("products") ?: JSONArray()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                }.onFailure {
                    runCatching { val a = JSONArray(body); for (i in 0 until a.length()) a.optJSONObject(i)?.let { list.add(it) } }
                }
                loading = false; products = list
            }
        })
    }

    LaunchedEffect(server) { load() }

    val filteredProducts = remember(products, searchQuery) {
        if (searchQuery.isBlank()) products
        else products.filter {
            it.optString("name").contains(searchQuery, ignoreCase = true) ||
            it.optString("description").contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("Product Catalog", "Manage inventory, pricing, and availability for Terminal A.")
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("TOTAL ITEMS", products.size.toString(), accent = AjiriwaColors.Primary, modifier = Modifier.weight(1f))
            StatTile("LIVE ACTIVE", products.count { !it.optBoolean("is_hidden", false) }.toString(), accent = AjiriwaColors.Success, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search inventory...", color = AjiriwaColors.TextMuted) },
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
            error != null -> EmptyState(Icons.AutoMirrored.Filled.List, "Couldn't load products", error!!)
            filteredProducts.isEmpty() -> EmptyState(Icons.AutoMirrored.Filled.List, "No products found", "No items match '$searchQuery'")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filteredProducts) { p -> ProductBentoCard(p) }
            }
        }
    }
}

@Composable
private fun ProductBentoCard(p: JSONObject) {
    val name = p.optString("name").ifBlank { "Product" }
    val price = p.optString("price").ifBlank { p.optString("selling_price", "0") }
    val currency = p.optString("currency", "KSh")
    val hidden = p.optBoolean("is_hidden", false)
    val desc = p.optString("description", "")

    GlassCard(
        borderColor = if (hidden) AjiriwaColors.Border else AjiriwaColors.BorderVariant,
        backgroundColor = AjiriwaColors.Surface.copy(alpha = 0.7f)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillChip(
                        text = if (hidden) "Disabled" else "Live",
                        color = if (hidden) AjiriwaColors.Danger else AjiriwaColors.Success,
                        showDot = true
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(name, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(desc.take(90), color = AjiriwaColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$currency $price",
                    color = if (hidden) AjiriwaColors.TextMuted else AjiriwaColors.Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AjiriwaColors.SurfaceAlt)
                        .border(1.dp, AjiriwaColors.Border, RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = AjiriwaColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

