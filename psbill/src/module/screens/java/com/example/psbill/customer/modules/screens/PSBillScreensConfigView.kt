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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    var activeMatch by remember { mutableStateOf<JSONObject?>(null) }
    val api = remember(token) { PsApi(context, client, serverHost, token, onTokenExpired) }
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
        queueTickets = emptyList()
        recentSessions = emptyList()
        activeMatch = null
        detailError = null
        loadingDetails = true

        var pending = if (token.isNotBlank()) 2 else 1
        fun completeOne() {
            pending -= 1
            if (pending <= 0) loadingDetails = false
        }

        // Every waiting / called person for this screen, all games (the old call showed one game only).
        api.get("/api/v1/kiosk/queue/tickets?device_id=${Uri.encode(nodeId)}") { code, body ->
            if (code in 200..299) {
                queueTickets = parseObjects(body).filter { it.optString("status").uppercase(Locale.US) in setOf("WAITING", "CALLED") }
            } else {
                detailError = if (code == 0) "Could not load the queue. Check the internet, then tap refresh."
                              else "Could not load the queue (error $code)."
            }
            completeOne()
        }
        LoserPayActions.active(api, nodeId) { activeMatch = it }

        if (token.isBlank()) {
            detailError = "Sign in again to see the session history."
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
                    detailError = "Could not load the history. Check the internet, then tap refresh."
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
                        detailError = "Could not load the history (error ${response.code})."
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
            Toast.makeText(context, "Please sign in again.", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, "No connection - nothing was changed. Try again.", Toast.LENGTH_LONG).show()
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


    var openNodeId by remember { mutableStateOf<String?>(null) }
    val openNode = nodes.firstOrNull { it.optString("id") == openNodeId }

    if (openNode != null) {
        BackHandler { openNodeId = null }
        ScreenDetail(
            node = openNode,
            games = games,
            busy = actionNodeId == openNode.optString("id"),
            selectedGame = selectedGameByNode[openNode.optString("id")].orEmpty(),
            onGameChange = { selectedGameByNode[openNode.optString("id")] = it },
            selectedBilling = selectedBillingByNode[openNode.optString("id")].orEmpty().ifBlank { "POSTPAID" },
            onBillingChange = { selectedBillingByNode[openNode.optString("id")] = it },
            onBack = { openNodeId = null },
            onAction = { endpoint, payload, message -> postScreenAction(openNode, endpoint, payload, message) },
            queueTickets = queueTickets,
            recentSessions = recentSessions,
            loadingDetails = loadingDetails,
            detailError = detailError,
            onReload = { loadScreenDetails(openNode) },
            dateFilter = sessionsDateFilter,
            onDateFilterChange = { sessionsDateFilter = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10) },
            page = sessionsPage,
            pages = sessionsPages,
            total = sessionsTotal,
            onPage = { p -> sessionsPage = p; loadScreenDetails(openNode, requestedPage = p) },
            activeMatch = activeMatch,
            onQueueStatus = { ticket, status, message ->
                actionNodeId = openNode.optString("id")
                api.post("/api/v1/kiosk/queue/tickets/${ticket.optString("id")}/status", JSONObject().put("status", status)) { code, body ->
                    actionNodeId = null
                    if (code in 200..299) { api.toast(message); loadScreenDetails(openNode) }
                    else api.toast(api.errorText(code, body, "Could not update the queue"))
                }
            },
            onCallNext = {
                val next = queueTickets.firstOrNull { it.optString("status").uppercase(Locale.US) == "WAITING" }
                if (next == null) api.toast("No one is waiting")
                else {
                    actionNodeId = openNode.optString("id")
                    api.post("/api/v1/kiosk/queue/next", JSONObject().put("device_id", openNode.optString("id")).put("game_name", next.optString("game_name"))) { code, body ->
                        actionNodeId = null
                        if (code in 200..299) {
                            val t = runCatching { JSONObject(body).optJSONObject("next_ticket") }.getOrNull()
                            api.toast(if (t != null) "Called #${t.optInt("ticket_number")} ${t.optString("player_name").takeIf { it.isNotBlank() && it != "null" }.orEmpty()}" else "No one is waiting")
                            loadScreenDetails(openNode)
                        } else api.toast(api.errorText(code, body, "Could not call the next person"))
                    }
                }
            },
            onStartFromTicket = { ticket ->
                postScreenAction(openNode, "start", JSONObject().apply {
                    put("current_game", ticket.optString("game_name"))
                    put("billing_mode", selectedBillingByNode[openNode.optString("id")].orEmpty().let { if (it == "PREPAID") it else "POSTPAID" })
                    put("queue_ticket_id", ticket.optString("id"))
                }, "Session started for #${ticket.optInt("ticket_number")}")
            },
            onLoserStart = { game, a, b ->
                actionNodeId = openNode.optString("id")
                LoserPayActions.start(api, openNode.optString("id"), game, a, b) { ok ->
                    actionNodeId = null
                    if (ok) { onRefresh(); loadScreenDetails(openNode) }
                }
            },
            onRound = { winner ->
                activeMatch?.let { m ->
                    actionNodeId = openNode.optString("id")
                    LoserPayActions.round(api, m, winner) { actionNodeId = null; loadScreenDetails(openNode) }
                }
            },
            onEndMatch = {
                activeMatch?.let { m ->
                    actionNodeId = openNode.optString("id")
                    LoserPayActions.end(api, m) { ok ->
                        actionNodeId = null
                        // Ending the match does not lock the screen on its own.
                        if (ok) postScreenAction(openNode, "stop", JSONObject(), "${nodeName(openNode)} locked")
                    }
                }
            },
            queueForm = {
                QueueWithWifiForm(
                    deviceId = openNode.optString("id"),
                    gameName = nodeGame(openNode).ifBlank { games.firstOrNull()?.optString("game_name").orEmpty() },
                    serverHost = serverHost, token = token, client = client,
                    onAdded = { loadScreenDetails(openNode) },
                )
            },
        )
        return
    }

    val playing = nodes.count { screenStatus(it).first in setOf("PLAYING", "OVERTIME") }
    val toPay = nodes.count { screenStatus(it).first == "TO PAY" }
    val free = nodes.count { screenStatus(it).first == "FREE" }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            PsPageHeader("Screens", "Tap a screen to start, end or manage it") {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
        }
        if (nodes.isEmpty()) {
            item {
                PsEmptyState(
                    icon = Icons.Filled.TvOff,
                    title = "No screens yet",
                    message = "Link a TV from the Sessions tab with the PIN shown on the TV.",
                    actionLabel = "Refresh", onAction = onRefresh,
                )
            }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                PsStat("Playing", "$playing", PsTone.Busy, Modifier.weight(1f))
                PsStat("To pay", "$toPay", PsTone.Warn, Modifier.weight(1f))
                PsStat("Free", "$free", PsTone.Good, Modifier.weight(1f))
            }
        }
        items(nodes, key = { it.optString("id") }) { node ->
            val (label, tone) = screenStatus(node)
            val game = nodeGame(node)
            val due = node.optDouble("active_billing_kes", 0.0)
            val detail = when (label) {
                "PLAYING", "OVERTIME" -> game.ifBlank { "Session running" }
                "TO PAY" -> "Session ended - waiting for payment"
                "ASLEEP" -> "Screen is dark - open to wake it"
                "OFFLINE" -> "Not connected"
                else -> "Ready for the next player"
            }
            PsListRow(
                title = nodeName(node),
                detail = detail,
                tone = tone,
                icon = Icons.Filled.Tv,
                pill = label,
                trailingValue = if (due > 0 && label in setOf("PLAYING", "OVERTIME", "TO PAY")) kes(due) else null,
                trailingTone = PsTone.Good,
                onClick = {
                    openNodeId = node.optString("id")
                    sessionsPage = 1
                    onSelectScreen(node)
                    loadScreenDetails(node, requestedPage = 1)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenDetail(
    node: JSONObject,
    games: List<JSONObject>,
    busy: Boolean,
    selectedGame: String,
    onGameChange: (String) -> Unit,
    selectedBilling: String,
    onBillingChange: (String) -> Unit,
    onBack: () -> Unit,
    onAction: (String, JSONObject, String) -> Unit,
    queueTickets: List<JSONObject>,
    recentSessions: List<JSONObject>,
    loadingDetails: Boolean,
    detailError: String?,
    onReload: () -> Unit,
    dateFilter: String,
    onDateFilterChange: (String) -> Unit,
    page: Int,
    pages: Int,
    total: Int,
    onPage: (Int) -> Unit,
    activeMatch: JSONObject?,
    onQueueStatus: (JSONObject, String, String) -> Unit,
    onCallNext: () -> Unit,
    onStartFromTicket: (JSONObject) -> Unit,
    onLoserStart: (String, String, String) -> Unit,
    onRound: (String?) -> Unit,
    onEndMatch: () -> Unit,
    queueForm: @Composable () -> Unit,
) {
    val name = nodeName(node)
    var ticketForActions by remember { mutableStateOf<JSONObject?>(null) }
    val (label, tone) = screenStatus(node)
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Control", "Queue (${queueTickets.size})", "History")

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to screens") }
            Column(Modifier.weight(1f)) {
                Text(name, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
                Text(
                    node.optString("console_type").takeIf { it.isNotBlank() && it != "null" } ?: "Game screen",
                    color = PSBillThemeColors.TextSecondary, fontSize = 12.sp
                )
            }
            PsPill(label, tone)
        }
        Spacer(Modifier.height(8.dp))
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) },
                    selectedContentColor = PSBillThemeColors.TextPrimary,
                    unselectedContentColor = PSBillThemeColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        detailError?.let {
            Text(it, color = PSBillThemeColors.Amber, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            when (tab) {
                0 -> item {
                    if (activeMatch != null) {
                        LoserPayMatchCard(activeMatch, null, busy, onRound, onEndMatch)
                    } else {
                        ScreenControl(node, label, games, busy, selectedGame, onGameChange, selectedBilling, onBillingChange, onAction, onLoserStart)
                    }
                }
                1 -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { PsSectionLabel("Waiting", queueTickets.size) }
                            FilledTonalButton(onClick = onCallNext, enabled = !busy && queueTickets.any { it.optString("status").uppercase(Locale.US) == "WAITING" }) {
                                Icon(Icons.Filled.Campaign, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call next")
                            }
                        }
                    }
                    if (queueTickets.isEmpty()) {
                        item { Text("No one is waiting for this screen.", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp) }
                    } else {
                        items(queueTickets) { t ->
                            val who = t.optString("player_name").takeIf { it.isNotBlank() && it != "null" }
                                ?: t.optString("player_phone").takeIf { it.isNotBlank() && it != "null" } ?: "Walk-in"
                            val st = t.optString("status", "WAITING").uppercase(Locale.US)
                            val wait = t.optInt("estimated_wait_minutes", 0)
                            PsListRow(
                                title = "#${t.optInt("ticket_number", 0)}  $who",
                                detail = listOf(
                                    t.optString("game_name").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
                                    if (st == "WAITING" && wait > 0) "about $wait min" else "",
                                    t.optString("wifi_voucher_code").takeIf { it.isNotBlank() && it != "null" }?.let { "WiFi $it" }.orEmpty(),
                                ).filter { it.isNotBlank() }.joinToString("  -  "),
                                tone = if (st == "CALLED") PsTone.Good else PsTone.Busy,
                                pill = st,
                                onClick = { ticketForActions = t },
                            )
                        }
                    }
                    item { PsSectionLabel("Add someone to the queue") }
                    item { PsSurface { queueForm() } }
                }
                else -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = dateFilter,
                                onValueChange = onDateFilterChange,
                                label = { Text("Day (YYYY-MM-DD)") },
                                placeholder = { Text("All days") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = { onPage(1) }, enabled = !loadingDetails) { Text("Show") }
                        }
                    }
                    if (loadingDetails) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (recentSessions.isEmpty() && !loadingDetails) {
                        item {
                            PsEmptyState(Icons.Filled.History, "No sessions", "Nothing recorded for this day.", "Reload", onReload)
                        }
                    }
                    items(recentSessions) { s ->
                        val paid = s.optString("payment_status", "UNPAID").uppercase(Locale.US) == "PAID"
                        val when_ = s.optString("end_time").takeIf { it.isNotBlank() && it != "null" }
                            ?: s.optString("start_time").takeIf { it.isNotBlank() && it != "null" } ?: ""
                        PsListRow(
                            title = s.optString("game_name").takeIf { it.isNotBlank() && it != "null" } ?: "Session",
                            detail = "${s.optInt("duration_minutes", 0)} min  -  ${when_.replace('T', ' ').take(16)}",
                            tone = if (paid) PsTone.Good else PsTone.Warn,
                            pill = if (paid) "PAID" else "UNPAID",
                            trailingValue = kes(s.optDouble("amount_charged_kes", 0.0)),
                        )
                    }
                    if (pages > 1) {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { onPage((page - 1).coerceAtLeast(1)) }, enabled = !loadingDetails && page > 1) { Text("Previous") }
                                Text("Page $page of $pages  ($total)", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                                OutlinedButton(onClick = { onPage((page + 1).coerceAtMost(pages)) }, enabled = !loadingDetails && page < pages) { Text("Next") }
                            }
                        }
                    }
                }
            }
        }
    }
    ticketForActions?.let { t ->
        val who = t.optString("player_name").takeIf { it.isNotBlank() && it != "null" }
            ?: t.optString("player_phone").takeIf { it.isNotBlank() && it != "null" } ?: "Walk-in"
        val num = t.optInt("ticket_number", 0)
        val free = label == "FREE"
        AlertDialog(
            onDismissRequest = { ticketForActions = null },
            title = { Text("#$num  $who") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.optString("game_name").takeIf { it.isNotBlank() && it != "null" } ?: "", color = PSBillThemeColors.TextSecondary)
                    Button(
                        onClick = { ticketForActions = null; onStartFromTicket(t) },
                        enabled = free && !busy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PsTone.Good)
                    ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start their session now") }
                    if (!free) Text("The screen is busy. Start their session when it is free.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    if (t.optString("status").uppercase(Locale.US) == "WAITING") {
                        OutlinedButton(onClick = { ticketForActions = null; onQueueStatus(t, "CALLED", "Called #$num") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Call them (show on the TV)")
                        }
                    }
                    OutlinedButton(onClick = { ticketForActions = null; onQueueStatus(t, "SKIPPED", "#$num skipped") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Skip - not here")
                    }
                    OutlinedButton(
                        onClick = { ticketForActions = null; onQueueStatus(t, "CANCELLED", "#$num removed") }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PsTone.Bad)
                    ) { Text("Remove from queue") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ticketForActions = null }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenControl(
    node: JSONObject,
    label: String,
    games: List<JSONObject>,
    busy: Boolean,
    selectedGame: String,
    onGameChange: (String) -> Unit,
    selectedBilling: String,
    onBillingChange: (String) -> Unit,
    onAction: (String, JSONObject, String) -> Unit,
    onLoserStart: (String, String, String) -> Unit,
) {
    val name = nodeName(node)
    val due = node.optDouble("active_billing_kes", 0.0)
    var playerA by remember { mutableStateOf("") }
    var playerB by remember { mutableStateOf("") }
    val rate = node.optDouble("rate_per_minute_kes", 0.0)
    var confirmSleep by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (label) {
            "PLAYING", "OVERTIME" -> PsSurface {
                Text("Now playing", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                Text(nodeGame(node).ifBlank { "Game session" }, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PsStat("Bill so far", kes(due), PsTone.Good, Modifier.weight(1f))
                    PsStat("Rate", if (rate > 0) "${kes(rate)}/min" else "-", PSBillThemeColors.TextPrimary, Modifier.weight(1f))
                }
                Button(
                    onClick = { confirmEnd = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PsTone.Bad)
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("End session") }
            }
            "TO PAY" -> PsSurface {
                Text("Session ended", color = PsTone.Warn, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Collect the payment, then free the screen for the next player.", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
                PsStat("Amount to collect", kes(due), PsTone.Good, Modifier.fillMaxWidth())
                Button(
                    onClick = { onAction("reset", JSONObject(), "$name marked paid and freed") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PsTone.Good)
                ) { Icon(Icons.Filled.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Paid - free the screen") }
                OutlinedButton(
                    onClick = { onAction("continue", JSONObject(), "Session resumed on $name") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Continue the same session") }
            }
            "ASLEEP" -> PsSurface {
                Text("This screen is asleep", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("The TV shows a dark sleep screen until you wake it.", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
                Button(
                    onClick = { onAction("open-app", JSONObject(), "$name is awake") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Icon(Icons.Filled.LightMode, null); Spacer(Modifier.width(8.dp)); Text("Wake screen") }
            }
            else -> PsSurface {
                Text("Start a session", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val options = games.map { it.optString("game_name") }.filter { it.isNotBlank() && it != "null" }.distinct()
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = selectedGame.ifBlank { options.firstOrNull().orEmpty() },
                        onValueChange = {}, readOnly = true, label = { Text("Game") },
                        placeholder = { Text("Add games on the dashboard") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) }
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        options.forEach { g ->
                            DropdownMenuItem(text = { Text(g) }, onClick = { onGameChange(g); open = false })
                        }
                    }
                }
                Text("How they pay", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("POSTPAID" to "Pay after", "PREPAID" to "Pay first", "LOSER" to "Loser pays").forEachIndexed { i, (mode, text) ->
                        SegmentedButton(
                            selected = selectedBilling == mode,
                            onClick = { onBillingChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                        ) { Text(text, maxLines = 1, fontSize = 13.sp) }
                    }
                }
                if (selectedBilling == "LOSER") {
                    LoserPayPlayersFields(playerA, { playerA = it }, playerB, { playerB = it })
                }
                Button(
                    onClick = {
                        val game = selectedGame.ifBlank { options.firstOrNull().orEmpty() }
                        if (selectedBilling == "LOSER") { onLoserStart(game, playerA, playerB); return@Button }
                        onAction("start", JSONObject().apply {
                            put("current_game", game)
                            put("billing_mode", selectedBilling)
                        }, "Session started on $name")
                    },
                    enabled = !busy && label != "OFFLINE",
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PsTone.Good)
                ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (selectedBilling == "LOSER") "Start match" else "Start session") }
                if (label == "OFFLINE") {
                    Text("The TV is not connected. Check its power and WiFi.", color = PsTone.Bad, fontSize = 12.sp)
                }
            }
        }

        if (label != "ASLEEP") {
            PsSectionLabel("Screen power")
            OutlinedButton(onClick = { confirmSleep = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bedtime, null); Spacer(Modifier.width(8.dp)); Text("Put screen to sleep")
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the session on $name?") },
            text = { Text("The TV locks and the bill (${kes(due)}) waits for payment.") },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; onAction("stop", JSONObject(), "$name locked") }) { Text("End session", color = PsTone.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep playing") } },
        )
    }
    if (confirmSleep) {
        AlertDialog(
            onDismissRequest = { confirmSleep = false },
            title = { Text("Put $name to sleep?") },
            text = { Text("The TV goes dark and players cannot use it until you press Wake screen here or on the dashboard.") },
            confirmButton = {
                TextButton(onClick = { confirmSleep = false; onAction("close-app", JSONObject(), "$name is asleep") }) { Text("Sleep") }
            },
            dismissButton = { TextButton(onClick = { confirmSleep = false }) { Text("Cancel") } },
        )
    }
}
