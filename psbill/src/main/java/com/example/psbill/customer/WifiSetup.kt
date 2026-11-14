package com.example.psbill.customer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/*
 * WiFi billing setup from the phone (PSBill Admin): link a MikroTik router over the tunnel
 * (pick hardware -> name -> install the config -> watch it connect -> finish), see routers,
 * and sell WiFi packages. Same endpoints as the web wizard (/api/v1/wifi/<partner>/...).
 */

private val UNITS = listOf("Mins", "Hrs", "Days", "Months")

@Composable
fun WifiSetupCard() {
    val api = LocalPsApi.current ?: return
    val pid = LocalPsProfile.current?.businessId.orEmpty()
    if (pid.isBlank()) return
    var routers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var sites by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var packages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var wizard by remember { mutableStateOf<JSONObject?>(null) }       // a site being set up
    var linking by remember { mutableStateOf(false) }
    var editingPkg by remember { mutableStateOf<JSONObject?>(null) }
    var addingPkg by remember { mutableStateOf(false) }
    var deletingPkg by remember { mutableStateOf<JSONObject?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        loading = true
        api.get("/api/v1/wifi/$pid/routers") { c, b -> if (c in 200..299) routers = objects(runCatching { JSONObject(b).optJSONArray("data") }.getOrNull()).filter { !it.optBoolean("deleted") } }
        api.get("/api/v1/wifi/$pid/sites") { c, b -> if (c in 200..299) sites = objects(runCatching { JSONObject(b).optJSONArray("data") }.getOrNull()) }
        api.get("/api/v1/wifi/$pid/packages") { c, b ->
            loading = false
            if (c in 200..299) packages = objects(runCatching { JSONObject(b).optJSONArray("data") }.getOrNull()).filter { !it.optBoolean("deleted") }
        }
    }

    PSBillCard(title = "WiFi billing") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val unfinished = sites.filter { it.optString("router_id").isBlank() || it.isNull("router_id") }
            if (routers.isEmpty() && unfinished.isEmpty() && !loading) {
                Text("Sell WiFi time on your lounge's own MikroTik router. Customers pay by M-Pesa to YOUR till on the WiFi page; " +
                    "free passes for gamers come from here too.", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
            }
            routers.forEach { r -> RouterRow(r) }
            unfinished.forEach { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PsTone.Warn.copy(alpha = 0.12f))
                    .clickable { wizard = s }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.HourglassTop, contentDescription = null, tint = PsTone.Warn)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.optString("name"), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Setup not finished - tap to continue", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PSBillThemeColors.TextSecondary)
                }
            }
            Button(onClick = { linking = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (routers.isEmpty()) "Link your MikroTik router" else "Link another router")
            }

            if (routers.isNotEmpty()) {
                HorizontalDivider(color = PSBillThemeColors.Border)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Packages", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { addingPkg = true }) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text("Add") }
                }
                if (packages.isEmpty()) Text("No packages yet - add one so customers can buy WiFi.", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                packages.sortedBy { it.optDouble("price_kes") }.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PSBillThemeColors.SurfaceAlt)
                        .clickable { editingPkg = p }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.optString("display_name"), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.optInt("validity")} ${p.optString("validity_unit")} - ${p.optInt("device_limit", 1)} device(s)" +
                                p.optString("speed_label").let { if (it.isBlank()) "" else " - $it" },
                                color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                        }
                        Text(if (p.optBoolean("is_free")) "Free" else "KES ${p.optDouble("price_kes").toInt()}",
                            color = PsTone.Good, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { deletingPkg = p }) { Icon(Icons.Filled.DeleteOutline, "Delete package", tint = PsTone.Bad) }
                    }
                }
            }
        }
    }

    if (linking) LinkRouterSheet(pid, onDismiss = { linking = false }) { site -> linking = false; wizard = site; tick++ }
    wizard?.let { s -> RouterProgressSheet(pid, s, onDismiss = { wizard = null; tick++ }) }
    if (addingPkg || editingPkg != null) PackageSheet(pid, editingPkg, routers, onDismiss = { addingPkg = false; editingPkg = null }) {
        addingPkg = false; editingPkg = null; tick++
    }
    deletingPkg?.let { p ->
        PsConfirm("Delete ${p.optString("display_name")}?", "Customers can no longer buy it. People already connected keep their time.", "Delete",
            onConfirm = {
                deletingPkg = null
                api.delete("/api/v1/wifi/$pid/packages/${p.optString("id")}") { c, b ->
                    if (c in 200..299) { api.toast("Package deleted"); tick++ } else api.toast(api.errorText(c, b, "Could not delete"))
                }
            }, onDismiss = { deletingPkg = null })
    }
}

