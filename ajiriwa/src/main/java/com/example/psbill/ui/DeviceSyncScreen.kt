package com.example.psbill.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.DeliveryTrackingService
import com.example.psbill.core.DeviceAgent
import com.example.psbill.core.DeviceIdentity
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.PhoneApi
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val STREAM_LABELS = linkedMapOf(
    "sms" to "SMS (received & sent)",
    "mpesa" to "M-Pesa messages",
    "calls" to "Call logs",
    "contacts" to "Contacts",
    "location" to "Location",
)

private suspend fun api(context: Context, path: String, body: JSONObject? = null, method: String? = null): JSONObject =
    withContext(Dispatchers.IO) { PhoneApi.request(context, path, body, method ?: if (body != null) "POST" else "GET") }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun daysAgo(n: Int): String = dayFmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time)

private fun ago(iso: String?): String {
    if (iso.isNullOrBlank()) return "never"
    val t = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(19))?.time
    }.getOrNull() ?: return iso
    return agoMs(t)
}

private fun agoMs(t: Long): String {
    if (t <= 0) return "never"
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

private fun localTime(iso: String?, pattern: String = "d MMM HH:mm"): String {
    if (iso.isNullOrBlank()) return ""
    val utc = iso.endsWith("Z")
    val t = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { if (utc) timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(19))
    }.getOrNull() ?: return iso
    return SimpleDateFormat(pattern, Locale.getDefault()).format(t)
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) AjiriwaColors.PrimaryAccent else AjiriwaColors.SurfaceAlt,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(text, color = if (selected) Color.White else AjiriwaColors.TextSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(99.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun stateColor(state: String) = when (state) {
    "IN_SYNC" -> AjiriwaColors.Success
    "SYNCING" -> AjiriwaColors.Secondary
    "PENDING" -> AjiriwaColors.Warning
    else -> AjiriwaColors.Danger
}

private fun stateText(state: String, waiting: Int) = when (state) {
    "IN_SYNC" -> "In sync"
    "SYNCING" -> "Syncing"
    "PENDING" -> "$waiting waiting"
    else -> "Offline"
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AjiriwaColors.Surface).padding(14.dp),
        content = content,
    )
}

/**
 * Ask for location once per sign-in and start the always-on agent (tracking +
 * sync heartbeat). Composed for as long as someone is signed in.
 */
@Composable
fun DeviceAgentEffects(ctx: ModuleContext) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        DeviceAgent.start(context)
        DeviceAgent.start(context, DeliveryTrackingService.ACTION_SETTINGS_CHANGED)
    }
    LaunchedEffect(ctx.partnerId) {
        if (ctx.partnerId.isBlank()) return@LaunchedEffect
        DeviceAgent.start(context)
        val want = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val perms = DeviceAgent.permissions(context)
        if (!perms.optBoolean("location") || !perms.optBoolean("notifications")) launcher.launch(want.toTypedArray())
    }
}

/** Sync & tracking: this phone, all phones, synced data, movement. */
@Composable
fun DeviceSyncScreen(ctx: ModuleContext) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, containerColor = AjiriwaColors.Canvas, contentColor = AjiriwaColors.Primary, edgePadding = 8.dp) {
            listOf("This phone", "All phones", "Synced data", "Movement").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> ThisPhone(ctx)
            1 -> AllPhones(ctx)
            2 -> SyncedData(ctx)
            3 -> Movement(ctx)
        }
    }
}

// ── This phone ───────────────────────────────────────────────────────────────

