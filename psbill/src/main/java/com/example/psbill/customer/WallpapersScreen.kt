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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * TV lock-screen wallpapers (owner): one picture per screen, or the same picture on every screen.
 * The picture is made 1920 px wide on the phone first, so uploads stay small on lounge internet.
 */
@Composable
fun WallpapersScreen(nodes: List<JSONObject>, onBack: () -> Unit, onChanged: () -> Unit) {
    val api = LocalPsApi.current ?: return
    val context = LocalContext.current
    var target by remember { mutableStateOf<String?>(null) }      // device id, or "*" for all
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val screens = nodes.filter { it.optString("id").isNotBlank() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) { target = null; return@rememberLauncherForActivityResult }
        pending = PsImages.shrink(context, uri, 1920, 88)
        if (pending == null) { api.toast("Could not read that picture"); target = null }
    }

    fun upload(ids: List<String>, bytes: ByteArray) {
        var left = ids.size
        var failed = 0
        busy = if (ids.size > 1) "*" else ids.first()
        ids.forEach { id ->
            api.multipart("/api/v1/kiosk/devices/$id/wallpaper", "POST", emptyMap(),
                FilePart("wallpaper", "wallpaper.jpg", "image/jpeg", bytes)) { code, _ ->
                if (code !in 200..299) failed++
                left--
                if (left == 0) {
                    busy = null
                    api.toast(if (failed == 0) "Wallpaper set - the TVs change in a moment" else "$failed screen(s) did not take it. Try again.")
                    onChanged()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PsSubHeader("TV wallpapers", "The picture behind the lock screen on each TV", onBack)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PSBillThemeColors.Surface).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Collections, contentDescription = null, tint = PSBillThemeColors.PrimaryAccent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Same picture on every TV", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Your logo, a promo or this week's offer. Landscape pictures (16:9) fit best.",
                    color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            }
            Button(onClick = { target = "*"; pick.launch("image/*") }, enabled = busy == null && screens.isNotEmpty()) {
                if (busy == "*") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Pick picture")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (screens.isEmpty()) {
            PsEmptyState(Icons.Filled.Tv, "No screens yet", "Link a TV first (Sessions > Link a TV), then set its wallpaper here.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(screens, key = { it.optString("id") }) { n ->
                    val id = n.optString("id")
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(PSBillThemeColors.Surface)
                        .clickable(enabled = busy == null) { target = id; pick.launch("image/*") }) {
                        Box {
                            RemoteImage(n.optString("lock_bg_url").ifBlank { null }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            if (busy == id || busy == "*") Box(Modifier.matchParentSize().background(PSBillThemeColors.Canvas.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(nodeName(n), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.Edit, contentDescription = "Change wallpaper", tint = PSBillThemeColors.TextSecondary,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    val bytes = pending
    val t = target
    if (bytes != null && t != null) {
        val count = if (t == "*") screens.size else 1
        PsFormSheet(
            title = if (t == "*") "Set on all $count screens?" else "Set on ${screens.firstOrNull { it.optString("id") == t }?.let { nodeName(it) } ?: "this screen"}?",
            onDismiss = { pending = null; target = null },
            confirmText = "Set wallpaper",
            danger = false,
            onConfirm = {
                pending = null; target = null
                upload(if (t == "*") screens.map { it.optString("id") } else listOf(t), bytes)
            },
        ) {
            LocalBitmapImage(bytes, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Text("This is how it is cropped on a TV.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}
