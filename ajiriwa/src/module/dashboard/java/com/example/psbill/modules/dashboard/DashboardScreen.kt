package com.example.psbill.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.json.JSONObject
import java.io.IOException

/**
 * Obsidian Kinetic Dashboard Screen:
 * Command Center real-time business telemetry, KPI stat tiles, and recent order stream.
 */
@Composable
fun DashboardScreen(server: String, headers: () -> Headers, modifier: Modifier = Modifier) {
    val client = remember { com.example.psbill.core.ActivityLog.client }
    val base = remember(server) { "https://${server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}" }

    var analytics by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; error = null
        val req = Request.Builder().url("$base/api/v1/shop/analytics/dashboard").headers(headers()).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    loading = false; error = e.message
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    loading = false
                    runCatching { analytics = JSONObject(body) }.onFailure { error = "Parse error" }
                }
            }
        })
    }

    LaunchedEffect(server) { load() }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Dashboard Overview", "Command Center real-time business telemetry.")
            IconButton(onClick = { load() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = AjiriwaColors.PrimaryAccent)
            }
        }

        Spacer(Modifier.height(14.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AjiriwaColors.PrimaryAccent)
            }
            error != null -> EmptyState(Icons.Filled.Home, "Couldn't load dashboard", error!!)
            analytics == null -> EmptyState(Icons.Filled.Home, "No data", "Dashboard data will appear here.")
            else -> {
                val cards = analytics!!
                val totalOrders = cards.optInt("total_orders", 0)
                val totalRevenue = cards.optString("total_revenue", "0")
                val totalCustomers = cards.optInt("total_customers", 0)
                val recentOrders = cards.optJSONArray("recent_orders")

                // KPI grid
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("TOTAL ORDERS", totalOrders.toString(), accent = AjiriwaColors.Primary, modifier = Modifier.weight(1f))
                    StatTile("REVENUE", "KSh $totalRevenue", accent = AjiriwaColors.Success, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("CUSTOMERS", totalCustomers.toString(), accent = AjiriwaColors.Secondary, modifier = Modifier.weight(1f))
                    val pendingCount = try {
                        var cnt = 0
                        val ro = cards.optJSONArray("recent_orders")
                        if (ro != null) for (i in 0 until ro.length()) {
                            if (ro.optJSONObject(i)?.optString("status", "")?.equals("PENDING", true) == true) cnt++
                        }
                        cnt.toString()
                    } catch (e: Exception) { "0" }
                    StatTile("PENDING TODAY", pendingCount, accent = AjiriwaColors.Warning, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(20.dp))
                Text("Recent Activity Log", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))

                if (recentOrders != null && recentOrders.length() > 0) {
                    val list = (0 until recentOrders.length()).mapNotNull { recentOrders.optJSONObject(it) }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list) { o ->
                            val id = o.optString("id").takeLast(6).uppercase()
                            val customer = o.optString("whatsapp_name").ifBlank { o.optString("user_phone", "Customer") }
                            val status = o.optString("status", "PENDING")
                            val amount = o.optString("amount").ifBlank { o.optString("total_amount", "0") }

                            GlassCard {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Order #$id", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(Modifier.height(2.dp))
                                        Text(customer, color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "KSh $amount",
                                            color = AjiriwaColors.TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        val statusColor = when (status.uppercase()) {
                                            "DELIVERED", "COMPLETED" -> AjiriwaColors.Success
                                            "PENDING" -> AjiriwaColors.Warning
                                            "CANCELLED" -> AjiriwaColors.Danger
                                            else -> AjiriwaColors.TextSecondary
                                        }
                                        PillChip(status, statusColor, showDot = true)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    EmptyState(Icons.Filled.Home, "No recent orders", "Recent order activity will show up here.")
                }
            }
        }
    }
}