@Composable
private fun ThisPhone(ctx: ModuleContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by DeviceAgent.state.collectAsState()
    var pending by remember { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val dev = st.device
    val perms = remember(tick) { DeviceAgent.permissions(context) }

    LaunchedEffect(Unit) { DeviceAgent.kick(context) }
    LaunchedEffect(tick, st.lastHeartbeatAt, st.runningRequestId) {
        pending = withContext(Dispatchers.IO) { DeviceAgent.pendingCounts(context) }
    }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        DeviceAgent.start(context, DeliveryTrackingService.ACTION_SETTINGS_CHANGED)
    }

    fun requestSync(full: Boolean) {
        val id = dev?.optString("id").orEmpty()
        if (id.isBlank()) { DeviceAgent.kick(context); return }
        busy = true
        scope.launch {
            runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/devices/$id/sync", JSONObject().put("streams", "all").put("full", full)) }
                .onSuccess { DeviceAgent.kick(context); Toast.makeText(context, if (full) "Full resync started" else "Sync started", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    fun patch(body: JSONObject) {
        val id = dev?.optString("id").orEmpty()
        if (id.isBlank()) return
        scope.launch {
            runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/devices/$id", body, "PATCH") }
                .onSuccess {
                    DeviceAgent.kick(context)
                    delay(1500)
                    DeviceAgent.start(context, DeliveryTrackingService.ACTION_SETTINGS_CHANGED)
                }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    val localWaiting = STREAM_LABELS.keys.sumOf { k -> if (DeviceAgent.streamEnabled(context, k)) pending.optInt(k) else 0 }
    val state = when {
        !st.online -> "OFFLINE"
        st.runningRequestId != null -> "SYNCING"
        localWaiting > 0 -> "PENDING"
        else -> "IN_SYNC"
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dev?.optString("name") ?: "${Build.MANUFACTURER} ${Build.MODEL}", color = AjiriwaColors.TextPrimary,
                            fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (st.online) "Connected to server · ${agoMs(st.lastHeartbeatAt)}" else "Can't reach the server${st.lastError?.let { " · $it" } ?: ""}",
                            color = AjiriwaColors.TextMuted, fontSize = 12.sp, maxLines = 2)
                    }
                    StatusPill(stateText(state, localWaiting), stateColor(state))
                }
                if (st.runningRequestId != null || st.progress.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    if (st.runningRequestId != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AjiriwaColors.PrimaryAccent)
                    st.progress.forEach { (k, v) ->
                        Text("${STREAM_LABELS[k] ?: k}: $v", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                    }
                }
                st.lastResult?.let { if (st.runningRequestId == null) Text(it, color = AjiriwaColors.TextMuted, fontSize = 12.sp) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requestSync(false) }, enabled = !busy && st.runningRequestId == null,
                        colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent)) { Text("Sync now") }
                    OutlinedButton(onClick = { requestSync(true) }, enabled = !busy && st.runningRequestId == null) { Text("Full resync") }
                }
                TextButton(onClick = { showClear = true }) { Text("Clear synced data & resync…", color = AjiriwaColors.Danger) }
            }
        }

        st.appUpdate?.let { u ->
            item {
                Panel {
                    Text("Update available: v${u.optString("version_name")}", color = AjiriwaColors.Success, fontWeight = FontWeight.Bold)
                    u.optString("notes").takeIf { it.isNotBlank() && it != "null" }?.let {
                        Text(it, color = AjiriwaColors.TextSecondary, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Download, open the file and tap Install. Your data and sign-in stay.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.optString("url")))) }
                    }, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Success)) { Text("Download update") }
                }
            }
        }

        val missing = buildList {
            if (!perms.optBoolean("location")) add("location")
            if (perms.optBoolean("location") && !perms.optBoolean("background_location")) add("background")
            if (!perms.optBoolean("notifications")) add("notifications")
        }
        if (missing.isNotEmpty()) item {
            Panel {
                Text("Permissions", color = AjiriwaColors.Warning, fontWeight = FontWeight.Bold)
                if ("location" in missing) {
                    Text("Location is off — movement can't be tracked.", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
                }
                if ("background" in missing && Build.VERSION.SDK_INT >= 29) {
                    Text("Choose \"Allow all the time\" so tracking continues after the phone restarts.", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        } else bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }) { Text("Open settings") }
                }
                if ("notifications" in missing && Build.VERSION.SDK_INT >= 33) {
                    TextButton(onClick = { permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Allow notifications") }
                }
            }
        }

        item {
            Panel {
                Text("What this phone syncs", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text("Switch off anything personal. Changes apply on the web too.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                val streams = dev?.optJSONObject("streams")
                val lastSync = dev?.optJSONObject("last_sync")
                STREAM_LABELS.forEach { (k, label) ->
                    val on = streams?.optBoolean(k, true) ?: DeviceAgent.streamEnabled(context, k)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = AjiriwaColors.TextPrimary, fontSize = 14.sp)
                            val p = pending.optInt(k)
                            Text(
                                (if (p > 0 && on) "$p waiting · " else "") + "last synced ${ago(lastSync?.optString(k))}",
                                color = if (p > 0 && on) AjiriwaColors.Warning else AjiriwaColors.TextMuted, fontSize = 12.sp,
                            )
                        }
                        Switch(checked = on, onCheckedChange = { v -> patch(JSONObject().put("streams", JSONObject().put(k, v))) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = AjiriwaColors.Border)
                val tracking = dev?.optBoolean("tracking_enabled", true) ?: DeviceAgent.trackingEnabled(context)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Track movement all day", color = AjiriwaColors.TextPrimary, fontSize = 14.sp)
                        Text("Distance per hour / day / week… on the web and in Movement", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    }
                    Switch(checked = tracking, onCheckedChange = { v -> patch(JSONObject().put("tracking_enabled", v)) })
                }
            }
        }
    }

    if (showClear) ClearDialog(ctx, thisDeviceId = dev?.optString("id")) { showClear = false }
}