@Composable
private fun RouterRow(r: JSONObject) {
    val seen = r.optString("last_seen_at")
    val online = r.optString("status").equals("online", true) || r.optString("status").equals("active", true)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PSBillThemeColors.SurfaceAlt).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) PsTone.Good else if (seen.isNotBlank()) PsTone.Warn else PsTone.Idle))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.optString("label").ifBlank { "Router" }, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(if (seen.isNotBlank()) "Last seen ${seen.take(16).replace('T', ' ')} UTC" else "Not seen yet",
                color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        }
        PsPill(r.optString("status").ifBlank { "unknown" }, if (online) PsTone.Good else PsTone.Idle)
    }
}

@Composable
private fun LinkRouterSheet(pid: String, onDismiss: () -> Unit, onCreated: (JSONObject) -> Unit) {
    val api = LocalPsApi.current ?: return
    var options by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var vendor by remember { mutableStateOf("mikrotik") }
    var name by remember { mutableStateOf("") }
    var iface by remember { mutableStateOf("bridge") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        api.get("/api/v1/wifi/$pid/onboarding/options") { c, b ->
            if (c in 200..299) options = objects(runCatching { JSONObject(b).getJSONObject("data").optJSONArray("gateways") }.getOrNull())
        }
    }
    PsFormSheet("Link a router", onDismiss, "Make my config", busy = busy, confirmEnabled = name.isNotBlank(), onConfirm = {
        busy = true; error = null
        val body = JSONObject().put("name", name.trim()).put("gateway_vendor", if (vendor == "mikrotik") "mikrotik" else "mikrotik")
            .put("hotspot_interface", iface.trim().ifBlank { "bridge" })
        if (vendor != "mikrotik") body.put("ap_vendor", vendor)
        api.post("/api/v1/wifi/$pid/sites", body) { c, b ->
            busy = false
            if (c in 200..299) runCatching { JSONObject(b) }.getOrNull()?.let { o -> onCreated(o.optJSONObject("data") ?: o) }
            else error = api.errorText(c, b, "Could not make the config")
        }
    }) {
        Text("1. What WiFi hardware do you have?", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        options.forEach { o ->
            val key = o.optString("key")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (key == vendor) PSBillThemeColors.PrimaryAccent.copy(alpha = 0.14f) else PSBillThemeColors.SurfaceAlt)
                .clickable { vendor = key }.padding(10.dp), verticalAlignment = Alignment.Top) {
                RadioButton(selected = key == vendor, onClick = { vendor = key })
                Column {
                    Text(o.optString("name"), color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(o.optString("blurb"), color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
                }
            }
        }
        if (vendor != "mikrotik") Text("Billing runs on a MikroTik in front of your access points. Link the MikroTik here.",
            color = PsTone.Warn, fontSize = 12.sp)
        Text("2. Name this place", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it }, label = { Text("e.g. Main lounge") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(iface, { iface = it }, label = { Text("Hotspot interface (advanced)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Leave as bridge unless your technician says otherwise") })
        error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
    }
}

/** Step 3-5: install the config, watch the router connect, finish. */
@Composable
private fun RouterProgressSheet(pid: String, site: JSONObject, onDismiss: () -> Unit) {
    val api = LocalPsApi.current ?: return
    val context = LocalContext.current
    val sid = site.optString("id")
    var configUrl by remember { mutableStateOf(site.optString("config_url")) }
    val fileName = site.optString("rsc_filename").ifBlank { "cvpap-wifi.rsc" }
    var steps by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var overall by remember { mutableStateOf("in_progress") }
    var finishing by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sid) {
        if (configUrl.isBlank()) api.post("/api/v1/wifi/$pid/sites/$sid/config-link", JSONObject()) { c, b ->
            if (c in 200..299) configUrl = runCatching { JSONObject(b).let { (it.optJSONObject("data") ?: it).optString("config_url") } }.getOrDefault("")
        }
        while (true) {
            api.get("/api/v1/wifi/$pid/sites/$sid/progress") { c, b ->
                if (c in 200..299) runCatching { JSONObject(b).getJSONObject("data") }.getOrNull()?.let {
                    steps = objects(it.optJSONArray("steps")); overall = it.optString("overall")
                }
            }
            if (overall == "registered") break
            delay(5_000)
        }
    }

    val importCmd = "/tool fetch url=\"$configUrl\" check-certificate=no dst-path=$fileName; :delay 2s; /import file-name=$fileName"
    PsFormSheet(
        title = "Set up ${site.optString("name")}",
        onDismiss = onDismiss,
        confirmText = if (overall == "registered") "Done" else "Finish setup",
        busy = finishing,
        danger = false,
        onConfirm = {
            if (overall == "registered") { onDismiss(); return@PsFormSheet }
            finishing = true; hint = null
            api.post("/api/v1/wifi/$pid/sites/$sid/finish", JSONObject()) { c, b ->
                finishing = false
                if (c in 200..299) { api.toast("Router linked - WiFi billing is on"); onDismiss() }
                else hint = runCatching { JSONObject(b).optJSONObject("detail")?.optString("hint") }.getOrNull()?.ifBlank { null }
                    ?: api.errorText(c, b, "The router is not reachable yet")
            }
        },
    ) {
        Text("3. Put the config on the router", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Text("Open WinBox / the router's Terminal and paste this one line. Or send the link to your technician.",
            color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        if (configUrl.isNotBlank()) {
            Text(importCmd, color = PSBillThemeColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PSBillThemeColors.Canvas).padding(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, importCmd); api.toast("Copied") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
                }
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                        "PSBill WiFi setup for ${site.optString("name")}. On the MikroTik terminal paste:\n\n$importCmd\n\nConfig file: $configUrl"),
                        "Send to technician").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Send")
                }
            }
        } else Text("Making the config link...", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)

        Text("4. Watch it connect", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        if (steps.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        steps.forEach { s ->
            val state = s.optString("state")
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    "ok" -> Icon(Icons.Filled.CheckCircle, null, tint = PsTone.Good, modifier = Modifier.size(20.dp))
                    "failed" -> Icon(Icons.Filled.Error, null, tint = PsTone.Bad, modifier = Modifier.size(20.dp))
                    else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(s.optString("label"), color = PSBillThemeColors.TextPrimary, fontSize = 14.sp)
                    s.optString("detail").takeIf { it.isNotBlank() }?.let { Text(it, color = PSBillThemeColors.TextSecondary, fontSize = 11.sp) }
                }
            }
        }
        Text(when (overall) {
            "registered" -> "Done - your router is linked."
            "ready" -> "5. The router answers. Tap Finish setup."
            else -> "This updates by itself every few seconds."
        }, color = if (overall == "in_progress") PSBillThemeColors.TextSecondary else PsTone.Good, fontSize = 12.sp)
        hint?.let { Text(it, color = PsTone.Warn, fontSize = 13.sp) }
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PSBill", text))
}

