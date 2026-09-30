package com.example.psbill.modules.properties

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import org.json.JSONObject

private val SCREEN_SPECS = setOf("Water source", "Days water actually flows", "Electricity meter", "Backup power", "Parking", "Guards", "Furnishing", "Floor", "Home fibre available", "Hot water")

private fun qrBitmap(text: String, size: Int = 360): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}

/** One slide, identical in layout to the web shop screen (/properties/display/<partner>). */
@Composable
fun HouseSlide(l: JSONObject, business: String, modifier: Modifier = Modifier) {
    val photos = l.optJSONArray("photos").objects().ifEmpty { listOfNotNull(l.optJSONObject("cover_photo")) }
    var i by remember(l.optString("id")) { mutableIntStateOf(0) }
    LaunchedEffect(l.optString("id"), photos.size) {
        while (photos.size > 1) { delay(3500); i = (i + 1) % photos.size }
    }
    val qr = remember(l.optString("public_url")) { runCatching { qrBitmap(l.optString("public_url")) }.getOrNull() }
    val currency = l.optString("currency", "KES")
    val specs = l.optJSONArray("readable_specs").objects().flatMap { it.optJSONArray("items").objects() }
        .filter { it.optString("label") in SCREEN_SPECS }.take(8)

    BoxWithConstraints(modifier.background(Color(0xFF05070D))) {
        val u = maxHeight / 100 // 1% of screen height, so it scales from phone to TV
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.62f).fillMaxHeight().background(Color(0xFF0F172A))) {
                Crossfade(targetState = i, label = "photo") { idx ->
                    photos.getOrNull(idx)?.let { AsyncImage(model = it.optString("url"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Text(
                    l.optString("availability_label"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (u.value * 2.2f).sp,
                    modifier = Modifier.padding(u * 3).clip(RoundedCornerShape(50)).background(occupancyColor(l.optString("occupancy_status"))).padding(horizontal = u * 1.6f, vertical = u * 0.8f),
                )
            }
            Column(Modifier.weight(0.38f).fillMaxHeight().padding(u * 4), verticalArrangement = Arrangement.spacedBy(u * 1.6f)) {
                Text(business.uppercase(), color = Color(0xFF93C5FD), fontSize = (u.value * 1.9f).sp, letterSpacing = 2.sp)
                Text(l.optString("title"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (u.value * 4.2f).sp, lineHeight = (u.value * 4.6f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(l.str("type_label"), l.str("apartment_name"), l.str("unit_label")?.let { "Unit $it" }).joinToString(" · "), color = Color(0xFFCBD5E1), fontSize = (u.value * 2.2f).sp)
                Text(money(l.dbl("rent_amount"), currency), color = Color(0xFF4ADE80), fontWeight = FontWeight.ExtraBold, fontSize = (u.value * 5.2f).sp)
                Row(horizontalArrangement = Arrangement.spacedBy(u)) {
                    listOf(
                        "${l.opt("bedrooms").takeUnless { it == JSONObject.NULL } ?: "—"}" to "Bedrooms",
                        "${l.opt("bathrooms").takeUnless { it == JSONObject.NULL } ?: "—"}" to "Bathrooms",
                        distance(l.dbl("distance_to_tarmac_m")) to "From tarmac",
                    ).forEach { (v, k) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(u * 1.4f)).background(Color.White.copy(alpha = .06f)).padding(u), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(v, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (u.value * 2.8f).sp)
                            Text(k.uppercase(), color = Color(0xFF94A3B8), fontSize = (u.value * 1.4f).sp)
                        }
                    }
                }
                l.optJSONObject("insights")?.optJSONArray("highlights").strings().take(4).takeIf { it.isNotEmpty() }?.let { hs ->
                    Text(hs.joinToString("   ") { "✓ $it" }, color = Color(0xFF86EFAC), fontSize = (u.value * 1.8f).sp)
                }
                specs.forEach { s -> Text("${s.optString("label")}: ${s.optString("value")}", color = Color(0xFFCBD5E1), fontSize = (u.value * 1.8f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 2)) {
                    qr?.let { Image(it.asImageBitmap(), null, Modifier.size(u * 15).clip(RoundedCornerShape(u)).background(Color.White).padding(u * 0.6f)) }
                    Column {
                        l.dbl("distance_from_shop_m")?.let { Text("${distance(it)} from this shop", color = Color.White, fontSize = (u.value * 2f).sp) }
                        l.optJSONObject("insights")?.optJSONObject("move_in")?.let { Text("Move in: ${money(it.optDouble("total"), currency)}", color = Color.White, fontSize = (u.value * 2f).sp) }
                        if (l.str("contact_name") != null || l.str("contact_phone") != null) {
                            Text("${l.str("contact_name") ?: ""} ${l.str("contact_phone") ?: ""}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (u.value * 2.4f).sp)
                        }
                        Text("Scan for photos, specs & directions", color = Color(0xFF94A3B8), fontSize = (u.value * 1.7f).sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenPreviewDialog(listing: JSONObject, business: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            HouseSlide(listing, business, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close", color = Color.White) }
        }
    }
}

/**
 * Full-screen rotating showcase of approved vacant/reserved houses — put the
 * phone/tablet (or an Android TV box running the app) on the shop counter.
 */
@Composable
fun ShopScreenMode(api: PropertiesApi, business: String, onExit: () -> Unit, seconds: Int = 12) {
    val activity = LocalContext.current as? Activity
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var name by remember { mutableStateOf(business) }
    var index by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { api.displayFeed() }
                .onSuccess { feed -> items = feed.optJSONArray("items").objects(); name = feed.optString("business", business); error = null }
                .onFailure { error = it.message }
            delay(5 * 60 * 1000L) // pick up newly approved houses
        }
    }
    LaunchedEffect(items.size) {
        while (items.size > 1) { delay(seconds * 1000L); index = (index + 1) % items.size }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05070D)).clickable(onClick = onExit)) {
        if (items.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(error ?: "New houses coming soon", color = Color(0xFF94A3B8))
                Text("Tap to exit", color = Color(0xFF475569), fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp))
            }
        } else {
            AnimatedContent(targetState = index.coerceIn(0, items.lastIndex), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "slide") { i ->
                HouseSlide(items[i], name, Modifier.fillMaxSize())
            }
        }
    }
}