@Composable
private fun ClearDialog(ctx: ModuleContext, thisDeviceId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateMapOf("sms" to true, "mpesa" to false, "calls" to true, "contacts" to false, "location" to false) }
    var onlyThis by remember { mutableStateOf(thisDeviceId != null) }
    var removeCustomers by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Clear synced data") },
        text = {
            Column(Modifier.verticalScrollCompat()) {
                Text("Deletes what the phone uploaded (e.g. personal messages synced by mistake) and uploads it again with the current settings. Messages sent by the system and delivery routes are kept.",
                    fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                STREAM_LABELS.forEach { (k, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { picked[k] = !(picked[k] ?: false) }) {
                        Checkbox(checked = picked[k] == true, onCheckedChange = { picked[k] = it })
                        Text(label, fontSize = 14.sp)
                    }
                }
                if (picked["location"] == true) Text("Location already uploaded can't be sent again.", color = AjiriwaColors.Warning, fontSize = 12.sp)
                if (thisDeviceId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlyThis, onCheckedChange = { onlyThis = it })
                    Text("Only this phone (off = every phone)", fontSize = 14.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeCustomers, onCheckedChange = { removeCustomers = it })
                    Text("Also remove customers that only came from phone data (no orders)", fontSize = 13.sp)
                }
                OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Type CLEAR") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && confirm.trim().uppercase() == "CLEAR" && picked.values.any { it }, onClick = {
                busy = true
                scope.launch {
                    val body = JSONObject().apply {
                        put("streams", JSONArray(picked.filterValues { it }.keys.toList()))
                        put("confirm", "CLEAR"); put("resync", true); put("remove_auto_customers", removeCustomers)
                        if (onlyThis && thisDeviceId != null) put("device_id", thisDeviceId)
                    }
                    runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/clear", body) }
                        .onSuccess { r ->
                            val d = r.optJSONObject("deleted")
                            Toast.makeText(context, "Cleared ${d?.keys()?.asSequence()?.joinToString { "$it ${d.optInt(it)}" }}. Uploading again…", Toast.LENGTH_LONG).show()
                            DeviceAgent.kick(context)
                            onClose()
                        }
                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    busy = false
                }
            }) { Text("Clear & resync", color = AjiriwaColors.Danger) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

// ── All phones ───────────────────────────────────────────────────────────────

@Composable
private fun AllPhones(ctx: ModuleContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        while (true) {
            runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/devices") }
                .onSuccess { items = it.optJSONArray("items").objects(); summary = it.optJSONObject("summary"); error = null }
                .onFailure { error = it.message }
            delay(15_000)
        }
    }
    val me = DeviceAgent.state.collectAsState().value.device?.optString("id")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        summary?.let { s ->
            item {
                Text("${s.optInt("online")} of ${s.optInt("devices")} online · ${s.optInt("in_sync")} in sync",
                    color = AjiriwaColors.TextSecondary)
            }
        }
        error?.let { item { Text(it, color = AjiriwaColors.Danger) } }
        if (items.isEmpty() && error == null) item { Text("No phones yet — they appear after signing in to the app.", color = AjiriwaColors.TextMuted) }
        items(items, key = { it.getString("id") }) { d ->
            val state = d.optString("sync_state")
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.optString("name") + if (d.optString("id") == me) " (this phone)" else "", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(d.optString("admin_name").takeIf { it.isNotBlank() && it != "null" }, d.optString("model").takeIf { it.isNotBlank() && it != "null" },
                            if (d.isNull("battery")) null else "battery ${d.optInt("battery")}%").joinToString(" · "),
                            color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                        Text("Seen ${ago(d.optString("last_seen"))}", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusPill(if (d.optBoolean("online")) "Online" else "Offline", if (d.optBoolean("online")) AjiriwaColors.Success else AjiriwaColors.Danger)
                        if (d.optBoolean("online")) StatusPill(stateText(state, d.optInt("waiting")), stateColor(state))
                    }
                }
                d.optJSONObject("open_request")?.let { r ->
                    Text("Sync ${r.optString("status").lowercase()} · asked ${ago(r.optString("created_at"))}", color = AjiriwaColors.Secondary, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/devices/${d.optString("id")}/sync", JSONObject().put("streams", "all")) }
                                .onSuccess { reload++; if (d.optString("id") == me) DeviceAgent.kick(context) }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Sync now") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/devices/${d.optString("id")}/sync", JSONObject().put("streams", "all").put("full", true)) }
                                .onSuccess { reload++; if (d.optString("id") == me) DeviceAgent.kick(context) }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Full resync") }
                }
            }
        }
    }
}

