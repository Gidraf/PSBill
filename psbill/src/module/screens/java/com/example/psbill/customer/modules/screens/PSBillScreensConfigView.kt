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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PSBillScreensConfigView(
    nodes: List<JSONObject>,
    games: List<JSONObject>,
    isLoading: Boolean,
    serverHost: String,
    token: String,
    client: OkHttpClient,
    onRefresh: () -> Unit,
    onSelectScreen: (JSONObject) -> Unit,
    onTokenExpired: () -> Unit
) {
    val context = LocalContext.current
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var queueTickets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var recentSessions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var loadingDetails by remember { mutableStateOf(false) }
    var actionNodeId by remember { mutableStateOf<String?>(null) }
    val selectedGameByNode = remember { mutableStateMapOf<String, String>() }
    val selectedBillingByNode = remember { mutableStateMapOf<String, String>() }
    var sessionsDateFilter by remember { mutableStateOf("") }
    var sessionsPage by remember { mutableStateOf(1) }
    var sessionsPages by remember { mutableStateOf(1) }
    var sessionsTotal by remember { mutableStateOf(0) }
    val sessionsLimit = 20

    fun authorizedBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    fun loadScreenDetails(node: JSONObject, requestedPage: Int = sessionsPage) {
        val nodeId = node.optString("id")
        if (nodeId.isBlank()) return
        val game = selectedGameByNode[nodeId]
            ?: node.optString("current_game").ifBlank { games.firstOrNull()?.optString("game_name").orEmpty() }

        queueTickets = emptyList()
        recentSessions = emptyList()
        detailError = null
        loadingDetails = true

        var pending = if (token.isNotBlank()) 2 else 1
        fun completeOne() {
            pending -= 1
            if (pending <= 0) loadingDetails = false
        }

        val queueUrl = "$serverHost/api/v1/kiosk/devices/$nodeId/queue?game_name=${Uri.encode(game)}"
        client.newCall(Request.Builder().url(queueUrl).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as? ComponentActivity)?.runOnUiThread {
                    detailError = "Queue unavailable: ${e.message}"
                    completeOne()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                (context as? ComponentActivity)?.runOnUiThread {
                    if (response.code == 401 || response.code == 403) {
                        onTokenExpired()
                        completeOne()
                        return@runOnUiThread
                    }
                    if (response.isSuccessful) {
                        val obj = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                        val ticketsArray = obj.optJSONObject("queue")?.optJSONArray("tickets") ?: JSONArray()
                        val list = mutableListOf<JSONObject>()
                        for (i in 0 until ticketsArray.length()) list.add(ticketsArray.getJSONObject(i))
                        queueTickets = list
                    } else {
                        detailError = "Queue load failed (${response.code})"
                    }
                    completeOne()
                }
            }
        })

        if (token.isBlank()) {
            detailError = "Add JWT token in settings to load session history."
            completeOne()
            return
        }

        val normalizedDateFilter = sessionsDateFilter.trim()
        val dateFilterQuery = if (normalizedDateFilter.length == 10) {
            "&start_date=${Uri.encode(normalizedDateFilter)}&end_date=${Uri.encode(normalizedDateFilter)}"
        } else {
            ""
        }
        val pageToLoad = requestedPage.coerceAtLeast(1)
        val sessionsUrl = "$serverHost/api/v1/kiosk/devices/$nodeId/sessions?limit=$sessionsLimit&page=$pageToLoad$dateFilterQuery"
        client.newCall(authorizedBuilder(sessionsUrl).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as? ComponentActivity)?.runOnUiThread {
                    detailError = "Sessions unavailable: ${e.message}"
                    completeOne()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                (context as? ComponentActivity)?.runOnUiThread {
                    if (response.code == 401 || response.code == 403) {
                        onTokenExpired()
                        completeOne()
                        return@runOnUiThread
                    }
                    if (response.isSuccessful) {
                        val obj = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                        val sessionsArray = obj.optJSONArray("sessions") ?: JSONArray()
                        val list = mutableListOf<JSONObject>()
                        for (i in 0 until sessionsArray.length()) list.add(sessionsArray.getJSONObject(i))
                        recentSessions = list
                        sessionsTotal = obj.optInt("total", list.size)
                        sessionsPage = obj.optInt("page", pageToLoad).coerceAtLeast(1)
                        sessionsPages = obj.optInt("pages", 1).coerceAtLeast(1)
                    } else {
                        detailError = "Sessions load failed (${response.code})"
                    }
                    completeOne()
                }
            }
        })
    }

    fun postScreenAction(node: JSONObject, endpoint: String, payload: JSONObject, successMessage: String) {
        val nodeId = node.optString("id")
        if (nodeId.isBlank()) return
        if (token.isBlank()) {
            Toast.makeText(context, "Add JWT token in API settings first.", Toast.LENGTH_LONG).show()
            return
        }

        actionNodeId = nodeId
        val request = authorizedBuilder("$serverHost/api/v1/kiosk/devices/$nodeId/$endpoint")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as? ComponentActivity)?.runOnUiThread {
                    actionNodeId = null
                    Toast.makeText(context, "Action failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                (context as? ComponentActivity)?.runOnUiThread {
                    actionNodeId = null
                    if (response.code == 401 || response.code == 403) {
                        onTokenExpired()
                        return@runOnUiThread
                    }
                    if (response.isSuccessful) {
                        Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                        onRefresh()
                        loadScreenDetails(node)
                    } else {
                        val error = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                        Toast.makeText(context, if (error.isNotBlank()) error else "Action failed (${response.code})", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    LaunchedEffect(nodes) {
        if (nodes.isNotEmpty() && selectedNodeId == null) {
            selectedNodeId = nodes.first().optString("id")
        }
        nodes.forEach { node ->
            val nodeId = node.optString("id")
            if (nodeId.isNotBlank() && !selectedGameByNode.containsKey(nodeId)) {
                selectedGameByNode[nodeId] = node.optString("current_game").ifBlank {
                    games.firstOrNull()?.optString("game_name") ?: "FIFA"
                }
            }
            if (nodeId.isNotBlank() && !selectedBillingByNode.containsKey(nodeId)) {
                selectedBillingByNode[nodeId] = node.optString("billing_mode", "POSTPAID")
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("🖥️ Screens", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("All screens with live state, controls, queue, and session history.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            if (isLoading) {
                Text("Refreshing screen state...", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
            }
        }

        if (nodes.isEmpty()) {
            item {
                PSBillCard(title = "No Screens Found") {
                    Text("No registered screens were returned by the API. Verify server URL and token in settings.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                }
            }
            return@LazyColumn
        }

        items(nodes) { node ->
            val nodeId = node.optString("id")
            val name = node.optString("device_name", node.optString("name", "TV Station"))
            val status = node.optString("status", "LOCKED").uppercase(Locale.US)
            val currentGame = node.optString("current_game").ifBlank { "No active game" }
            val occupied = status in setOf("ACTIVE", "OVERTIME", "PENDING")
            val liveCharge = node.optDouble("active_billing_kes", 0.0)
            val idleHours = node.optDouble("idle_hours", 0.0)
            val uptimeHours = node.optDouble("uptime_hours", 0.0)
            val isSelected = selectedNodeId == nodeId

            PSBillCard(title = name) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("State: ${if (occupied) "Occupied" else "Available"}", color = if (occupied) PSBillThemeColors.Crimson else PSBillThemeColors.SecondaryAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Status: $status", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                            Text("Game: $currentGame", color = PSBillThemeColors.TextPrimary, fontSize = 12.sp)
                            Text("Due: KSh ${String.format("%.2f", liveCharge)}", color = PSBillThemeColors.SecondaryAccent, fontSize = 12.sp)
                            Text("Uptime ${String.format("%.1f", uptimeHours)}h • Idle ${String.format("%.1f", idleHours)}h", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                selectedNodeId = nodeId
                                sessionsPage = 1
                                onSelectScreen(node)
                                loadScreenDetails(node, requestedPage = 1)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) PSBillThemeColors.PrimaryAccent else PSBillThemeColors.SurfaceAlt)
                        ) {
                            Text(if (isSelected) "Selected" else "Select")
                        }
                    }

                    BoxWithConstraints {
                        val compactSelectors = maxWidth < 640.dp
                        val gameOptions = games.map { it.optString("game_name") }.filter { it.isNotBlank() }.distinct()
                        var gamesMenuOpen by remember(nodeId) { mutableStateOf(false) }
                        val gameSelector: @Composable (Modifier) -> Unit = { modifier ->
                            ExposedDropdownMenuBox(expanded = gamesMenuOpen, onExpandedChange = { gamesMenuOpen = it }) {
                                OutlinedTextField(
                                    value = selectedGameByNode[nodeId].orEmpty(),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Game") },
                                    modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gamesMenuOpen) }
                                )
                                ExposedDropdownMenu(expanded = gamesMenuOpen, onDismissRequest = { gamesMenuOpen = false }) {
                                    gameOptions.forEach { game ->
                                        DropdownMenuItem(
                                            text = { Text(game) },
                                            onClick = {
                                                selectedGameByNode[nodeId] = game
                                                gamesMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        var billingMenuOpen by remember(nodeId) { mutableStateOf(false) }
                        val billingSelector: @Composable (Modifier) -> Unit = { modifier ->
                            ExposedDropdownMenuBox(expanded = billingMenuOpen, onExpandedChange = { billingMenuOpen = it }) {
                                OutlinedTextField(
                                    value = selectedBillingByNode[nodeId].orEmpty(),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Billing") },
                                    modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = billingMenuOpen) }
                                )
                                ExposedDropdownMenu(expanded = billingMenuOpen, onDismissRequest = { billingMenuOpen = false }) {
                                    listOf("POSTPAID", "PREPAID").forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode) },
                                            onClick = {
                                                selectedBillingByNode[nodeId] = mode
                                                billingMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (compactSelectors) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                gameSelector(Modifier.fillMaxWidth())
                                billingSelector(Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                gameSelector(Modifier.weight(1f))
                                billingSelector(Modifier.widthIn(min = 170.dp))
                            }
                        }
                    }

                    BoxWithConstraints {
                        val compactActions = maxWidth < 520.dp
                        if (compactActions) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        val payload = JSONObject().apply {
                                            put("current_game", selectedGameByNode[nodeId].orEmpty())
                                            put("billing_mode", selectedBillingByNode[nodeId].orEmpty().ifBlank { "POSTPAID" })
                                        }
                                        postScreenAction(node, "start", payload, "Session started on $name")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionNodeId != nodeId,
                                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.SecondaryAccent)
                                ) { Text("Start Session") }

                                Button(
                                    onClick = { postScreenAction(node, "stop", JSONObject(), "$name locked") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionNodeId != nodeId,
                                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.Crimson)
                                ) { Text("Lock / End") }

                                OutlinedButton(
                                    onClick = { postScreenAction(node, "close-app", JSONObject(), "$name app closed") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionNodeId != nodeId
                                ) { Text("Close App") }

                                OutlinedButton(
                                    onClick = { postScreenAction(node, "open-app", JSONObject(), "$name app opened") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = actionNodeId != nodeId
                                ) { Text("Open App") }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        val payload = JSONObject().apply {
                                            put("current_game", selectedGameByNode[nodeId].orEmpty())
                                            put("billing_mode", selectedBillingByNode[nodeId].orEmpty().ifBlank { "POSTPAID" })
                                        }
                                        postScreenAction(node, "start", payload, "Session started on $name")
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = actionNodeId != nodeId,
                                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.SecondaryAccent)
                                ) { Text("Start Session") }

                                Button(
                                    onClick = { postScreenAction(node, "stop", JSONObject(), "$name locked") },
                                    modifier = Modifier.weight(1f),
                                    enabled = actionNodeId != nodeId,
                                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.Crimson)
                                ) { Text("Lock / End") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { postScreenAction(node, "close-app", JSONObject(), "$name app closed") },
                                    modifier = Modifier.weight(1f),
                                    enabled = actionNodeId != nodeId
                                ) { Text("Close App") }
                                OutlinedButton(
                                    onClick = { postScreenAction(node, "open-app", JSONObject(), "$name app opened") },
                                    modifier = Modifier.weight(1f),
                                    enabled = actionNodeId != nodeId
                                ) { Text("Open App") }
                            }
                        }
                    }

                    if (node.optBoolean("has_ended_session", false)) {
                        Text(
                            "Session ended on this screen. Mark paid + reset to make it available.",
                            color = PSBillThemeColors.Amber,
                            fontSize = 11.sp
                        )
                        OutlinedButton(
                            onClick = { postScreenAction(node, "continue", JSONObject(), "Session resumed on $name") },
                            enabled = actionNodeId != nodeId,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Continue Ended Session")
                        }
                        Button(
                            onClick = { postScreenAction(node, "reset", JSONObject(), "Marked paid and reset $name") },
                            enabled = actionNodeId != nodeId,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.SecondaryAccent)
                        ) {
                            Text("Mark Paid + Reset Screen")
                        }
                    }
                }
            }
        }

        val selectedNode = nodes.firstOrNull { it.optString("id") == selectedNodeId }
        if (selectedNode != null) {
            item {
                PSBillCard(title = "Selected Screen Details") {
                    val selectedName = selectedNode.optString("device_name", selectedNode.optString("name", "TV Station"))
                    Text(selectedName, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (!loadingDetails && queueTickets.isEmpty() && recentSessions.isEmpty() && detailError == null) {
                        OutlinedButton(onClick = { loadScreenDetails(selectedNode) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Load Queue & Session Report")
                        }
                    }
                    if (loadingDetails) {
                        CircularProgressIndicator(color = PSBillThemeColors.PrimaryAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    detailError?.let {
                        Text(it, color = PSBillThemeColors.Amber, fontSize = 12.sp)
                    }

                    Text("Queued People", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (queueTickets.isEmpty()) {
                        Text("No people currently queued.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    } else {
                        queueTickets.forEach { ticket ->
                            val num = ticket.optInt("ticket_number", 0)
                            val player = ticket.optString("player_name").ifBlank { ticket.optString("player_phone", "Walk-in") }
                            val status = ticket.optString("status", "WAITING")
                            Text("#$num • $player • $status", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                        }
                    }

                    HorizontalDivider(color = PSBillThemeColors.Border)
                    com.example.psbill.customer.QueueWithWifiForm(
                        deviceId = selectedNode.optString("id"),
                        gameName = selectedNode.optString("current_game").takeIf { it.isNotBlank() && it != "null" }
                            ?: games.firstOrNull()?.optString("game_name").orEmpty(),
                        serverHost = serverHost, token = token, client = client,
                        onAdded = { loadScreenDetails(selectedNode) },
                    )

                    HorizontalDivider(color = PSBillThemeColors.Border)
                    Text("Session Report (day filter + pagination)", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(
                        value = sessionsDateFilter,
                        onValueChange = { sessionsDateFilter = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
                        placeholder = { Text("2026-08-13") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                sessionsPage = 1
                                loadScreenDetails(selectedNode, requestedPage = 1)
                            },
                            enabled = !loadingDetails,
                            modifier = Modifier.weight(1f)
                        ) { Text("Apply Day Filter") }
                        OutlinedButton(
                            onClick = {
                                sessionsDateFilter = ""
                                sessionsPage = 1
                                loadScreenDetails(selectedNode, requestedPage = 1)
                            },
                            enabled = !loadingDetails,
                            modifier = Modifier.weight(1f)
                        ) { Text("Clear") }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val prev = (sessionsPage - 1).coerceAtLeast(1)
                                sessionsPage = prev
                                loadScreenDetails(selectedNode, requestedPage = prev)
                            },
                            enabled = !loadingDetails && sessionsPage > 1
                        ) { Text("Prev") }
                        Text(
                            "Page $sessionsPage / $sessionsPages • Total $sessionsTotal",
                            color = PSBillThemeColors.TextSecondary,
                            fontSize = 11.sp
                        )
                        OutlinedButton(
                            onClick = {
                                val next = (sessionsPage + 1).coerceAtMost(sessionsPages)
                                sessionsPage = next
                                loadScreenDetails(selectedNode, requestedPage = next)
                            },
                            enabled = !loadingDetails && sessionsPage < sessionsPages
                        ) { Text("Next") }
                    }

                    if (recentSessions.isEmpty()) {
                        Text("No recorded sessions for this page/filter.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    } else {
                        recentSessions.forEach { session ->
                            val game = session.optString("game_name", "Unknown")
                            val mins = session.optInt("duration_minutes", 0)
                            val amount = session.optDouble("amount_charged_kes", 0.0)
                            val payment = session.optString("payment_status", "UNPAID").uppercase(Locale.US)
                            val endTime = session.optString("end_time", "").ifBlank { session.optString("start_time", "-") }
                            Text(
                                "$game • ${mins}min • KSh ${String.format("%.2f", amount)} • $payment • $endTime",
                                color = if (payment == "PAID") PSBillThemeColors.SecondaryAccent else PSBillThemeColors.Amber,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
