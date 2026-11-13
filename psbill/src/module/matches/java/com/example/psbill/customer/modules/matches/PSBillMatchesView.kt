package com.example.psbill.customer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Loser-pays matches: every running match with its score and what each player owes,
 * the "who won this game" buttons, and a New match form. (Before this the tab only
 * showed a toast and never reached the server.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PSBillMatchesView(ctx: PsbillTabContext) {
    val context = LocalContext.current
    val api = remember(ctx.token) { PsApi(context, ctx.client, ctx.serverHost, ctx.token, ctx.onTokenExpired) }
    val matches = remember { mutableStateMapOf<String, JSONObject>() }   // device id -> match
    var loading by remember { mutableStateOf(false) }
    var busyDevice by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }

    fun reload() {
        val playing = ctx.nodes.filter { screenStatus(it).first in setOf("PLAYING", "OVERTIME") }
        matches.keys.retainAll(playing.map { it.optString("id") }.toSet())
        if (playing.isEmpty()) { loading = false; return }
        loading = true
        var left = playing.size
        playing.forEach { node ->
            val id = node.optString("id")
            LoserPayActions.active(api, id) { m ->
                if (m != null) matches[id] = m else matches.remove(id)
                left -= 1
                if (left <= 0) loading = false
            }
        }
    }
    LaunchedEffect(ctx.nodes) { reload() }

    val running = ctx.nodes.mapNotNull { n -> matches[n.optString("id")]?.let { n to it } }
    val freeScreens = ctx.nodes.filter { screenStatus(it).first == "FREE" }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            PsPageHeader("Loser pays", "Two players, the loser of each game pays for it") {
                FilledTonalButton(onClick = { showNew = true }, enabled = freeScreens.isNotEmpty()) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New match")
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (running.isEmpty() && !loading) {
            item {
                PsEmptyState(
                    icon = Icons.Filled.SportsEsports,
                    title = "No match running",
                    message = if (freeScreens.isEmpty()) "Every screen is busy. Start a match when one is free."
                              else "Start one here, or choose Loser pays when you start a session on a screen.",
                    actionLabel = if (freeScreens.isNotEmpty()) "New match" else "Refresh",
                    onAction = if (freeScreens.isNotEmpty()) ({ showNew = true }) else ({ ctx.onRefresh() }),
                )
            }
        }
        items(running, key = { it.first.optString("id") }) { (node, match) ->
            val id = node.optString("id")
            LoserPayMatchCard(
                match = match,
                screenName = nodeName(node),
                busy = busyDevice == id,
                onRound = { winner ->
                    busyDevice = id
                    LoserPayActions.round(api, match, winner) {
                        LoserPayActions.active(api, id) { m -> if (m != null) matches[id] = m else matches.remove(id); busyDevice = null }
                    }
                },
                onEnd = {
                    busyDevice = id
                    LoserPayActions.end(api, match) { ok ->
                        if (!ok) { busyDevice = null; return@end }
                        // Ending the match does not lock the screen on its own.
                        api.post("/api/v1/kiosk/devices/$id/stop", JSONObject()) { code, body ->
                            busyDevice = null
                            if (code !in 200..299) api.toast(api.errorText(code, body, "Match ended, but the screen did not lock"))
                            matches.remove(id)
                            ctx.onRefresh()
                        }
                    }
                },
            )
        }
    }

    if (showNew) {
        var screenId by remember { mutableStateOf(freeScreens.firstOrNull()?.optString("id").orEmpty()) }
        val lpGames = ctx.games.filter { it.optBoolean("loser_pay_available") }.ifEmpty { ctx.games }
            .map { it.optString("game_name") }.filter { it.isNotBlank() && it != "null" }.distinct()
        var game by remember { mutableStateOf(lpGames.firstOrNull().orEmpty()) }
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        var screenOpen by remember { mutableStateOf(false) }
        var gameOpen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New loser-pays match") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExposedDropdownMenuBox(expanded = screenOpen, onExpandedChange = { screenOpen = it }) {
                        OutlinedTextField(
                            value = freeScreens.firstOrNull { it.optString("id") == screenId }?.let { nodeName(it) }.orEmpty(),
                            onValueChange = {}, readOnly = true, label = { Text("Screen") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = screenOpen) }
                        )
                        ExposedDropdownMenu(expanded = screenOpen, onDismissRequest = { screenOpen = false }) {
                            freeScreens.forEach { n ->
                                DropdownMenuItem(text = { Text(nodeName(n)) }, onClick = { screenId = n.optString("id"); screenOpen = false })
                            }
                        }
                    }
                    ExposedDropdownMenuBox(expanded = gameOpen, onExpandedChange = { gameOpen = it }) {
                        OutlinedTextField(
                            value = game, onValueChange = {}, readOnly = true, label = { Text("Game") },
                            placeholder = { Text("Add games on the dashboard") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gameOpen) }
                        )
                        ExposedDropdownMenu(expanded = gameOpen, onDismissRequest = { gameOpen = false }) {
                            lpGames.forEach { g -> DropdownMenuItem(text = { Text(g) }, onClick = { game = g; gameOpen = false }) }
                        }
                    }
                    LoserPayPlayersFields(a, { a = it }, b, { b = it })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNew = false
                        busyDevice = screenId
                        LoserPayActions.start(api, screenId, game, a, b) { ok ->
                            busyDevice = null
                            if (ok) ctx.onRefresh()
                        }
                    },
                    enabled = screenId.isNotBlank(),
                ) { Text("Start match") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }
}
