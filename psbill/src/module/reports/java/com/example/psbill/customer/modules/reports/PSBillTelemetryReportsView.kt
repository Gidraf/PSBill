package com.example.psbill.customer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

@Composable
fun PSBillTelemetryReportsView(
    nodes: List<JSONObject>,
    reportData: JSONObject?,
    reportRange: String,
    onRangeChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val summary = reportData?.optJSONObject("summary")
    val totals = reportData?.optJSONObject("totals")
    val stationsFromReport = jsonArrayToObjectList(reportData?.optJSONArray("stations"))
    val stationRows = if (stationsFromReport.isNotEmpty()) stationsFromReport else nodes
    val recommendations = jsonArrayToObjectList(reportData?.optJSONArray("recommendations"))
    val effectiveRange = reportRange.lowercase(Locale.US)

    val totalRevenue = totals?.optDouble("revenue_kes")
        ?: summary?.optDouble("total_revenue_kes")
        ?: reportData?.optDouble("total_revenue")
        ?: stationRows.sumOf { it.optDouble("revenue_kes", it.optDouble("total_paid_kes", 0.0)) }
    val totalUptimeHours = totals?.optDouble("uptime_hours")
        ?: summary?.optDouble("uptime_hours")
        ?: stationRows.sumOf { it.optDouble("uptime_hours", it.optInt("uptime_seconds", 0) / 3600.0) }
    val totalIdleHours = totals?.optDouble("idle_hours")
        ?: summary?.optDouble("idle_hours")
        ?: stationRows.sumOf { it.optDouble("idle_hours", it.optInt("idle_seconds", 0) / 3600.0) }

    val powerRecommendation = recommendations.firstOrNull {
        it.optString("type").equals("power_off", ignoreCase = true)
    } ?: stationRows.firstOrNull {
        val idleSecs = it.optInt("idle_seconds", 0)
        val hdmiConn = it.optBoolean("hdmi_connected", true)
        idleSecs > 1800 && hdmiConn
    }

    val summaryRangeLabel = summary?.optString("date")
        ?.takeIf { it.isNotBlank() }
        ?: when (effectiveRange) {
            "yesterday" -> "Yesterday"
            "last_7_days" -> "Last 7 Days"
            else -> "Today"
        }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("📊 Screen Telemetry & Reports", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Daily Uptime, HDMI Active Time, Idle Time, and Revenue Telemetry", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh reports", tint = PSBillThemeColors.PrimaryAccent)
                }
            }
        }

        item {
            BoxWithConstraints {
                val compact = maxWidth < 560.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = effectiveRange == "today",
                            onClick = { onRangeChange("today") },
                            label = { Text("Today") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilterChip(
                            selected = effectiveRange == "yesterday",
                            onClick = { onRangeChange("yesterday") },
                            label = { Text("Yesterday") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilterChip(
                            selected = effectiveRange == "last_7_days",
                            onClick = { onRangeChange("last_7_days") },
                            label = { Text("Last 7 Days") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = effectiveRange == "today",
                            onClick = { onRangeChange("today") },
                            label = { Text("Today") },
                            modifier = Modifier.widthIn(min = 120.dp)
                        )
                        FilterChip(
                            selected = effectiveRange == "yesterday",
                            onClick = { onRangeChange("yesterday") },
                            label = { Text("Yesterday") },
                            modifier = Modifier.widthIn(min = 140.dp)
                        )
                        FilterChip(
                            selected = effectiveRange == "last_7_days",
                            onClick = { onRangeChange("last_7_days") },
                            label = { Text("Last 7 Days") },
                            modifier = Modifier.widthIn(min = 160.dp)
                        )
                    }
                }
            }
        }

        // Dynamic Smart Power Recommendation Card
        if (powerRecommendation != null) {
            val alertName = powerRecommendation.optString("device_name")
                .ifBlank { powerRecommendation.optString("name", "Station") }
            val idleMins = powerRecommendation.optInt("idle_seconds", 0) / 60
            val recMessage = powerRecommendation.optString("message").ifBlank {
                "$alertName has been IDLE for $idleMins mins while HDMI cable is connected. Turn off TV & console to save energy."
            }
            item {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PSBillThemeColors.Amber.copy(alpha = 0.15f)).border(1.dp, PSBillThemeColors.Amber, RoundedCornerShape(16.dp)).padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = PSBillThemeColors.Amber, modifier = Modifier.size(28.dp))
                        Column {
                            Text("⚡ Smart Power-Off Recommendation", color = PSBillThemeColors.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                recMessage,
                                color = PSBillThemeColors.TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Aggregated Business Telemetry Card
        item {
            PSBillCard(title = "🌐 Aggregated Telemetry across ALL Screens ($summaryRangeLabel)") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total Revenue", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                            Text("KSh ${String.format("%.2f", totalRevenue)}", color = PSBillThemeColors.SecondaryAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total Uptime", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                            Text("${String.format("%.1f", totalUptimeHours)} hrs", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total Idle Time", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                            Text("${String.format("%.1f", totalIdleHours)} hrs", color = PSBillThemeColors.Amber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // Individual Screen Telemetry Breakdown
        item {
            Text("🖥️ Screen Telemetry Breakdown", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        if (stationRows.isEmpty()) {
            item {
                PSBillCard(title = "Telemetry Data") {
                    Text("No screen telemetry reports found for selected range.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                }
            }
        } else {
            items(stationRows) { node ->
                val name = node.optString("device_name").ifBlank { node.optString("name", "TV Station") }
                val uptime = String.format("%.1f", node.optDouble("uptime_hours", node.optInt("uptime_seconds", 0) / 3600.0))
                val hdmiActive = String.format("%.1f", node.optDouble("hdmi_active_hours", 0.0))
                val idle = String.format("%.1f", node.optDouble("idle_hours", node.optInt("idle_seconds", 0) / 3600.0))
                val rev = String.format("%.2f", node.optDouble("revenue_kes", node.optDouble("total_paid_kes", 0.0)))

                PSBillCard(title = name) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Uptime: $uptime hrs • HDMI Active: $hdmiActive hrs • Idle: $idle hrs", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                        Text("Revenue: KSh $rev", color = PSBillThemeColors.SecondaryAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