// ── Synced data ──────────────────────────────────────────────────────────────

private val PERIODS = listOf("Today" to 0, "Yesterday" to 1, "7 days" to 7, "30 days" to 30, "All" to -1)

private fun periodRange(p: Int): Pair<String?, String?> = when (p) {
    -1 -> null to null
    0 -> daysAgo(0) to daysAgo(0)
    1 -> daysAgo(1) to daysAgo(1)
    else -> daysAgo(p - 1) to daysAgo(0)
}

@Composable
private fun SyncedData(ctx: ModuleContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stream by remember { mutableStateOf("sms") }
    var period by remember { mutableIntStateOf(0) }
    var groupBy by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var drill by remember { mutableStateOf<Pair<String, JSONObject>?>(null) } // group_key to group row
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var totals by remember { mutableStateOf<JSONObject?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val types = when (stream) {
        "sms" -> listOf("" to "All", "in" to "Received", "out" to "Sent")
        "calls" -> listOf("" to "All", "INCOMING" to "In", "OUTGOING" to "Out", "MISSED" to "Missed")
        "mpesa" -> listOf("" to "All", "RECEIVED" to "Received", "SENT" to "Sent", "PAID" to "Paid", "WITHDRAW" to "Withdraw")
        else -> listOf("" to "All")
    }

    fun url(pg: Int): String {
        val (from, to) = if (stream == "contacts") null to null else periodRange(PERIODS[period].second)
        val grouped = groupBy.isNotBlank() && drill == null
        return buildString {
            append("/api/v1/mobile/${ctx.partnerId}/data/$stream?page=$pg&limit=40")
            from?.let { append("&from=$it") }; to?.let { append("&to=$it") }
            if (q.isNotBlank()) append("&q=" + URLEncoder.encode(q, "UTF-8"))
            if (typeFilter.isNotBlank()) append("&type=$typeFilter")
            if (grouped) append("&group_by=$groupBy")
            drill?.let { (k, g) -> append("&group_key=$k&group=" + URLEncoder.encode(g.optString("key"), "UTF-8")) }
        }
    }

    fun load(reset: Boolean) {
        val pg = if (reset) 1 else page + 1
        loading = true
        scope.launch {
            runCatching { api(context, url(pg)) }.onSuccess { r ->
                val list = (r.optJSONArray("groups") ?: r.optJSONArray("items")).objects()
                rows = if (reset) list else rows + list
                total = r.optInt("total"); totals = r.optJSONObject("by_type"); page = pg; error = null
            }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(stream, period, groupBy, typeFilter, drill) { load(true) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("sms" to "SMS", "calls" to "Calls", "mpesa" to "M-Pesa", "contacts" to "Contacts").forEach { (k, l) ->
                    Pill(l, stream == k) { stream = k; typeFilter = ""; drill = null; groupBy = "" }
                }
            }
            if (stream != "contacts") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PERIODS.forEachIndexed { i, (l, _) -> Pill(l, period == i) { period = i; drill = null } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "List", "name" to "By person", "date" to "By day", "type" to "By type").forEach { (k, l) ->
                    Pill(l, groupBy == k && drill == null) { groupBy = k; drill = null }
                }
                if (types.size > 1) types.forEach { (k, l) -> Pill(l, typeFilter == k) { typeFilter = k } }
            }
            OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search name, number or text") },
                trailingIcon = { TextButton(onClick = { load(true) }) { Text("Go") } })
            drill?.let { (_, g) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.optString("label"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { drill = null }) { Text("Back") }
                }
            }
            val summary = totals?.let { t -> t.keys().asSequence().joinToString(" · ") { k ->
                val o = t.getJSONObject(k)
                "${types.firstOrNull { it.first == k }?.second ?: k} ${o.optInt("count")}" +
                    if (stream == "mpesa" && o.optDouble("amount") > 0) " (KES ${"%,.0f".format(o.optDouble("amount"))})" else ""
            } }
            Text("$total ${if (groupBy.isNotBlank() && drill == null) "groups" else "records"}${summary?.let { " · $it" } ?: ""}",
                color = AjiriwaColors.TextMuted, fontSize = 12.sp)
            error?.let { Text(it, color = AjiriwaColors.Danger, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows) { r ->
                if (groupBy.isNotBlank() && drill == null) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface)
                        .clickable { drill = groupBy to r }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.optString("label"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(buildString {
                                append("${r.optInt("count")} · last ${localTime(r.optString("last_at"))}")
                                if (stream == "sms") append(" · ${r.optInt("in")} in / ${r.optInt("out")} out")
                            }, color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                        }
                        if (stream == "mpesa") Text("KES ${"%,.0f".format(r.optDouble("amount"))}", color = AjiriwaColors.Success, fontWeight = FontWeight.Bold)
                    }
                } else {
                    DataRow(stream, r)
                }
            }
            if (rows.size < total) item {
                TextButton(onClick = { load(false) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Loading…" else "Load more") }
            }
        }
    }
}