@Composable
private fun PackageSheet(pid: String, pkg: JSONObject?, routers: List<JSONObject>, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val api = LocalPsApi.current ?: return
    var name by remember { mutableStateOf(pkg?.optString("display_name").orEmpty()) }
    var price by remember { mutableStateOf(pkg?.optDouble("price_kes")?.toInt()?.toString() ?: "20") }
    var validity by remember { mutableStateOf(pkg?.optInt("validity")?.takeIf { it > 0 }?.toString() ?: "1") }
    var unit by remember { mutableStateOf(pkg?.optString("validity_unit")?.ifBlank { null } ?: "Hrs") }
    var devices by remember { mutableStateOf(pkg?.optInt("device_limit", 1)?.toString() ?: "1") }
    var speed by remember { mutableStateOf("5") }
    var router by remember { mutableStateOf(if (pkg == null) "all" else pkg.optString("router_id").ifBlank { "all" }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ok = name.isNotBlank() && price.toIntOrNull() != null && (validity.toIntOrNull() ?: 0) > 0 && (devices.toIntOrNull() ?: 0) > 0
    PsFormSheet(if (pkg == null) "New WiFi package" else "Edit ${pkg.optString("display_name")}", onDismiss, "Save", busy = busy,
        confirmEnabled = ok, onConfirm = {
            busy = true; error = null
            val body = JSONObject().put("display_name", name.trim()).put("price_kes", price.toInt()).put("validity", validity.toInt())
                .put("validity_unit", unit).put("device_limit", devices.toInt()).put("router_id", router)
            if (pkg == null) body.put("speed_mbps", speed.toDoubleOrNull() ?: 5.0)
            val done: (Int, String) -> Unit = { c, b ->
                busy = false
                if (c in 200..299) { api.toast("Saved"); onSaved() } else error = api.errorText(c, b, "Could not save the package")
            }
            if (pkg == null) api.post("/api/v1/wifi/$pid/packages", body, done)
            else api.put("/api/v1/wifi/$pid/packages/${pkg.optString("id")}", body, done)
        }) {
        OutlinedTextField(name, { name = it }, label = { Text("Name customers see") }, placeholder = { Text("e.g. 1 hour") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it.filter(Char::isDigit) }, label = { Text("Price KES (0 = free)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(validity, { validity = it.filter(Char::isDigit) }, label = { Text("Lasts") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Box(Modifier.weight(1f)) { DropdownField("Unit", unit, UNITS, display = { mapOf("Mins" to "Minutes", "Hrs" to "Hours", "Days" to "Days", "Months" to "Months")[it] ?: it }) { unit = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(devices, { devices = it.filter(Char::isDigit) }, label = { Text("Devices") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (pkg == null) OutlinedTextField(speed, { speed = it }, label = { Text("Speed Mbps") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (routers.size > 1 || pkg == null) DropdownField("Router", router, listOf("all") + routers.map { it.optString("id") },
            display = { id -> if (id == "all") "All routers" else routers.firstOrNull { it.optString("id") == id }?.optString("label") ?: id }) { router = it }
        error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
    }
}
