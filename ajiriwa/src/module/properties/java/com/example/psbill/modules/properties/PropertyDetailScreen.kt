package com.example.psbill.modules.properties

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.psbill.ui.components.PillChip
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

private val ROOMS = listOf("living", "kitchen", "bedroom", "bathroom", "exterior", "compound", "parking", "view", "other")
private val OCCUPANCY = listOf("vacant" to "Vacant", "reserved" to "Reserved", "occupied" to "Occupied", "maintenance" to "Under maintenance")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertyDetailScreen(
    api: PropertiesApi,
    id: String,
    context: JSONObject?,
    onBack: () -> Unit,
    onEdit: (JSONObject) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var l by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showReject by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    fun reload() = scope.launch { runCatching { api.detail(id) }.onSuccess { l = it; error = null }.onFailure { error = it.message } }
    fun act(block: suspend () -> JSONObject, ok: String? = null) = scope.launch {
        busy = true
        runCatching { block() }
            .onSuccess { res -> if (res.has("id")) l = res else reload(); ok?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }
            .onFailure { Toast.makeText(ctx, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
        busy = false
    }
    LaunchedEffect(id) { reload() }

    val listing = l
    Scaffold(
        containerColor = AjiriwaColors.Canvas,
        topBar = {
            TopAppBar(
                title = { Text(listing?.optString("title") ?: "House", maxLines = 1, color = AjiriwaColors.TextPrimary) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = AjiriwaColors.Primary) } },
                actions = {
                    if (listing != null) IconButton(onClick = { onEdit(listing) }) { Icon(Icons.Filled.Edit, "Edit", tint = AjiriwaColors.Primary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AjiriwaColors.Surface),
            )
        },
    ) { pad ->
        if (listing == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = AjiriwaColors.Danger) else CircularProgressIndicator(color = AjiriwaColors.PrimaryAccent)
            }
            return@Scaffold
        }
        val approval = listing.optString("approval_status")
        val approved = approval == "APPROVED"
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AjiriwaColors.PrimaryAccent)

            // Maker–checker banner
            when {
                approval == "PENDING" && listing.optBoolean("can_approve") -> Row(
                    Modifier.fillMaxWidth().background(AjiriwaColors.Warning.copy(alpha = .15f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Added by ${listing.str("created_by_name") ?: "a maker"} — review and approve", color = AjiriwaColors.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showReject = true }) { Text("Reject", color = AjiriwaColors.Danger) }
                    Button(onClick = { act({ api.review(id, true, null) }, "Approved — now live") }, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Success)) { Text("Approve") }
                }
                approval == "PENDING" -> Banner("Waiting for a checker to approve.", AjiriwaColors.Warning)
                approval == "REJECTED" -> Banner("Changes requested: ${listing.str("review_note") ?: ""}", AjiriwaColors.Danger)
                approval == "DRAFT" -> Row(Modifier.fillMaxWidth().background(AjiriwaColors.SurfaceAlt).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Draft — not yet submitted.", color = AjiriwaColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { act({ api.submit(id) }, if (context?.optBoolean("is_checker") == true) "Published" else "Sent to checkers") }) {
                        Text(if (context?.optBoolean("is_checker") == true) "Publish" else "Submit")
                    }
                }
            }

            TabRow(selectedTabIndex = tab, containerColor = AjiriwaColors.Canvas, contentColor = AjiriwaColors.Primary) {
                listOf("Overview", "Directions", "Photos").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> Overview(listing, approved, onShare = { showShare = true }, onPreview = { showPreview = true },
                    onAvailability = { v -> act({ api.update(id, JSONObject().put("occupancy_status", v)) }, "Availability updated") })
                1 -> Directions(api, listing, context)
                2 -> Photos(api, listing, onChanged = { l = it }, onBusy = { busy = it })
            }
        }
    }

    if (showShare && listing != null) ShareDialog(api, listing) { showShare = false }
    if (showReject) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReject = false },
            title = { Text("Request changes") },
            text = { OutlinedTextField(note, { note = it }, label = { Text("What should the maker fix?") }, minLines = 3) },
            confirmButton = { TextButton(enabled = note.isNotBlank(), onClick = { showReject = false; act({ api.review(id, false, note) }, "Sent back to the maker") }) { Text("Send back") } },
            dismissButton = { TextButton(onClick = { showReject = false }) { Text("Cancel") } },
        )
    }
    if (showPreview && listing != null) {
        ScreenPreviewDialog(listing, context?.optString("business_name").orEmpty()) { showPreview = false }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(text, color = AjiriwaColors.TextPrimary, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(color.copy(alpha = .15f)).padding(12.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Overview(l: JSONObject, approved: Boolean, onShare: () -> Unit, onPreview: () -> Unit, onAvailability: (String) -> Unit) {
    val ctx = LocalContext.current
    val photos = l.optJSONArray("photos").objects()
    val insights = l.optJSONObject("insights")
    val currency = l.optString("currency", "KES")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (photos.isNotEmpty()) {
            val pager = rememberPagerState { photos.size }
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                HorizontalPager(state = pager) { i ->
                    AsyncImage(model = photos[i].optString("url"), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    photos.getOrNull(pager.currentPage)?.str("room")?.let { PillChip(it.replaceFirstChar(Char::uppercase), Color.White, showDot = false) }
                    PillChip("${pager.currentPage + 1}/${photos.size}", Color.White, showDot = false)
                }
            }
        }
        Text(
            listOfNotNull(l.str("type_label"), l.str("apartment_name"), l.str("unit_label")?.let { "Unit $it" }, l.optJSONObject("specs")?.str("estate")).joinToString(" · "),
            color = AjiriwaColors.TextSecondary, fontSize = 13.sp,
        )
        Text(money(l.dbl("rent_amount"), currency), color = AjiriwaColors.Success, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("${l.opt("bedrooms").takeUnless { it == JSONObject.NULL } ?: "—"}", "Bedrooms", Modifier.weight(1f))
            Fact("${l.opt("bathrooms").takeUnless { it == JSONObject.NULL } ?: "—"}", "Bathrooms", Modifier.weight(1f))
            Fact(distance(l.dbl("distance_to_tarmac_m")), "From tarmac", Modifier.weight(1f))
            Fact(distance(l.dbl("distance_from_shop_m")), "From shop", Modifier.weight(1f))
        }

        // Actions
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onShare, enabled = approved, label = { Text("Share SMS/email") }, leadingIcon = { Icon(Icons.Filled.Send, null, Modifier.size(16.dp)) })
            AssistChip(onClick = onPreview, label = { Text("Screen preview") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)) })
            l.str("contact_phone")?.let { phone ->
                AssistChip(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }, label = { Text("Call") }, leadingIcon = { Icon(Icons.Filled.Phone, null, Modifier.size(16.dp)) })
                AssistChip(onClick = {
                    val digits = phone.filter { it.isDigit() }.let { if (it.startsWith("0")) "254" + it.drop(1) else it }
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))) }
                }, label = { Text("WhatsApp") })
            }
            if (approved) AssistChip(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${l.optString("title")} — ${l.optString("public_url")}")
                }, "Share link"))
            }, label = { Text("Share link") }, leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(16.dp)) })
        }

        // Availability
        var menu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menu = true }) {
                Text("Availability: ${l.optString("availability_label")}", color = occupancyColor(l.optString("occupancy_status")))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                OCCUPANCY.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onAvailability(v) }) }
            }
        }

        insights?.optJSONArray("highlights").strings().takeIf { it.isNotEmpty() }?.let { hs ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { hs.forEach { PillChip("✓ $it", AjiriwaColors.Success, showDot = false) } }
        }
        insights?.optJSONArray("warnings").objects().takeIf { it.isNotEmpty() }?.let { ws ->
            SectionHeader("Tenant-protection checklist")
            ws.forEach { w ->
                val c = when (w.optString("level")) { "danger" -> AjiriwaColors.Danger; "warning" -> AjiriwaColors.Warning; else -> AjiriwaColors.Secondary }
                Text(w.optString("text"), color = AjiriwaColors.TextPrimary, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = .15f)).padding(10.dp))
            }
        }
        insights?.optJSONObject("move_in")?.let { mi ->
            SectionHeader("Cost to move in")
            listOf("first_rent" to "First rent", "rent_deposit" to "Rent deposit", "water_deposit" to "Water deposit",
                "electricity_deposit" to "Electricity deposit", "service_charge" to "Service charge", "agency_fee" to "Agency fee")
                .filter { mi.optDouble(it.first, 0.0) > 0 }
                .forEach { (k, label) -> SpecRow(label, money(mi.optDouble(k), currency)) }
            SpecRow("Total", money(mi.optDouble("total"), currency), bold = true)
        }
        if (l.str("contact_name") != null || l.str("contact_phone") != null) {
            SectionHeader("Contact ${l.str("contact_role")?.let { "($it)" } ?: ""}")
            Text(listOfNotNull(l.str("contact_name"), l.str("contact_phone"), l.str("contact_email")).joinToString(" · "), color = AjiriwaColors.TextPrimary)
        }
        l.str("description")?.let { Text(it, color = AjiriwaColors.TextSecondary) }
        l.optJSONArray("readable_specs").objects().forEach { sec ->
            SectionHeader(sec.optString("title"))
            sec.optJSONArray("items").objects().forEach { SpecRow(it.optString("label"), it.optString("value")) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SectionHeader(text: String) = Text(text, color = AjiriwaColors.Primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun SpecRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = AjiriwaColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = AjiriwaColors.TextPrimary, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Fact(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(AjiriwaColors.Surface).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = AjiriwaColors.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun Directions(api: PropertiesApi, l: JSONObject, context: JSONObject?) {
    val ctx = LocalContext.current
    val lat = l.dbl("latitude")
    val lng = l.dbl("longitude")
    if (lat == null || lng == null) {
        Text("Add the house's map location (Edit → Location) to get directions.", color = AjiriwaColors.TextSecondary, modifier = Modifier.padding(16.dp))
        return
    }
    var mode by remember { mutableStateOf("driving") }
    var route by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(mode) {
        route = null; focus = null
        runCatching { api.route(l.getString("id"), mode) }.onSuccess { route = it; error = null }.onFailure { error = it.message }
    }
    val r = route
    val geometry = r?.optJSONArray("geometry")?.let { arr -> (0 until arr.length()).map { i -> arr.getJSONArray(i).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } } }.orEmpty()
    val origin = r?.optJSONObject("origin")?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lng")) }
        ?: context?.optJSONObject("shop")?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lng")) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "driving", onClick = { mode = "driving" }, label = { Text("Drive") })
            FilterChip(selected = mode == "walking", onClick = { mode = "walking" }, label = { Text("Walk") })
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                // Hand over to Google Maps turn-by-turn; fall back to any maps app.
                val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=${if (mode == "walking") "w" else "d"}")).setPackage("com.google.android.apps.maps")
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(r?.optJSONObject("links")?.optString("google_maps") ?: "geo:$lat,$lng?q=$lat,$lng"))
                runCatching { ctx.startActivity(nav) }.onFailure { runCatching { ctx.startActivity(fallback) } }
            }, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent)) {
                Icon(Icons.Filled.Navigation, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Start navigation")
            }
        }
        if (r != null) {
            Text(
                if (r.optBoolean("available")) "${distance(r.dbl("distance_m"))} · about ${minutes(r.dbl("duration_s"))} from ${r.optString("origin_label")}"
                else "About ${distance(r.dbl("straight_line_m"))} from ${r.optString("origin_label")} (straight line)",
                color = AjiriwaColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        error?.let { Text(it, color = AjiriwaColors.Warning, modifier = Modifier.padding(12.dp)) }
        RouteMapView(
            house = GeoPoint(lat, lng), houseTitle = l.optString("title"),
            shop = origin, shopLabel = r?.optString("origin_label") ?: "Shop",
            route = geometry, focus = focus,
            modifier = Modifier.fillMaxWidth().height(300.dp).padding(12.dp).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            r?.optJSONArray("steps").objects().forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        s.dbl("lat")?.let { la -> s.dbl("lng")?.let { lo -> focus = GeoPoint(la, lo) } }
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(AjiriwaColors.PrimaryAccent), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(s.optString("instruction"), color = AjiriwaColors.TextPrimary, fontSize = 14.sp)
                        if (s.optDouble("distance_m", 0.0) > 0) Text(distance(s.dbl("distance_m")), color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Photos(api: PropertiesApi, l: JSONObject, onChanged: (JSONObject) -> Unit, onBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var room by remember { mutableStateOf("living") }
    val id = l.getString("id")

    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            onBusy(true)
            runCatching { api.uploadPhotos(ctx, id, uris, room) }
                .onSuccess { res ->
                    res.optJSONObject("listing")?.let(onChanged)
                    val errs = res.optJSONArray("errors").strings()
                    Toast.makeText(ctx, if (errs.isEmpty()) "Photos uploaded" else errs.joinToString("\n"), Toast.LENGTH_LONG).show()
                }
                .onFailure { Toast.makeText(ctx, it.message ?: "Upload failed", Toast.LENGTH_LONG).show() }
            onBusy(false)
        }
    }
    val camera = rememberCameraCapture { uri -> upload(listOf(uri)) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { upload(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Room for next photos", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ROOMS.forEach { r -> FilterChip(selected = room == r, onClick = { room = r }, label = { Text(r.replaceFirstChar(Char::uppercase)) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = camera, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent)) {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Take photo")
            }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("From gallery")
            }
        }
        Text("Photos are time-stamped — record every room and any existing damage before a tenant moves in.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
        val coverId = l.optJSONObject("cover_photo")?.optString("id")
        l.optJSONArray("photos").objects().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface)) {
                        AsyncImage(model = p.optString("thumb_url"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (p.str("room") ?: "photo").replaceFirstChar(Char::uppercase) + if (p.optString("id") == coverId) " · cover" else "",
                                color = AjiriwaColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                scope.launch { runCatching { api.setCover(id, p.getString("id")) }.onSuccess(onChanged) }
                            }) { Icon(Icons.Filled.Star, "Cover", tint = if (p.optString("id") == coverId) AjiriwaColors.Warning else AjiriwaColors.TextMuted) }
                            IconButton(onClick = {
                                scope.launch { runCatching { api.deletePhoto(id, p.getString("id")) }.onSuccess(onChanged) }
                            }) { Icon(Icons.Filled.Delete, "Delete", tint = AjiriwaColors.Danger) }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShareDialog(api: PropertiesApi, l: JSONObject, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send house details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The visitor gets all specs, photos, the contact person and directions from the shop.", fontSize = 12.sp)
                OutlinedTextField(name, { name = it }, label = { Text("Visitor name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone (SMS)") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
            }
        },
        confirmButton = {
            TextButton(enabled = !sending && (phone.isNotBlank() || email.isNotBlank()), onClick = {
                sending = true
                scope.launch {
                    runCatching { api.share(l.getString("id"), name, phone, email, note) }
                        .onSuccess { Toast.makeText(ctx, "Sent", Toast.LENGTH_SHORT).show(); onDismiss() }
                        .onFailure { Toast.makeText(ctx, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
                    sending = false
                }
            }) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
