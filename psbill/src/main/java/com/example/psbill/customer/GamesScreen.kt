package com.example.psbill.customer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val CONSOLES = listOf("PS5", "PS4", "XBOX", "PC", "NINTENDO", "VR", "OTHER")
private val RATINGS = listOf("PEGI 3", "PEGI 7", "PEGI 12", "PEGI 16", "PEGI 18")

/** Games and covers (owner): list, add, edit, delete. Prices per game override the screen's price. */
@Composable
fun GamesScreen(games: List<JSONObject>, onBack: () -> Unit, onChanged: () -> Unit) {
    val api = LocalPsApi.current ?: return
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    val shown = games.filter { query.isBlank() || it.optString("game_name").contains(query, ignoreCase = true) }
        .sortedBy { it.optString("game_name").lowercase() }

    Column(Modifier.fillMaxSize()) {
        PsSubHeader("Games and covers", "${games.size} game${if (games.size == 1) "" else "s"} - shown on the TVs and the portal", onBack) {
            Button(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Add game")
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search games") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            PsEmptyState(Icons.Filled.SportsEsports, if (games.isEmpty()) "No games yet" else "No game matches",
                if (games.isEmpty()) "Add the games you have. Each can have its own price per minute and a cover photo." else "Try another name")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(shown, key = { it.optString("id") }) { g ->
                    GameCard(g, onEdit = { editing = g }, onDelete = { deleting = g })
                }
            }
        }
    }

    if (adding || editing != null) {
        GameForm(editing, onDismiss = { adding = false; editing = null }, onSaved = {
            adding = false; editing = null; onChanged()
        })
    }
    deleting?.let { g ->
        PsConfirm(
            "Delete ${g.optString("game_name")}?",
            "It disappears from the TVs and the portal. Past sessions keep their records.",
            "Delete",
            onConfirm = {
                deleting = null
                api.delete("/api/v1/kiosk/games/${g.optString("id")}") { code, body ->
                    if (code in 200..299) { api.toast("Game deleted"); onChanged() }
                    else api.toast(api.errorText(code, body, "Could not delete the game"))
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun GameCard(g: JSONObject, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(PSBillThemeColors.Surface).clickable(onClick = onEdit)) {
        RemoteImage(g.optString("cover_image_url").ifBlank { null }, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        Column(Modifier.padding(10.dp)) {
            Text(g.optString("game_name"), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${g.optString("console_type")} - KES ${g.optDouble("base_rate_per_minute", 0.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}/min",
                color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Min KES ${g.optDouble("minimum_charge", 0.0).toInt()}", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete game", tint = PsTone.Bad, modifier = Modifier.size(18.dp))
                }
            }
            if (g.optBoolean("loser_pay_available")) PsPill("Loser pays", PsTone.Good)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameForm(game: JSONObject?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val api = LocalPsApi.current ?: return
    val context = LocalContext.current
    var name by remember { mutableStateOf(game?.optString("game_name").orEmpty()) }
    var console by remember { mutableStateOf(game?.optString("console_type")?.ifBlank { null } ?: "PS5") }
    var rate by remember { mutableStateOf(game?.optDouble("base_rate_per_minute")?.takeIf { !it.isNaN() }?.toString() ?: "3") }
    var minimum by remember { mutableStateOf(game?.optDouble("minimum_charge")?.takeIf { !it.isNaN() }?.toInt()?.toString() ?: "40") }
    var rating by remember { mutableStateOf(game?.optString("rating")?.ifBlank { null } ?: "PEGI 3") }
    var description by remember { mutableStateOf(game?.optString("description").orEmpty()) }
    var loserPay by remember { mutableStateOf(game?.optBoolean("loser_pay_available") ?: true) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) cover = PsImages.shrink(context, uri, 900) ?: run { api.toast("Could not read that picture"); null }
    }
    val rateOk = rate.toDoubleOrNull()?.let { it >= 0 } == true
    val minOk = minimum.toDoubleOrNull()?.let { it >= 0 } == true

    PsFormSheet(
        title = if (game == null) "Add a game" else "Edit ${game.optString("game_name")}",
        onDismiss = onDismiss,
        confirmText = if (game == null) "Add game" else "Save",
        busy = busy,
        confirmEnabled = name.isNotBlank() && rateOk && minOk,
        onConfirm = {
            busy = true; error = null
            val fields = mapOf(
                "game_name" to name.trim(), "console_type" to console, "base_rate_per_minute" to rate,
                "minimum_charge" to minimum, "rating" to rating, "description" to description.trim(),
                "loser_pay_available" to loserPay.toString(),
            )
            val file = cover?.let { FilePart("cover_image", "cover.jpg", "image/jpeg", it) }
            val path = if (game == null) "/api/v1/kiosk/games" else "/api/v1/kiosk/games/${game.optString("id")}"
            api.multipart(path, if (game == null) "POST" else "PUT", fields, file) { code, body ->
                busy = false
                if (code in 200..299) { api.toast(if (game == null) "Game added - the TVs update in a moment" else "Saved"); onSaved() }
                else error = api.errorText(code, body, "Could not save the game")
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(96.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).clickable { pick.launch("image/*") }) {
                if (cover != null) LocalBitmapImage(cover, Modifier.fillMaxSize())
                else RemoteImage(game?.optString("cover_image_url")?.ifBlank { null }, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text("Cover photo", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("A portrait picture of the game box looks best.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                TextButton(onClick = { pick.launch("image/*") }) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (cover != null || game?.optString("cover_image_url").orEmpty().isNotBlank()) "Change" else "Pick a photo")
                }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Game name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = name.isBlank() && error != null)
        DropdownField("Console", console, CONSOLES) { console = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(rate, { rate = it }, label = { Text("KES per minute") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = !rateOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum KES") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = !minOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        DropdownField("Age rating", rating, RATINGS) { rating = it }
        OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, minLines = 2,
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Loser pays", color = PSBillThemeColors.TextPrimary)
                Text("Players can play matches where the loser pays", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            }
            Switch(loserPay, { loserPay = it })
        }
        error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(label: String, value: String, options: List<String>, display: (String) -> String = { it }, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = display(value), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}
