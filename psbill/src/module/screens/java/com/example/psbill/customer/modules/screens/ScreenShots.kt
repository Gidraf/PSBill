package com.example.psbill.customer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/*
 * Screen detail -> Shots: the TV's gameplay screenshots (one a minute during a session,
 * GET /api/v1/kiosk/devices/<id>/screenshots?wrap=1), so the owner can check the game on the
 * screen is the one billed. "This session" = the running session, "Last 6 h" = everything recent.
 */
private val shotHttp by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }
private val shotCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
fun ScreenShotsSection(api: PsApi, node: JSONObject) {
    val deviceId = node.optString("id")
    var scope by remember(deviceId) { mutableStateOf("current") }
    var rows by remember(deviceId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var info by remember(deviceId) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(deviceId) { mutableStateOf(false) }
    var error by remember(deviceId) { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<JSONObject?>(null) }

    fun load() {
        loading = true
        api.get("/api/v1/kiosk/devices/$deviceId/screenshots?wrap=1&scope=$scope&limit=60") { code, body ->
            loading = false
            if (code !in 200..299) { error = api.errorText(code, body, "Could not load the screenshots"); return@get }
            error = null
            val o = JSONObject(body)
            info = o
            val arr = o.optJSONArray("screenshots")
            rows = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
        }
    }
    LaunchedEffect(deviceId, scope) {
        while (true) { load(); delay(60_000) }   // a new shot arrives about once a minute
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = scope == "current", onClick = { scope = "current" }, label = { Text("This session") })
            FilterChip(selected = scope == "recent", onClick = { scope = "recent" }, label = { Text("Last 6 h") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { load() }, enabled = !loading) { Text("Refresh") }
        }
        val billed = info?.optString("current_game")?.takeIf { it.isNotBlank() && it != "null" }
        Text(
            "The TV takes a picture of the game once a minute while a session runs." +
                (billed?.let { " Billed game now: $it." } ?: ""),
            color = PSBillThemeColors.TextSecondary, fontSize = 13.sp
        )
        error?.let { Text(it, color = PSBillThemeColors.Amber, fontSize = 12.sp) }
        if (loading && rows.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (info?.optBoolean("all_dark") == true) {
            Text(
                "Every picture is black: this TV hides the HDMI picture from apps (copy protection). " +
                    "The times still show the session ran; check the game on the TV itself.",
                color = PSBillThemeColors.Amber, fontSize = 12.sp
            )
        }
        if (!loading && rows.isEmpty() && error == null) {
            Text(
                if (scope == "current") "No pictures for this session yet. The first comes 20 s after the start. " +
                    "If none ever arrive, press \"Start now\" when the TV asks to capture the screen (it asks on the idle lock screen)."
                else "No pictures in the last 6 hours.",
                color = PSBillThemeColors.TextSecondary, fontSize = 13.sp
            )
        }
        rows.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { r -> ShotTile(r, Modifier.weight(1f)) { open = r } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    open?.let { r ->
        Dialog(onDismissRequest = { open = null }) {
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F172A)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShotImage(r.optString("image_url"), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Text(shotCaption(r), color = Color.White, fontSize = 13.sp)
                TextButton(onClick = { open = null }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ShotTile(r: JSONObject, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShotImage(r.optString("image_url"), Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        Text(shotCaption(r), color = PSBillThemeColors.TextSecondary, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun ShotImage(url: String, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(shotCache.get(url), url) {
        if (value != null || url.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                shotHttp.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    if (!res.isSuccessful) null
                    else res.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()?.also { shotCache.put(url, it) }
        }
    }
    Box(modifier.background(Color(0xFF1E293B)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = "Screenshot", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

private fun shotCaption(r: JSONObject): String {
    val time = runCatching {
        val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(p.parse(r.optString("created_at").take(19))!!)
    }.getOrDefault("")
    val game = r.optString("game_name").takeIf { it.isNotBlank() && it != "null" } ?: "game not set"
    return listOf(time, game, if (r.optBoolean("is_dark")) "black frame" else "").filter { it.isNotBlank() }.joinToString("  -  ")
}
