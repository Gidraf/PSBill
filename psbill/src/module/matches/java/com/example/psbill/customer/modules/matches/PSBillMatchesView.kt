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
fun PSBillMatchesView(nodes: List<JSONObject>, games: List<JSONObject>) {
    val context = LocalContext.current
    var playerBPhone by remember { mutableStateOf("") }
    var matchMode by remember { mutableStateOf("PREPAID") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PSBillCard(title = "⚔️ Baseya Loser-Pay Challenge") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Set up a Loser-Pay match with Player B. The loser automatically settles the station bill upon match conclusion.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)

                    BoxWithConstraints {
                        val isCompact = maxWidth < 520.dp
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                FilterChip(
                                    selected = matchMode == "PREPAID",
                                    onClick = { matchMode = "PREPAID" },
                                    label = { Text("Prepaid Stake") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FilterChip(
                                    selected = matchMode == "POSTPAID",
                                    onClick = { matchMode = "POSTPAID" },
                                    label = { Text("Postpaid Open") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilterChip(
                                    selected = matchMode == "PREPAID",
                                    onClick = { matchMode = "PREPAID" },
                                    label = { Text("Prepaid Stake") },
                                    modifier = Modifier.widthIn(min = 180.dp)
                                )
                                FilterChip(
                                    selected = matchMode == "POSTPAID",
                                    onClick = { matchMode = "POSTPAID" },
                                    label = { Text("Postpaid Open") },
                                    modifier = Modifier.widthIn(min = 180.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = playerBPhone,
                        onValueChange = { playerBPhone = it },
                        label = { Text("Player B Phone / Username") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PSBillThemeColors.PrimaryAccent)
                    )

                    Button(
                        onClick = {
                            if (playerBPhone.trim().isEmpty()) {
                                Toast.makeText(context, "Enter Player B phone or username", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Challenge invitation sent to $playerBPhone!", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.Crimson)
                    ) {
                        Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send Challenge Invite")
                    }
                }
            }
        }
    }
}
