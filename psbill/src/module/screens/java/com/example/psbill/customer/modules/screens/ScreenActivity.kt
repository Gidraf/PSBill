package com.example.psbill.customer

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Screen detail -> Activity: who did what on this screen (server: app/services/screen_activity.py,
 * GET /api/v1/kiosk/devices/<id>/activity), and the owner's remote controls for PSBill on the TV:
 * pause (other apps and TV settings usable), resume, remove (the TV uninstalls PSBill and is unlinked).
 * Attendants see the log; the server refuses them the controls (403 OWNER_ONLY, shown as a message).
 */
@Composable
fun ScreenActivitySection(api: PsApi, node: JSONObject, onChanged: () -> Unit) {
    val context = LocalContext.current
    val deviceId = node.optString("id")
    var rows by remember(deviceId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(deviceId) { mutableStateOf(false) }
    var nextBefore by remember(deviceId) { mutableStateOf<String?>(null) }
    var error by remember(deviceId) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }   // pause | resume | remove | remove/cancel
    var busy by remember { mutableStateOf(false) }
    val paused = node.optBoolean("app_paused", false)
    val removing = node.optString("remove_requested_at").let { it.isNotBlank() && it != "null" }

    fun load(before: String? = null) {
        loading = true
        val q = "/api/v1/kiosk/devices/$deviceId/activity?limit=50" + (before?.let { "&before=" + android.net.Uri.encode(it) } ?: "")
        api.get(q) { code, body ->
            loading = false
            if (code !in 200..299) { error = api.errorText(code, body, "Could not load the activity"); return@get }
            error = null
            val o = JSONObject(body)
            val arr = o.optJSONArray("data")
            val page = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
            rows = if (before == null) page else rows + page
            nextBefore = o.optString("next_before").takeIf { page.size >= 50 && it.isNotBlank() && it != "null" }
        }
    }
    LaunchedEffect(deviceId) { load() }

    fun act(action: String, force: Boolean = false) {
        busy = true
        api.post("/api/v1/kiosk/devices/$deviceId/$action", JSONObject().put("force", force)) { code, body ->
            busy = false
            if (code in 200..299) {
                Toast.makeText(context, when (action) {
                    "pause" -> "PSBill paused on the TV"
                    "resume" -> "PSBill is back on the TV"
                    "remove" -> "The TV will uninstall PSBill"
                    else -> "Removal cancelled"
                }, Toast.LENGTH_SHORT).show()
                onChanged(); load()
            } else {
                Toast.makeText(context, api.errorText(code, body, "Not changed"), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PsSectionLabel("PSBill on this TV")
        Text(
            when {
                removing -> "Removal requested: the TV gives up kiosk mode and asks to uninstall PSBill. " +
                    "State: ${node.optString("remote_state").ifBlank { "waiting for the TV" }}"
                paused -> "Paused: other apps and TV settings can be used. Resume to lock the TV again."
                else -> "Running. Pause it to use other apps or change TV settings, then resume."
            },
            color = PSBillThemeColors.TextSecondary, fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                removing -> OutlinedButton(enabled = !busy, onClick = { confirm = "remove/cancel" }) { Text("Cancel removal") }
                paused -> FilledTonalButton(enabled = !busy, onClick = { act("resume") }) { Text("Resume PSBill") }
                else -> FilledTonalButton(enabled = !busy, onClick = { confirm = "pause" }) { Text("Pause PSBill") }
            }
            if (!removing) OutlinedButton(enabled = !busy, onClick = { confirm = "remove" }) { Text("Remove from TV", color = PsTone.Bad) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PsSectionLabel("Activity", rows.size) }
            TextButton(onClick = { load() }, enabled = !loading) { Text("Refresh") }
        }
        error?.let { Text(it, color = PSBillThemeColors.Amber, fontSize = 12.sp) }
        if (loading && rows.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && rows.isEmpty() && error == null) {
            Text("Nothing logged on this screen yet.", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
        }
        rows.forEach { r ->
            PsListRow(
                title = r.optString("label").ifBlank { r.optString("action") },
                detail = listOf(whoDid(r), detailText(r.optJSONObject("detail"))).filter { it.isNotBlank() }.joinToString("  -  "),
                tone = toneFor(r.optString("action")),
                icon = Icons.Filled.History,
                trailingValue = timeText(r.optString("created_at")),
            )
        }
        nextBefore?.let { b -> TextButton(onClick = { load(b) }, enabled = !loading) { Text("Older") } }
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(when (what) { "pause" -> "Pause PSBill?"; "remove" -> "Remove PSBill from this TV?"; else -> "Cancel the removal?" }) },
            text = {
                Text(when (what) {
                    "pause" -> "The TV stops locking. Anyone at the TV can open other apps and TV settings until you resume."
                    "remove" -> "The TV gives up kiosk mode and shows the uninstall prompt. The screen is unlinked and no longer billed. " +
                        "Link it again after a new install."
                    else -> "PSBill stays on the TV, paused. Resume it to lock the TV again."
                })
            },
            confirmButton = { TextButton(onClick = { confirm = null; act(what) }) { Text("Yes", color = if (what == "remove") PsTone.Bad else PsTone.Busy) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("No") } },
        )
    }
}

private fun whoDid(r: JSONObject): String {
    val name = r.optString("actor_name").takeIf { it.isNotBlank() && it != "null" }
    return when (r.optString("actor_type")) {
        "tv" -> "the TV"
        "system" -> "automatic"
        "portal" -> "captive portal"
        "admin" -> "platform admin"
        "owner" -> name?.let { "$it (owner)" } ?: "owner"
        else -> name ?: "staff"
    }
}

private fun detailText(d: JSONObject?): String {
    if (d == null) return ""
    val parts = mutableListOf<String>()
    d.optString("current_game").takeIf { it.isNotBlank() && it != "null" }?.let { parts += it }
    if (d.has("minutes") && !d.isNull("minutes")) parts += "${d.optInt("minutes")} min"
    if (d.has("amount_kes") && !d.isNull("amount_kes")) parts += "KES ${"%,.0f".format(d.optDouble("amount_kes", 0.0))}"
    if (d.has("to")) parts += "${d.optString("from")} -> ${d.optString("to")}"
    if (d.has("ticket") && !d.isNull("ticket")) parts += "ticket #${d.optString("ticket")}"
    return parts.joinToString(", ")
}

private fun toneFor(action: String) = when {
    action in setOf("start", "topup", "topup_approve", "topup_accept", "resume", "link") -> PsTone.Good
    action in setOf("remove", "unlink", "delete", "topup_decline", "topup_refuse", "tv_removing") -> PsTone.Bad
    action in setOf("pause", "free_play_on", "console_state", "topup_request", "topup_propose") -> PsTone.Warn
    else -> PsTone.Idle
}

private fun timeText(iso: String): String = runCatching {
    val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val d = parse.parse(iso.take(19)) ?: return ""
    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(d)
}.getOrDefault("")
