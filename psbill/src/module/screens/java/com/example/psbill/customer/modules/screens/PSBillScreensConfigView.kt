package com.example.psbill.customer

import androidx.compose.foundation.verticalScroll
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
    // Turn length, overtime price and rush hours (business-wide; Prices button on the list).
    var pricing by remember { mutableStateOf<JSONObject?>(null) }
    var showPricing by remember { mutableStateOf(false) }
    fun loadPricing() {
        api.get("/api/v1/kiosk/pricing-rules") { code, body ->
            if (code in 200..299) pricing = runCatching { JSONObject(body) }.getOrNull()
        }
    }
    LaunchedEffect(token) { if (token.isNotBlank()) loadPricing() }
    // The staff phone's hotspot the TVs fall back to (Backup WiFi button on the list).
    var backupWifi by remember { mutableStateOf<JSONObject?>(null) }
    var showBackupWifi by remember { mutableStateOf(false) }
    fun loadBackupWifi() {
        api.get("/api/v1/kiosk/backup-wifi") { code, body ->
            if (code in 200..299) backupWifi = runCatching { JSONObject(body) }.getOrNull()
        }
    }

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
                queueTickets = parseObjects(body).filter { it.optString("status").uppercase(Locale.US) in setOf("PENDING_APPROVAL", "WAITING", "CALLED") }
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
                    if (com.example.psbill.customer.authLost(response.code, body)) {
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
                    if (com.example.psbill.customer.authLost(response.code, body)) {
                        onTokenExpired()
                        return@runOnUiThread
                    }
                    if (response.isSuccessful) {
                        val msg = if (endpoint == "topup" || endpoint == "topup-request/approve") topupMessage(body, nodeName(node)) ?: successMessage else successMessage
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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
                // current_game is JSON null on an idle screen; optString turns that into "null".
                selectedGameByNode[nodeId] = nodeGame(node).ifBlank {
                    games.map { it.optString("game_name") }.firstOrNull { it.isNotBlank() && it != "null" } ?: ""
                }
            }
            if (nodeId.isNotBlank() && !selectedBillingByNode.containsKey(nodeId)) {
                selectedBillingByNode[nodeId] = node.optString("billing_mode", "POSTPAID")
            }
        }
    }


    var openNodeId by remember { mutableStateOf<String?>(null) }
    // A tapped staff alert (top-up, session ended, overtime, queue...): open that screen's detail
    val alertScreen = com.example.psbill.customer.StaffAlertNav.openScreen.value
    LaunchedEffect(alertScreen, nodes.size) {
        val node = alertScreen?.let { id -> nodes.firstOrNull { it.optString("id") == id } } ?: return@LaunchedEffect
        openNodeId = alertScreen
        com.example.psbill.customer.StaffAlertNav.openScreen.value = null
        loadScreenDetails(node)
    }
    val openNode = nodes.firstOrNull { it.optString("id") == openNodeId }

    // While a screen is open and running, refresh every 5 s so the time left / bill is
    // live (the main list refreshes only every 25 s). Between refreshes LiveBill ticks.
    val openLive = openNode != null && screenStatus(openNode).first in setOf("PLAYING", "OVERTIME", "WANTS TIME", "CONSOLE OFF")
    LaunchedEffect(openNodeId, openLive) {
        while (openLive) { delay(5_000); onRefresh() }
    }

    if (openNode != null) {
        BackHandler { openNodeId = null }
        // Top-ups cost the overtime price while someone waits for this screen or in rush hours.
        val waitingHere = queueTickets.any { it.optString("status").uppercase(Locale.US) == "WAITING" }
        val why = when {
            waitingHere -> "someone is waiting"
            pricing?.optBoolean("rush_hour_now") == true -> "rush hour"
            else -> null
        }
        val otRate = openNode.optDouble("overtime_rate_per_minute_kes", 0.0).takeIf { !it.isNaN() && it > 0 }
            ?: pricing?.optDouble("overtime_rate_per_minute", 0.0)?.takeIf { !it.isNaN() && it > 0 }
        val overcharge = if (why != null && otRate != null) otRate to why else null
        ScreenDetail(
            node = openNode,
            overcharge = overcharge,
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
            onQueueDecision = { ticket, decision, message ->
                actionNodeId = openNode.optString("id")
                api.post("/api/v1/kiosk/queue/tickets/${ticket.optString("id")}/$decision", JSONObject()) { code, body ->
                    actionNodeId = null
                    if (code in 200..299) { api.toast(message); loadScreenDetails(openNode) }
                    else api.toast(api.errorText(code, body, "Could not answer the request"))
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
                    // Pay-first needs the amount from the Control tab; from the queue it is pay-after.
                    put("billing_mode", "POSTPAID")
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
                        else loadScreenDetails(openNode)   // e.g. the session already ended: the card goes away
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
            activityTab = { ScreenActivitySection(api, openNode, onRefresh) },
            shotsTab = { ScreenShotsSection(api, openNode) },
            showShots = pricing?.optBoolean("screenshots_enabled") == true &&
                openNode.optString("screenshot_support") != "black",
        )
        return
    }

    if (showPricing) {
        PricingRulesDialog(
            current = pricing,
            onDismiss = { showPricing = false },
            onSave = { body, done ->
                api.put("/api/v1/kiosk/pricing-rules", body) { code, text ->
                    if (code in 200..299) {
                        pricing = runCatching { JSONObject(text) }.getOrNull()
                        api.toast("Prices saved - the TVs use them within seconds")
                        showPricing = false
                        done(null)
                    } else done(api.errorText(code, text, "Could not save"))
                }
            },
        )
    }

    if (showBackupWifi) {
        BackupWifiDialog(
            current = backupWifi,
            nodes = nodes,
            onDismiss = { showBackupWifi = false },
            onSave = { body, done ->
                api.put("/api/v1/kiosk/backup-wifi", body) { code, text ->
                    if (code in 200..299) {
                        backupWifi = runCatching { JSONObject(text) }.getOrNull()
                        api.toast("Saved - each TV asks to save it once it is idle (press OK on the TV)")
                        showBackupWifi = false
                        done(null)
                    } else done(api.errorText(code, text, "Could not save"))
                }
            },
        )
    }

    val playing = nodes.count { screenStatus(it).first in setOf("PLAYING", "OVERTIME", "WANTS TIME", "CONSOLE OFF") }
    val toPay = nodes.count { screenStatus(it).first == "TO PAY" }
    val free = nodes.count { screenStatus(it).first == "FREE" }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            PsPageHeader("Screens", "Tap a screen to start, end or manage it") {
                // Prices, queue rules and backup WiFi are the lounge admin's (agents get 403 OWNER_ONLY)
                if (LocalPsProfile.current?.isAdmin != false) {
                    TextButton(onClick = { loadPricing(); showPricing = true }) {
                        Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Prices")
                    }
                    TextButton(onClick = { loadBackupWifi(); showBackupWifi = true }) { Text("Backup WiFi") }
                }
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
                "PLAYING", "OVERTIME", "WANTS TIME", "CONSOLE OFF" -> game.ifBlank { "Session running" }
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
                trailingValue = if (due > 0 && label in setOf("PLAYING", "OVERTIME", "WANTS TIME", "CONSOLE OFF", "TO PAY")) kes(due) else null,
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
    overcharge: Pair<Double, String>?,
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
    onQueueDecision: (JSONObject, String, String) -> Unit,
    onCallNext: () -> Unit,
    onStartFromTicket: (JSONObject) -> Unit,
    onLoserStart: (String, String, String) -> Unit,
    onRound: (String?) -> Unit,
    onEndMatch: () -> Unit,
    queueForm: @Composable () -> Unit,
    activityTab: @Composable () -> Unit = {},
    shotsTab: @Composable () -> Unit = {},
    showShots: Boolean = false,
) {
    val name = nodeName(node)
    var ticketForActions by remember { mutableStateOf<JSONObject?>(null) }
    val (label, tone) = screenStatus(node)
    var tab by remember { mutableStateOf(0) }
    // Activity (the log + pause/remove) is for the lounge admin only
    val admin = LocalPsProfile.current?.isAdmin != false
    val tabKeys = listOf("control", "queue", "history") + (if (admin) listOf("activity") else emptyList()) +
        (if (showShots) listOf("shots") else emptyList())
    val tabs = tabKeys.map { when (it) { "control" -> "Control"; "queue" -> "Queue (${queueTickets.size})"; "history" -> "History"
        "activity" -> "Activity"; else -> "Shots" } }
    if (tab >= tabs.size) tab = 0

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
            when (tabKeys[tab]) {
                "activity" -> item { activityTab() }
                "shots" -> item { shotsTab() }
                "control" -> item {
                    // Results can only be entered while the screen's session runs (the server refuses after)
                    val live = node.optString("status").uppercase(Locale.US) in setOf("ACTIVE", "OVERTIME", "PENDING") &&
                        !node.optBoolean("has_ended_session")
                    if (activeMatch != null && live) {
                        LoserPayMatchCard(activeMatch, null, busy, onRound, onEndMatch)
                    } else {
                        ScreenControl(node, label, games, busy, selectedGame, onGameChange, selectedBilling, onBillingChange, onAction, onLoserStart, overcharge)
                    }
                }
                "queue" -> {
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
                                tone = when (st) { "CALLED" -> PsTone.Good; "PENDING_APPROVAL" -> PsTone.Warn; else -> PsTone.Busy },
                                pill = if (st == "PENDING_APPROVAL") "APPROVE?" else st,
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
                        s.optJSONObject("loser_pay")?.let { LoserPayBreakdown(it) }
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
                    if (t.optString("status").uppercase(Locale.US) == "PENDING_APPROVAL") {
                        Text("Asked on the WiFi page. Approve only if they are really here.",
                            color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                        Button(
                            onClick = { ticketForActions = null; onQueueDecision(t, "approve", "#$num added to the queue") },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PsTone.Good)
                        ) { Text("Approve - add to the queue") }
                        OutlinedButton(
                            onClick = { ticketForActions = null; onQueueDecision(t, "reject", "#$num rejected") },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PsTone.Bad)
                        ) { Text("Reject") }
                    } else {
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
    overcharge: Pair<Double, String>? = null,
) {
    val name = nodeName(node)
    val due = node.optDouble("active_billing_kes", 0.0)
    var playerA by remember { mutableStateOf("") }
    var playerB by remember { mutableStateOf("") }
    val rate = node.optDouble("rate_per_minute_kes", 0.0)
    var confirmSleep by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var showTopup by remember { mutableStateOf(false) }
    var paidAmount by remember(node.optString("id")) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A player at the TV picked a game while others are queued: the attendant decides.
        pendingGameRequest(node)?.let { req ->
            val want = req.optString("requested_game")
            val queued = req.optString("ticket_game")
            val who = req.optString("player_name").takeIf { it.isNotBlank() && it != "null" } ?: "a player"
            PsSurface {
                Text(if (req.optBoolean("different_game")) "The player picked another game" else "A player wants to start",
                    color = PsTone.Warn, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Wants: $want", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Queued: #${req.optInt("ticket_number")} $who - $queued", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
                Text("Approve starts $want, billed at its price, for #${req.optInt("ticket_number")}. Refuse keeps the screen locked.",
                    color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy,
                        onClick = { onAction("game-request/approve", JSONObject(), "$want started on $name") }) { Text("Approve") }
                    TextButton(enabled = !busy,
                        onClick = { onAction("game-request/decline", JSONObject(), "Refused - the TV tells the player") }) {
                        Text("Refuse", color = PsTone.Bad)
                    }
                }
            }
        }
        // The player asked for more time on the TV: take the money, then approve.
        pendingTopupRequest(node)?.let { req ->
            val mins = req.optInt("minutes")
            val amount = req.optDouble("amount_kes", 0.0)
            PsSurface {
                Text("The player wants more time", color = PsTone.Warn, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "+$mins min = ${kes(amount)}" + when (req.optString("reason")) {
                        "queue" -> " (overtime price: someone is waiting)"
                        "rush" -> " (overtime price: rush hour)"
                        else -> ""
                    },
                    color = PSBillThemeColors.TextPrimary, fontSize = 14.sp
                )
                Text("Take the money first, then approve. The price is checked again when you approve.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { onAction("topup-request/approve", JSONObject(), "Added $mins min on $name") },
                    ) { Text("Paid - approve") }
                    TextButton(
                        enabled = !busy,
                        onClick = { onAction("topup-request/decline", JSONObject(), "Request declined on $name") },
                    ) { Text("Decline", color = PsTone.Bad) }
                }
            }
        }
        when (label) {
            "PLAYING", "OVERTIME", "WANTS TIME", "CONSOLE OFF" -> PsSurface {
                Text("Now playing", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                Text(nodeGame(node).ifBlank { "Game session" }, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                LiveBill(node)
                if (isPrepaid(node)) {
                    Button(
                        onClick = { showTopup = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PsTone.Good)
                    ) { Icon(Icons.Filled.AddCircle, null); Spacer(Modifier.width(8.dp)); Text("Top up time") }
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
                if (isPrepaid(node)) {
                    // Pay-first locks when the time is used up; it continues only after a top-up.
                    OutlinedButton(
                        onClick = { showTopup = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Filled.AddCircle, null); Spacer(Modifier.width(8.dp)); Text("Top up and continue") }
                } else {
                    OutlinedButton(
                        onClick = { onAction("continue", JSONObject(), "Session resumed on $name") }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Continue the same session") }
                }
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
                        value = selectedGame.takeIf { it.isNotBlank() && it != "null" } ?: options.firstOrNull().orEmpty(),
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
                val game = selectedGame.takeIf { it.isNotBlank() && it != "null" } ?: options.firstOrNull().orEmpty()
                val gameRate = games.firstOrNull { it.optString("game_name") == game }
                    ?.optDouble("base_rate_per_minute", 0.0)?.takeIf { it > 0 } ?: rate
                if (selectedBilling == "LOSER") {
                    LoserPayPlayersFields(playerA, { playerA = it }, playerB, { playerB = it })
                }
                if (selectedBilling == "PREPAID") {
                    AmountToMinutes(paidAmount, { paidAmount = it }, gameRate, "Amount paid (KSh)")
                }
                val amount = paidAmount.toDoubleOrNull() ?: 0.0
                Button(
                    onClick = {
                        if (selectedBilling == "LOSER") { onLoserStart(game, playerA, playerB); return@Button }
                        onAction("start", JSONObject().apply {
                            put("current_game", game)
                            put("billing_mode", selectedBilling)
                            if (selectedBilling == "PREPAID") put("amount_kes", amount)
                        }, if (selectedBilling == "PREPAID") "$name: ${minutesText(amount, gameRate)} started" else "Session started on $name")
                    },
                    enabled = !busy && label != "OFFLINE" && (selectedBilling != "PREPAID" || amount > 0),
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
    if (showTopup) {
        var topup by remember { mutableStateOf("") }
        val topupAmount = topup.toDoubleOrNull() ?: 0.0
        val topupRate = overcharge?.first ?: rate
        AlertDialog(
            onDismissRequest = { showTopup = false },
            title = { Text("Top up $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (label == "TO PAY") "Take the money first. The screen unlocks and the same session continues."
                        else "Take the money first. The minutes are added to the time left.",
                        color = PSBillThemeColors.TextSecondary, fontSize = 13.sp
                    )
                    if (overcharge != null) {
                        Text("Overtime price now: ${kes(overcharge.first)}/min (${overcharge.second}).", color = PsTone.Warn, fontSize = 13.sp)
                    }
                    AmountToMinutes(topup, { topup = it }, topupRate, "Amount paid (KSh)")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = topupAmount > 0 && topupRate > 0,
                    onClick = {
                        showTopup = false
                        onAction("topup", JSONObject().put("amount_kes", topupAmount), "Added ${minutesText(topupAmount, topupRate)} on $name")
                    }
                ) { Text("Add time") }
            },
            dismissButton = { TextButton(onClick = { showTopup = false }) { Text("Cancel") } },
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


private fun isPrepaid(node: JSONObject) = node.optString("billing_mode").uppercase(Locale.US) == "PREPAID"

/** "20 min" / "2 min 30 s" for an amount at a price per minute. */
private fun minutesText(amount: Double, rate: Double): String {
    if (rate <= 0 || amount <= 0) return "0 min"
    val secs = Math.round(amount / rate * 60).toInt()
    val m = secs / 60; val s = secs % 60
    return if (s == 0) "$m min" else "$m min $s s"
}

private fun clock(totalSecs: Int): String {
    val t = maxOf(0, totalSecs)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

/** Amount field that shows how many minutes it buys at the game's price. */
@Composable
private fun AmountToMinutes(value: String, onChange: (String) -> Unit, rate: Double, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(7)) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
    )
    val amount = value.toDoubleOrNull() ?: 0.0
    Text(
        when {
            rate <= 0 -> "This game has no price per minute yet - set it on the dashboard."
            amount <= 0 -> "${kes(rate)} per minute"
            else -> "= ${minutesText(amount, rate)} of play (${kes(rate)}/min)"
        },
        color = if (rate <= 0) PsTone.Bad else PsTone.Good, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
    )
}

/**
 * Time left (pay first) or bill so far (pay after), ticking every second between the
 * server refreshes. The TV is the timer authority; each refresh re-bases this.
 */
@Composable
private fun LiveBill(node: JSONObject) {
    val key = node.toString()
    var ticks by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key) { while (true) { delay(1_000); ticks++ } }
    val rate = node.optDouble("rate_per_minute_kes", 0.0)
    val overtime = node.optString("status").uppercase(Locale.US) == "OVERTIME"
    val total = node.optDouble("active_billing_kes", 0.0)
    if (isPrepaid(node)) {
        val left = if (overtime) 0 else node.optInt("seconds_remaining") - ticks
        val over = node.optInt("seconds_elapsed_overtime") + if (overtime) ticks else 0
        val runningOut = !overtime && left in 1..120
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (overtime) PsStat("Overtime", clock(over), PsTone.Warn, Modifier.weight(1f))
            else PsStat("Time left", clock(left), if (runningOut) PsTone.Warn else PsTone.Good, Modifier.weight(1f))
            PsStat(if (overtime) "Paid + extra" else "Paid", kes(total), PSBillThemeColors.TextPrimary, Modifier.weight(1f))
        }
        when {
            overtime -> Text("Time is up. Overtime is ${kes(rate)}/min - top up or end the session.", color = PsTone.Warn, fontSize = 13.sp)
            runningOut -> Text("Less than 2 minutes left.", color = PsTone.Warn, fontSize = 13.sp)
        }
    } else {
        val elapsed = node.optInt("seconds_elapsed") + node.optInt("seconds_elapsed_overtime") + ticks
        val minCharge = node.optDouble("minimum_charge_kes", 0.0)
        // Played past the turn while others waited: those minutes cost the overtime price.
        val queued = node.optInt("queued_overtime_seconds", 0).coerceIn(0, elapsed)
        val otRate = node.optDouble("overtime_rate_per_minute_kes", 0.0).let { if (it.isNaN() || it <= 0) rate else it }
        val bill = maxOf(total, minCharge, (elapsed - queued) / 60.0 * rate + queued / 60.0 * otRate)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PsStat("Bill so far", kes(Math.round(bill).toDouble()), PsTone.Good, Modifier.weight(1f))
            PsStat("Playing for", clock(elapsed), PSBillThemeColors.TextPrimary, Modifier.weight(1f))
        }
        Text(if (rate > 0) "${kes(rate)}/min, at least ${kes(minCharge)}" else "", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        if (queued > 0) {
            Text("Overtime ${clock(queued)} at ${kes(otRate)}/min - someone is waiting. The player was told on the TV.",
                color = PsTone.Warn, fontSize = 13.sp)
        }
    }
}


/** Toast text for a top-up from the server's answer (minutes, price used, why). */
private fun topupMessage(body: String, name: String): String? = runCatching {
    val j = JSONObject(body)
    val mins = j.optInt("added_seconds") / 60
    val rate = j.optDouble("rate_used", 0.0)
    val why = when (j.optString("overcharge")) {
        "queue" -> " (overtime price: someone is waiting)"
        "rush" -> " (overtime price: rush hour)"
        else -> ""
    }
    "Added $mins min on $name at ${kes(rate)}/min$why"
}.getOrNull()

/** Turn length, overtime price and rush hours for the whole business. */
@Composable
private fun BackupWifiDialog(
    current: JSONObject?,
    nodes: List<JSONObject>,
    onDismiss: () -> Unit,
    onSave: (JSONObject, (String?) -> Unit) -> Unit,
) {
    var ssid by remember(current) { mutableStateOf(current?.optString("ssid").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasPassword = current?.optBoolean("has_password") == true
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Backup WiFi (phone hotspot)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "When the lounge WiFi is down, turn on this phone's hotspot. The TVs that saved it join it, " +
                        "so the screens stay in touch through the phone's data. Sessions keep running either way.",
                    color = PSBillThemeColors.TextSecondary, fontSize = 13.sp
                )
                OutlinedTextField(
                    ssid, { ssid = it.take(32) }, singleLine = true,
                    label = { Text("Hotspot name") },
                    supportingText = { Text("Exactly as in the phone's hotspot settings. Empty = no backup") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    password, { password = it.take(63) }, singleLine = true,
                    label = { Text("Hotspot password") },
                    placeholder = { Text(if (hasPassword) "Saved - type to change" else "8 to 63 characters") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (nodes.isNotEmpty() && current?.optString("ssid").orEmpty().isNotBlank()) {
                    Text("On the TVs", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
                    nodes.forEach { n ->
                        val st = when (n.optString("backup_wifi_state")) {
                            "saved" -> "saved" to PsTone.Good
                            "ask" -> "press OK on the TV when it asks" to PsTone.Warn
                            "suggested" -> "add it by hand in the TV's WiFi settings" to PsTone.Warn
                            else -> "not yet (TV app update or sync pending)" to PSBillThemeColors.TextSecondary
                        }
                        Text("${nodeName(n)}: ${st.first}", color = st.second, fontSize = 12.sp)
                    }
                }
                error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val name = ssid.trim()
                if (name.isNotEmpty() && password.isEmpty() && !hasPassword) {
                    error = "Type the hotspot password"; return@TextButton
                }
                if (password.isNotEmpty() && password.length < 8) {
                    error = "The password must be at least 8 characters"; return@TextButton
                }
                saving = true; error = null
                val body = JSONObject().apply {
                    put("ssid", name)
                    // keep the saved password when the field is left empty
                    put("password", password.ifEmpty { if (hasPassword && name == current?.optString("ssid")) KEEP_PASSWORD else "" })
                }
                onSave(body) { err -> saving = false; error = err }
            }) { Text(if (saving) "Saving..." else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Sent as the password to keep the one already saved (the server never returns it). */
private const val KEEP_PASSWORD = "\u0000keep"

@Composable
private fun PricingRulesDialog(
    current: JSONObject?,
    onDismiss: () -> Unit,
    onSave: (JSONObject, (String?) -> Unit) -> Unit,
) {
    var turn by remember(current) { mutableStateOf(current?.optInt("turn_minutes", 30)?.toString() ?: "30") }
    var ot by remember(current) {
        mutableStateOf(current?.optDouble("overtime_rate_per_minute", 0.0)?.takeIf { it > 0 }?.let {
            if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
        } ?: "")
    }
    var rushOn by remember(current) { mutableStateOf(current?.optBoolean("rush_hours_enabled") == true) }
    var rush by remember(current) { mutableStateOf(current?.optString("rush_hours").orEmpty().ifBlank { "17:00-22:00" }) }
    var speed by remember(current) { mutableStateOf(current?.optInt("wifi_speed_mbps", 5)?.toString() ?: "5") }
    var shotsOn by remember(current) { mutableStateOf(current?.optBoolean("screenshots_enabled") == true) }
    var queueStrict by remember(current) { mutableStateOf(current?.optBoolean("queue_strict", true) != false) }
    var amber by remember(current) { mutableStateOf(current?.optInt("bill_amber_kes", 100)?.toString() ?: "100") }
    var red by remember(current) { mutableStateOf(current?.optInt("bill_red_kes", 200)?.toString() ?: "200") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Prices, rush hours & WiFi speed") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                OutlinedTextField(
                    turn, { turn = it.filter(Char::isDigit).take(3) }, singleLine = true,
                    label = { Text("Turn length (minutes)") },
                    supportingText = { Text("Pay-after: after this, overtime starts if someone waits or in rush hours") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    ot, { ot = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, singleLine = true,
                    label = { Text("Overtime price (KSh per minute)") },
                    placeholder = { Text("Empty = the game's normal price") },
                    supportingText = { Text("Pay-after minutes past the turn, and pay-first top-ups") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Queued players go first", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
                        Text("While someone is queued for a screen, no one can continue, resume or add time on it. " +
                            "Turn off to allow extra time at the overtime price.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    }
                    Switch(checked = queueStrict, onCheckedChange = { queueStrict = it })
                }
                Text("Pay-after meter on the TV", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text("The meter turns amber, then red and blinking, as the bill grows. The player is told when the " +
                    "minimum time is used up and at each colour. 0 = off.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        amber, { amber = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text("Amber at KSh") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        red, { red = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text("Red at KSh") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rush hours", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
                        Text("Overtime price applies even when no one is queued", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    }
                    Switch(checked = rushOn, onCheckedChange = { rushOn = it })
                }
                if (rushOn) {
                    OutlinedTextField(
                        rush, { rush = it.take(200) }, singleLine = true,
                        label = { Text("Times (Nairobi)") },
                        supportingText = { Text("Like 17:00-22:00, or 12:00-14:00, 17:00-23:00") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (current?.optBoolean("rush_hour_now") == true) {
                    Text("It is rush hour now.", color = PsTone.Warn, fontSize = 13.sp)
                }
                OutlinedTextField(
                    speed, { speed = it.filter(Char::isDigit).take(3) }, singleLine = true,
                    label = { Text("Free WiFi speed (Mbps per player)") },
                    supportingText = { Text("Every free WiFi pass (queue, after playing). Paid WiFi keeps its own speed.") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gameplay screenshots", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
                        Text("A picture of each game once a minute (Shots tab). Each TV asks once to capture the screen; " +
                            "TVs that only give black pictures stop by themselves.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    }
                    Switch(checked = shotsOn, onCheckedChange = { shotsOn = it })
                }
                error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true; error = null
                val body = JSONObject().apply {
                    put("screenshots_enabled", shotsOn)
                    put("turn_minutes", turn.toIntOrNull() ?: 30)
                    put("overtime_rate_per_minute", ot.toDoubleOrNull() ?: 0.0)
                    put("rush_hours_enabled", rushOn)
                    put("rush_hours", rush.trim())
                    put("wifi_speed_mbps", speed.toIntOrNull() ?: 5)
                    put("queue_strict", queueStrict)
                    put("bill_amber_kes", amber.toIntOrNull() ?: 0)
                    put("bill_red_kes", red.toIntOrNull() ?: 0)
                }
                onSave(body) { err -> saving = false; error = err }
            }) { Text(if (saving) "Saving..." else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}


/** Loser pay under a session: each game (who won, minutes, cost) and what player 1 and player 2 pay. */
@Composable
private fun LoserPayBreakdown(lp: JSONObject) {
    val games = lp.optJSONArray("games") ?: return
    if (games.length() == 0) return
    val a = lp.optString("player_a").takeIf { it.isNotBlank() && it != "null" } ?: "Player 1"
    val b = lp.optString("player_b").takeIf { it.isNotBlank() && it != "null" } ?: "Player 2"
    PsSurface {
        Text("LOSER PAYS - by game", color = PsTone.Warn, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth()) {
            Text("Game", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Result", color = PSBillThemeColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1.5f))
            Text(a.take(10), color = PSBillThemeColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(b.take(10), color = PSBillThemeColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val result = when (g.optString("result")) {
                "A" -> "$a won"
                "B" -> "$b won"
                else -> "Draw - split"
            }
            val payA = g.optDouble("amount_player_a", 0.0)
            val payB = g.optDouble("amount_player_b", 0.0)
            Row(Modifier.fillMaxWidth()) {
                Text("${g.optInt("round_number")} (${g.optInt("minutes")}m)", color = PSBillThemeColors.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("$result, ${kes(g.optDouble("amount_kes", 0.0))}", color = PSBillThemeColors.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1.5f))
                Text(if (payA > 0) kes(payA) else "-", color = if (payA > 0) PsTone.Warn else PSBillThemeColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(if (payB > 0) kes(payB) else "-", color = if (payB > 0) PsTone.Warn else PSBillThemeColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("To pay", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(2.5f))
            Text(kes(lp.optDouble("total_amount_player_a", 0.0)), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(kes(lp.optDouble("total_amount_player_b", 0.0)), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }
}
