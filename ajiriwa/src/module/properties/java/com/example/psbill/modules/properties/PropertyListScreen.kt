package com.example.psbill.modules.properties

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.psbill.ui.components.EmptyState
import com.example.psbill.ui.components.PillChip
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.delay
import org.json.JSONObject

internal fun approvalColor(status: String): Color = when (status) {
    "APPROVED" -> AjiriwaColors.Success
    "PENDING" -> AjiriwaColors.Warning
    "REJECTED" -> AjiriwaColors.Danger
    else -> AjiriwaColors.TextMuted
}

internal fun approvalLabel(status: String): String = when (status) {
    "APPROVED" -> "Approved"
    "PENDING" -> "Awaiting approval"
    "REJECTED" -> "Changes requested"
    else -> "Draft"
}

internal fun occupancyColor(status: String): Color = when (status) {
    "vacant" -> AjiriwaColors.Success
    "reserved" -> AjiriwaColors.Warning
    "occupied" -> AjiriwaColors.Danger
    else -> AjiriwaColors.TextMuted
}

@Composable
fun PropertyListScreen(
    api: PropertiesApi,
    context: JSONObject?,
    refreshKey: Int,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onShopScreen: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(tab, query, refreshKey) {
        delay(if (query.isBlank()) 0 else 350) // debounce typing
        loading = true
        runCatching { api.list(approval = if (tab == 1) "PENDING" else null, q = query) }
            .onSuccess { data = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    val items = data?.optJSONArray("items").objects()
    val counts = data?.optJSONObject("counts")
    val isChecker = context?.optBoolean("is_checker") == true

    Scaffold(
        containerColor = AjiriwaColors.Canvas,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = AjiriwaColors.PrimaryAccent,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add house") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Properties & Houses", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        if (isChecker) "You are a checker — your houses publish immediately" else "You are a maker — a checker approves your houses",
                        color = AjiriwaColors.TextSecondary, fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onShopScreen) {
                    Icon(Icons.Filled.PlayArrow, null, tint = AjiriwaColors.Primary)
                    Spacer(Modifier.width(4.dp))
                    Text("Shop screen", color = AjiriwaColors.Primary)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apartment, unit, address…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AjiriwaColors.TextPrimary, unfocusedTextColor = AjiriwaColors.TextPrimary),
            )
            TabRow(selectedTabIndex = tab, containerColor = AjiriwaColors.Canvas, contentColor = AjiriwaColors.Primary, modifier = Modifier.padding(top = 8.dp)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Houses ${counts?.optInt("total")?.let { "($it)" } ?: ""}") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Approvals ${counts?.optInt("pending")?.takeIf { it > 0 }?.let { "($it)" } ?: ""}") })
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AjiriwaColors.PrimaryAccent)
            error?.let { Text(it, color = AjiriwaColors.Danger, modifier = Modifier.padding(16.dp)) }

            if (!loading && items.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Home,
                    title = if (tab == 1) "Nothing to approve" else "No houses yet",
                    message = if (tab == 1) "New houses from makers appear here." else "Tap “Add house” to list the first one with photos and specs.",
                )
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.getString("id") }) { l -> ListingCard(l) { onOpen(l.getString("id")) } }
            }
        }
    }
}

@Composable
private fun ListingCard(l: JSONObject, onClick: () -> Unit) {
    val cover = l.optJSONObject("cover_photo")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AjiriwaColors.Surface).clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(AjiriwaColors.SurfaceAlt)) {
            if (cover != null) {
                AsyncImage(model = cover.optString("thumb_url"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Home, null, tint = AjiriwaColors.TextMuted, modifier = Modifier.align(Alignment.Center).size(40.dp))
            }
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillChip(l.optString("availability_label"), occupancyColor(l.optString("occupancy_status")))
                val approval = l.optString("approval_status")
                if (approval != "APPROVED") PillChip(approvalLabel(approval), approvalColor(approval))
            }
        }
        Column(Modifier.padding(14.dp)) {
            Text(l.optString("title"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(l.str("type_label"), l.str("apartment_name"), l.str("unit_label")?.let { "Unit $it" }).joinToString(" · "),
                color = AjiriwaColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(money(l.dbl("rent_amount"), l.optString("currency", "KES")), color = AjiriwaColors.Success, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "${l.opt("bedrooms").takeUnless { it == JSONObject.NULL } ?: "—"} bd · ${distance(l.dbl("distance_to_tarmac_m"))} to tarmac",
                    color = AjiriwaColors.TextMuted, fontSize = 12.sp,
                )
            }
        }
    }
}
