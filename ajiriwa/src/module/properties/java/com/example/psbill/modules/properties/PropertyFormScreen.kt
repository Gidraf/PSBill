package com.example.psbill.modules.properties

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.psbill.ui.theme.AjiriwaColors
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File

/** Launches the camera; the full-resolution capture (with EXIF) is written to a FileProvider file. */
@Composable
fun rememberCameraCapture(onCaptured: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pending
        if (ok && uri != null) onCaptured(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.let { launcher.launch(it) }
        else Toast.makeText(ctx, "Camera permission is needed to take photos", Toast.LENGTH_SHORT).show()
    }
    return remember(ctx) {
        {
            val dir = File(ctx.cacheDir, "property_photos").apply { mkdirs() }
            val file = File(dir, "house_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.properties.files", file)
            pending = uri
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launcher.launch(uri)
            else permission.launch(Manifest.permission.CAMERA)
        }
    }
}

private val STEPS = listOf("Basics", "Location", "Specs", "Photos", "Contact")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertyFormScreen(
    api: PropertiesApi,
    schema: JSONObject?,
    context: JSONObject?,
    listing: JSONObject?,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEdit = listing != null
    val isChecker = context?.optBoolean("is_checker") == true
    // Form state: a JSON copy of the listing (core fields + specs object)
    val form = remember { mutableStateMapOf<String, Any?>() }
    val specs = remember { mutableStateMapOf<String, Any?>() }
    val pending = remember { mutableStateListOf<Pair<Uri, String>>() }
    var step by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var photoRoom by remember { mutableStateOf("living") }

    LaunchedEffect(listing) {
        form.clear(); specs.clear()
        if (listing == null) {
            form["listing_purpose"] = "rent"; form["occupancy_status"] = "vacant"; specs["rent_period"] = "month"
        } else {
            listing.keys().forEach { k -> if (k != "specs") form[k] = listing.opt(k).takeUnless { it == JSONObject.NULL } }
            listing.optJSONObject("specs")?.let { s -> s.keys().forEach { k -> specs[k] = s.opt(k).takeUnless { it == JSONObject.NULL } } }
        }
    }

    val core = remember(schema) { schema?.optJSONArray("core").objects().associateBy { it.getString("key") } }
    val sections = remember(schema) { schema?.optJSONArray("sections").objects() }
    val required = listOf("title", "apartment_name", "property_type", "rent_amount")
    val missing = required.filter { form[it] == null || form[it].toString().isBlank() }

    val camera = rememberCameraCapture { uri -> pending.add(uri to photoRoom) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> uris.forEach { pending.add(it to photoRoom) } }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("MissingPermission")
    fun useMyLocation() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) { form["latitude"] = loc.latitude; form["longitude"] = loc.longitude }
                else Toast.makeText(ctx, "Could not get a GPS fix — try outside", Toast.LENGTH_SHORT).show()
            }
    }

    fun payload(): JSONObject = JSONObject().apply {
        val keys = listOf("title", "apartment_name", "unit_label", "property_type", "listing_purpose", "bedrooms", "bathrooms",
            "rent_amount", "occupancy_status", "description", "latitude", "longitude", "address", "distance_to_tarmac_m",
            "contact_name", "contact_role", "contact_phone", "contact_email")
        keys.forEach { k -> if (form.containsKey(k)) put(k, form[k] ?: JSONObject.NULL) }
        put("specs", JSONObject().apply {
            specs.forEach { (k, v) -> put(k, if (v is List<*>) JSONArray(v) else v ?: JSONObject.NULL) }
        })
    }

    fun save(submit: Boolean) {
        if (missing.isNotEmpty()) {
            Toast.makeText(ctx, "Fill in: ${missing.joinToString { core[it]?.optString("label") ?: it }}", Toast.LENGTH_LONG).show()
            step = 0; return
        }
        saving = true
        scope.launch {
            runCatching {
                var saved = if (isEdit) api.update(listing!!.getString("id"), payload())
                else api.create(payload().put("submit", false))
                val id = saved.getString("id")
                // Upload queued photos grouped by room
                pending.groupBy({ it.second }, { it.first }).forEach { (room, uris) ->
                    api.uploadPhotos(ctx, id, uris, room).optJSONObject("listing")?.let { saved = it }
                }
                pending.clear()
                if (submit && saved.optString("approval_status") in listOf("DRAFT", "REJECTED")) saved = api.submit(id)
                saved
            }.onSuccess {
                val status = it.optString("approval_status")
                Toast.makeText(ctx, when (status) {
                    "APPROVED" -> "Saved and live"
                    "PENDING" -> "Sent to checkers for approval"
                    else -> "Saved as draft"
                }, Toast.LENGTH_SHORT).show()
                onSaved(it.getString("id"))
            }.onFailure { Toast.makeText(ctx, it.message ?: "Could not save", Toast.LENGTH_LONG).show() }
            saving = false
        }
    }

    Scaffold(
        containerColor = AjiriwaColors.Canvas,
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit house" else "Add house", color = AjiriwaColors.TextPrimary) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, null, tint = AjiriwaColors.Primary) } },
                actions = {
                    if (!isEdit) TextButton(enabled = !saving, onClick = { save(false) }) { Text("Draft") }
                    Button(enabled = !saving, onClick = { save(true) }, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent)) {
                        Text(if (saving) "Saving…" else if (isChecker) "Publish" else "Submit")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AjiriwaColors.Surface),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AjiriwaColors.PrimaryAccent)
            ScrollableTabRow(selectedTabIndex = step, containerColor = AjiriwaColors.Canvas, contentColor = AjiriwaColors.Primary, edgePadding = 8.dp) {
                STEPS.forEachIndexed { i, t -> Tab(selected = step == i, onClick = { step = i }, text = { Text(t) }) }
            }
            if (schema == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isChecker) Text("A checker approves this house before it goes live. Checkers are emailed when you submit.", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                when (step) {
                    0 -> {
                        listOf("title", "apartment_name", "unit_label", "property_type", "listing_purpose", "bedrooms", "bathrooms", "rent_amount")
                            .forEach { k -> core[k]?.let { f -> SpecInput(f, form[k]) { form[k] = it } } }
                        sections.flatMap { it.optJSONArray("fields").objects() }.firstOrNull { it.optString("key") == "rent_period" }
                            ?.let { f -> SpecInput(f, specs["rent_period"]) { specs["rent_period"] = it } }
                        listOf("occupancy_status", "description").forEach { k -> core[k]?.let { f -> SpecInput(f, form[k]) { form[k] = it } } }
                    }
                    1 -> {
                        Text("Tap the map where the house is, or stand at the gate and use GPS.", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
                        OutlinedButton(onClick = ::useMyLocation) { Icon(Icons.Filled.MyLocation, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Use my current location") }
                        val lat = (form["latitude"] as? Number)?.toDouble()
                        val lng = (form["longitude"] as? Number)?.toDouble()
                        val shop = context?.optJSONObject("shop")?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lng")) }
                        LocationPickerView(
                            point = if (lat != null && lng != null) GeoPoint(lat, lng) else null,
                            shop = shop,
                            onPick = { p -> form["latitude"] = "%.6f".format(p.latitude).toDouble(); form["longitude"] = "%.6f".format(p.longitude).toDouble() },
                            modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Text(if (lat != null) "Pin: %.5f, %.5f".format(lat, lng) else "No pin yet", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                        listOf("distance_to_tarmac_m", "address").forEach { k -> core[k]?.let { f -> SpecInput(f, form[k]) { form[k] = it } } }
                        sections.firstOrNull { it.optString("key") == "access" }?.optJSONArray("fields").objects()
                            .forEach { f -> SpecInput(f, specs[f.optString("key")]) { specs[f.optString("key")] = it } }
                    }
                    2 -> sections.filter { it.optString("key") != "access" }.forEach { sec ->
                        var open by remember(sec.optString("key")) { mutableStateOf(sec.optString("key") in listOf("basics", "costs")) }
                        val fields = sec.optJSONArray("fields").objects().filter { it.optString("key") != "rent_period" }
                        val filled = fields.count { specs[it.optString("key")] != null }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AjiriwaColors.Surface).clickable { open = !open }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sec.optString("title"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("$filled/${fields.size}", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = AjiriwaColors.TextMuted)
                        }
                        if (open) fields.forEach { f -> SpecInput(f, specs[f.optString("key")]) { specs[f.optString("key")] = it } }
                    }
                    3 -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("living", "kitchen", "bedroom", "bathroom", "exterior", "compound", "parking", "view", "other").forEach { r ->
                                FilterChip(selected = photoRoom == r, onClick = { photoRoom = r }, label = { Text(r.replaceFirstChar(Char::uppercase)) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = camera, colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent)) {
                                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Take photo")
                            }
                            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("From gallery")
                            }
                        }
                        if (isEdit) Text("Existing photos: ${listing!!.optJSONArray("photos")?.length() ?: 0} (manage them on the house's Photos tab).", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                        Text("New photos upload when you save.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                        pending.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { (uri, room) ->
                                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                                        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        Text(room, color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp,
                                            modifier = Modifier.align(Alignment.BottomStart).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .5f)).padding(3.dp))
                                        IconButton(onClick = { pending.remove(uri to room) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                                            Icon(Icons.Filled.Close, null, tint = androidx.compose.ui.graphics.Color.White)
                                        }
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    4 -> {
                        listOf("contact_name", "contact_role", "contact_phone", "contact_email").forEach { k -> core[k]?.let { f -> SpecInput(f, form[k]) { form[k] = it } } }
                        if (missing.isNotEmpty()) Text("Still required: ${missing.joinToString { core[it]?.optString("label") ?: it }}", color = AjiriwaColors.Warning, fontSize = 13.sp)
                        if (form["latitude"] == null) Text("No map pin — customers won't get directions from the shop.", color = AjiriwaColors.Warning, fontSize = 13.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    if (step < STEPS.lastIndex) OutlinedButton(onClick = { step++ }) { Text("Next") }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** One field of the server's property spec schema. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecInput(field: JSONObject, value: Any?, onChange: (Any?) -> Unit) {
    val label = field.optString("label") + if (field.optBoolean("required")) " *" else ""
    val options = field.optJSONArray("options").objects()
    val unit = field.str("unit")
    val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AjiriwaColors.TextPrimary, unfocusedTextColor = AjiriwaColors.TextPrimary)
    when (field.optString("type")) {
        "bool" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.optString("label"), color = AjiriwaColors.TextPrimary, modifier = Modifier.weight(1f))
            Switch(checked = value == true, onCheckedChange = { onChange(it) })
        }
        "select" -> {
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = options.firstOrNull { it.optString("value") == value }?.optString("label") ?: "",
                    onValueChange = {}, readOnly = true, label = { Text(label) }, colors = colors,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Not set") }, onClick = { onChange(null); open = false })
                    options.forEach { o -> DropdownMenuItem(text = { Text(o.optString("label")) }, onClick = { onChange(o.optString("value")); open = false }) }
                }
            }
        }
        "multiselect" -> {
            val selected = (value as? List<*>)?.map { it.toString() } ?: (value as? JSONArray).strings()
            Text(label, color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { o ->
                    val v = o.optString("value")
                    FilterChip(selected = v in selected, onClick = { onChange(if (v in selected) selected - v else selected + v) }, label = { Text(o.optString("label")) })
                }
            }
        }
        "number", "money" -> OutlinedTextField(
            value = value?.let { if (it is Double && it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "",
            onValueChange = { t -> onChange(t.replace(",", "").toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong() else it }) },
            label = { Text(label) }, singleLine = true, colors = colors,
            prefix = if (field.optString("type") == "money") ({ Text("KES ") }) else null,
            suffix = unit?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        "textarea" -> OutlinedTextField(value = value?.toString() ?: "", onValueChange = onChange, label = { Text(label) }, minLines = 3, colors = colors, modifier = Modifier.fillMaxWidth())
        else -> OutlinedTextField(value = value?.toString() ?: "", onValueChange = onChange, label = { Text(label) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
            placeholder = if (field.optString("type") == "date") ({ Text("YYYY-MM-DD") }) else null)
    }
    field.str("help")?.let { Text(it, color = AjiriwaColors.TextMuted, fontSize = 11.sp) }
}
