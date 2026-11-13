package com.example.psbill.customer

import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Small JSON helper for the agent app: runs the call, comes back on the UI thread with
 * (http code, body). code 0 = no connection. 401/403 calls onAuthLost and nothing else.
 */
class PsApi(
    private val context: Context,
    private val client: OkHttpClient,
    private val serverHost: String,
    private val token: String,
    private val onAuthLost: () -> Unit,
) {
    fun get(path: String, done: (Int, String) -> Unit) = run(req(path).get().build(), done)

    fun post(path: String, body: JSONObject, done: (Int, String) -> Unit) =
        run(req(path).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build(), done)

    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url("$serverHost$path")
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    private fun ui(block: () -> Unit) {
        (context as? ComponentActivity)?.runOnUiThread(block) ?: block()
    }

    private fun run(request: Request, done: (Int, String) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = ui { done(0, "") }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                val code = response.code
                ui {
                    if (code == 401 || code == 403) onAuthLost() else done(code, body)
                }
            }
        })
    }

    /** The server's error text, else a plain fallback. */
    fun errorText(code: Int, body: String, fallback: String): String = when (code) {
        0 -> "No connection - nothing was changed. Try again."
        else -> runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "$fallback (error $code)"
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

fun parseObjects(body: String): List<JSONObject> = runCatching {
    val arr = if (body.trim().startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("tickets") ?: JSONArray()
    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}.getOrDefault(emptyList())

private fun JSONObject.text(key: String): String =
    optString(key).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

/**
 * A running loser-pay match: who is playing, rounds won, what each one owes, and the
 * buttons the attendant presses after every game.
 */
@Composable
fun LoserPayMatchCard(
    match: JSONObject,
    screenName: String?,
    busy: Boolean,
    onRound: (winner: String?) -> Unit,   // null = draw
    onEnd: () -> Unit,
) {
    val a = match.text("player_a").ifBlank { "Player A" }
    val b = match.text("player_b").ifBlank { "Player B" }
    val rounds = match.optJSONArray("rounds") ?: JSONArray()
    var winsA = 0; var winsB = 0; var draws = 0; var finished = 0
    for (i in 0 until rounds.length()) {
        val r = rounds.optJSONObject(i) ?: continue
        if (r.text("ended_at").isBlank()) continue
        finished++
        when {
            r.optBoolean("is_draw") -> draws++
            r.text("winner") == a -> winsA++
            r.text("winner") == b -> winsB++
        }
    }
    val owesA = match.optDouble("total_amount_player_a", 0.0)
    val owesB = match.optDouble("total_amount_player_b", 0.0)
    var confirmEnd by remember { mutableStateOf(false) }

    PsSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LOSER PAYS", color = PsTone.Warn, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Text(
                    listOf(screenName.orEmpty(), match.text("game_name")).filter { it.isNotBlank() }.joinToString("  -  ")
                        .ifBlank { "Match" },
                    color = PSBillThemeColors.TextSecondary, fontSize = 13.sp
                )
            }
            PsPill("GAME ${finished + 1}", PsTone.Busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerScore(a, winsA, owesA, Modifier.weight(1f))
            PlayerScore(b, winsB, owesB, Modifier.weight(1f))
        }
        if (draws > 0) Text("$draws draw${if (draws > 1) "s" else ""} (no charge)", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)

        Text("Who won this game?", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRound(a) }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(a, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { onRound(b) }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(b, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onRound(null) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Draw") }
            OutlinedButton(
                onClick = { confirmEnd = true }, enabled = !busy, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PsTone.Bad)
            ) { Text("End match") }
        }
        Text(
            "The loser of each game pays for that game's time. A draw costs nobody.",
            color = PSBillThemeColors.TextSecondary, fontSize = 12.sp
        )
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the match?") },
            text = {
                Text("$a pays ${kes(owesA)}, $b pays ${kes(owesB)}. The game in progress is not charged. The screen locks.")
            },
            confirmButton = { TextButton(onClick = { confirmEnd = false; onEnd() }) { Text("End match", color = PsTone.Bad) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep playing") } },
        )
    }
}

@Composable
private fun PlayerScore(name: String, wins: Int, owes: Double, modifier: Modifier) {
    Column(
        modifier
            .then(Modifier)
            .padding(0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PsStat(
            label = name,
            value = "$wins won",
            tone = PSBillThemeColors.TextPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (owes > 0) "owes ${kes(owes)}" else "owes nothing",
            color = if (owes > 0) PsTone.Warn else PSBillThemeColors.TextSecondary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
    }
}

/** Player names for a new loser-pay match (the game is chosen by the caller). */
@Composable
fun LoserPayPlayersFields(playerA: String, onA: (String) -> Unit, playerB: String, onB: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(playerA, onA, label = { Text("Player 1 name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(playerB, onB, label = { Text("Player 2 name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(
            "Pay after only. Press who won after every game; the loser pays for it.",
            color = PSBillThemeColors.TextSecondary, fontSize = 12.sp
        )
    }
}

/** Actions shared by every place that shows a match. */
object LoserPayActions {
    fun start(api: PsApi, deviceId: String, game: String, a: String, b: String, done: (Boolean) -> Unit) {
        val body = JSONObject().apply {
            put("device_id", deviceId); put("current_game", game); put("billing_mode", "POSTPAID")
            put("player_a", a.trim().ifBlank { "Player 1" }); put("player_b", b.trim().ifBlank { "Player 2" })
        }
        api.post("/api/v1/kiosk/loser-pay/start", body) { code, text ->
            if (code in 200..299) { api.toast("Match started"); done(true) }
            else { api.toast(api.errorText(code, text, "Could not start the match")); done(false) }
        }
    }

    fun round(api: PsApi, match: JSONObject, winner: String?, done: (Boolean) -> Unit) {
        val a = match.text("player_a"); val b = match.text("player_b")
        val body = JSONObject().apply {
            if (winner == null) put("is_draw", true)
            else { put("winner", winner); put("loser", if (winner == a) b else a) }
        }
        api.post("/api/v1/kiosk/loser-pay/round/${match.text("token")}", body) { code, text ->
            if (code in 200..299) {
                api.toast(if (winner == null) "Draw recorded" else "$winner won - ${if (winner == a) b else a} pays this game")
                done(true)
            } else { api.toast(api.errorText(code, text, "Could not save the result")); done(false) }
        }
    }

    fun end(api: PsApi, match: JSONObject, done: (Boolean) -> Unit) {
        api.post("/api/v1/kiosk/loser-pay/end/${match.text("token")}", JSONObject()) { code, text ->
            if (code in 200..299) { api.toast("Match ended"); done(true) }
            else { api.toast(api.errorText(code, text, "Could not end the match")); done(false) }
        }
    }

    /** The running match on a screen, or null. */
    fun active(api: PsApi, deviceId: String, done: (JSONObject?) -> Unit) {
        api.get("/api/v1/kiosk/loser-pay/device/$deviceId/active") { code, text ->
            val obj = if (code in 200..299) runCatching { JSONObject(text) }.getOrNull() else null
            done(obj?.takeIf { it.optBoolean("active") })
        }
    }
}
