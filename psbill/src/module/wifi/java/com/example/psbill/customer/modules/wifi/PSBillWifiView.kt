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
fun PSBillWifiView(vouchers: List<JSONObject>, onRefresh: () -> Unit) {
    val context = LocalContext.current
    var voucherPin by remember { mutableStateOf("") }

    fun copyToClipboard(code: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Wi-Fi Voucher Code", "Baseya Wi-Fi Voucher Code: $code (Valid for High Speed Access)")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "📋 Voucher code $code copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PSBillCard(title = "📶 Baseya Wi-Fi Voucher Portal") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Baseya_Arcade_5G", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Speed: 100 Mbps Ultra Fiber", color = PSBillThemeColors.SecondaryAccent, fontSize = 12.sp)
                        }
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = PSBillThemeColors.SecondaryAccent, modifier = Modifier.size(28.dp))
                    }

                    HorizontalDivider(color = PSBillThemeColors.Border)

                    Text("Redeem Wi-Fi Voucher PIN:", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(
                        value = voucherPin,
                        onValueChange = { voucherPin = it },
                        label = { Text("Enter Voucher PIN Code") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PSBillThemeColors.PrimaryAccent)
                    )

                    Button(
                        onClick = {
                            if (voucherPin.trim().isEmpty()) {
                                Toast.makeText(context, "Enter voucher PIN code", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Wi-Fi Voucher $voucherPin Activated! High Speed Unlocked.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.SecondaryAccent)
                    ) {
                        Text("Activate Voucher")
                    }
                }
            }
        }

        item {
            Text("📋 Generated Voucher Batches & 1-Tap Copy", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        if (vouchers.isEmpty()) {
            item {
                PSBillCard(title = "Wi-Fi Vouchers") {
                    Text("No active Wi-Fi vouchers generated yet. Generate new vouchers via web admin dashboard.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                }
            }
        } else {
            items(vouchers) { voucher ->
                val code = voucher.optString("code", voucher.optString("voucher_code", voucher.optString("pin", "VOUCHER")))
                val amount = voucher.optString("amount")
                    .ifBlank { voucher.optString("price_kes") }
                    .ifBlank { "--" }
                val status = voucher.optString("status", "ACTIVE")
                val source = voucher.optString("source", "kiosk")
                val packageName = voucher.optString("package_name")

                PSBillCard(title = "Voucher Code: $code") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Code: $code", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                            Text(if (amount == "--") "Amount: --" else "KES $amount", color = PSBillThemeColors.SecondaryAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text(
                            "Status: ${status.uppercase(Locale.US)} • Source: ${if (source == "wifi_batch") "Wi-Fi Batch" else "Kiosk"}",
                            color = PSBillThemeColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                        if (packageName.isNotBlank()) {
                            Text("Package: $packageName", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { copyToClipboard(code) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.SurfaceAlt)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = PSBillThemeColors.PrimaryAccent)
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Voucher Code", color = PSBillThemeColors.TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