@Composable
private fun DataRow(stream: String, r: JSONObject) {
    val type = r.optString("type")
    val (tag, color) = when (type) {
        "in", "INCOMING", "RECEIVED" -> "IN" to AjiriwaColors.Success
        "out", "OUTGOING", "SENT", "PAID" -> "OUT" to AjiriwaColors.Secondary
        "MISSED", "REJECTED" -> type to AjiriwaColors.Danger
        else -> type.uppercase() to AjiriwaColors.TextMuted
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tag, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
            Text(r.optString("name"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(localTime(r.optString("at")), color = AjiriwaColors.TextMuted, fontSize = 12.sp)
        }
        val phone = r.optString("phone")
        if (phone.isNotBlank() && phone != r.optString("name")) Text(phone, color = AjiriwaColors.TextMuted, fontSize = 12.sp)
        when (stream) {
            "calls" -> Text("${r.optInt("amount") / 60} min ${r.optInt("amount") % 60} s", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
            "mpesa" -> Text("KES ${"%,.2f".format(r.optDouble("amount", 0.0))} · ${r.optString("status")}", color = AjiriwaColors.Success, fontSize = 13.sp)
            "contacts" -> {}
            else -> Text(r.optString("body"), color = AjiriwaColors.TextSecondary, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (stream == "mpesa") Text(r.optString("body"), color = AjiriwaColors.TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ── Movement ─────────────────────────────────────────────────────────────────

private val MOVE_PERIODS = listOf("Today", "Yesterday", "This week", "This month", "This year")

private fun movePeriod(i: Int): Pair<String, String> {
    val c = Calendar.getInstance()
    val today = dayFmt.format(c.time)
    return when (i) {
        1 -> daysAgo(1) to daysAgo(1)
        2 -> { c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek); dayFmt.format(c.time) to today }
        3 -> { c.set(Calendar.DAY_OF_MONTH, 1); dayFmt.format(c.time) to today }
        4 -> { c.set(Calendar.DAY_OF_YEAR, 1); dayFmt.format(c.time) to today }
        else -> today to today
    }
}

@Composable
private fun Movement(ctx: ModuleContext) {
    val context = LocalContext.current
    var period by remember { mutableIntStateOf(0) }
    var bucket by remember { mutableStateOf("") }
    var agents by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var agent by remember { mutableStateOf<String?>(null) }
    var rep by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val (from, to) = movePeriod(period)

    LaunchedEffect(period) {
        if (ctx.isAdmin) runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/movement/agents?from=$from&to=$to") }
            .onSuccess { agents = it.optJSONArray("items").objects() }
    }
    LaunchedEffect(period, bucket, agent) {
        val who = agent?.let { "&admin_id=$it" } ?: "&mine=1"
        val b = if (bucket.isNotBlank()) "&bucket=$bucket" else ""
        runCatching { api(context, "/api/v1/mobile/${ctx.partnerId}/movement?from=$from&to=$to$who$b") }
            .onSuccess { rep = it; error = null }.onFailure { error = it.message }
    }

    fun openMap(lat: Double, lng: Double) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MOVE_PERIODS.forEachIndexed { i, l -> Pill(l, period == i) { period = i } }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "Auto", "minute" to "Per minute", "hour" to "Per hour", "day" to "Per day", "week" to "Per week", "month" to "Per month")
                    .forEach { (k, l) -> Pill(l, bucket == k) { bucket = k } }
            }
            if (ctx.isAdmin && agents.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Me", agent == null) { agent = null }
                    agents.forEach { a -> Pill("${a.optString("name")} · ${"%.1f".format(a.optDouble("km"))} km", agent == a.optString("admin_id")) { agent = a.optString("admin_id") } }
                }
            }
        }
        error?.let { item { Text(it, color = AjiriwaColors.Danger) } }
        rep?.let { r ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Distance" to "${"%.2f".format(r.optDouble("total_km"))} km",
                        "Moving" to "${r.optInt("moving_minutes") / 60} h ${r.optInt("moving_minutes") % 60} m",
                        "Avg speed" to "${"%.1f".format(r.optDouble("avg_speed_kmh"))} km/h",
                    ).forEach { (l, v) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(AjiriwaColors.Surface).padding(10.dp)) {
                            Text(l, color = AjiriwaColors.TextMuted, fontSize = 11.sp)
                            Text(v, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
            val buckets = r.optJSONArray("buckets").objects()
            val maxKm = buckets.maxOfOrNull { it.optDouble("km") }?.takeIf { it > 0 } ?: 1.0
            val pattern = when (r.optString("bucket")) {
                "minute" -> "HH:mm"; "hour" -> "EEE HH:00"; "day" -> "EEE d MMM"; "week" -> "'Week of' d MMM"; "month" -> "MMM yyyy"; else -> "yyyy"
            }
            if (buckets.isEmpty()) item { Text("No movement recorded for this period.", color = AjiriwaColors.TextMuted) }
            items(buckets) { b ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(localTime(b.optString("start"), pattern), color = AjiriwaColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text("${"%.2f".format(b.optDouble("km"))} km", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AjiriwaColors.SurfaceHigh)) {
                        Box(Modifier.fillMaxWidth((b.optDouble("km") / maxKm).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(AjiriwaColors.PrimaryAccent))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${b.optInt("moving_minutes")} min moving · ${b.optInt("points")} points", color = AjiriwaColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        b.optJSONObject("main_place")?.let { p ->
                            TextButton(onClick = { openMap(p.optDouble("lat"), p.optDouble("lng")) }) { Text("Where", fontSize = 12.sp) }
                        }
                    }
                }
            }
            val stops = r.optJSONArray("stops").objects()
            if (stops.isNotEmpty()) {
                item { Text("Stops (5 min or more)", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold) }
                items(stops) { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface)
                        .clickable { openMap(s.optDouble("lat"), s.optDouble("lng")) }.padding(10.dp)) {
                        Text("${localTime(s.optString("arrived"), "HH:mm")} – ${localTime(s.optString("left"), "HH:mm")}", color = AjiriwaColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text("${s.optInt("minutes")} min · map", color = AjiriwaColors.Secondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
