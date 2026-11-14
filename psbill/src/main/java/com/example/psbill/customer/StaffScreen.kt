package com.example.psbill.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private const val ADMIN_ROLE = "PSBill Admin"
private const val AGENT_ROLE = "PSBill Agent"

/**
 * Staff and roles (owner): who can sign in to this lounge and what they may do.
 * PSBill Admin = everything; PSBill Agent = runs the screens (sessions, queue, top-ups, vouchers),
 * no screens, prices, logs, reports, staff or billing. Invites get an email with a set-password link.
 */
@Composable
fun StaffScreen(onBack: () -> Unit) {
    val api = LocalPsApi.current ?: return
    val me = LocalPsProfile.current
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var roles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf<JSONObject?>(null) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }

    fun load() {
        loading = true; error = null
        api.get("/api/v1/role") { code, body ->
            if (code in 200..299) roles = objects(runCatching { JSONObject(body).optJSONArray("result") }.getOrNull())
        }
        api.get("/api/v1/admin-users?per_page=100") { code, body ->
            loading = false
            if (code in 200..299) users = objects(runCatching { JSONObject(body).optJSONArray("result") }.getOrNull())
            else error = api.errorText(code, body, "Could not load your staff")
        }
    }
    LaunchedEffect(Unit) { load() }

    fun roleId(name: String) = roles.firstOrNull { it.optString("name").equals(name, true) }?.optString("id")

    fun setRole(userId: String, role: String, done: () -> Unit = {}) {
        val id = roleId(role) ?: run { api.toast("The $role role is missing - open the app again in a minute"); return }
        api.post("/user/$userId/role", JSONObject().put("roles", JSONArray().put(id))) { code, body ->
            if (code in 200..299) { done(); load() } else api.toast(api.errorText(code, body, "Could not change the role"))
        }
    }

    Column(Modifier.fillMaxSize()) {
        PsSubHeader("Staff and roles", "Who can sign in to this lounge", onBack) {
            Button(onClick = { inviting = true }) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Invite")
            }
        }
        RoleExplainer()
        Spacer(Modifier.height(12.dp))
        PsLoadState(loading, error, users.isEmpty(), "No staff yet. Invite your first attendant.", ::load) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(users, key = { it.optString("id") }) { u ->
                    val roleNames = objects(u.optJSONArray("roles")).map { it.optString("name") }
                    val isMe = u.optString("email").equals(me?.email, true)
                    StaffRow(u, roleNames, isMe, onChange = { changing = u }, onRemove = { removing = u })
                }
            }
        }
    }

    if (inviting) InviteForm(onDismiss = { inviting = false }) { name, email, phone, role, done ->
        val username = email.substringBefore("@").filter { it.isLetterOrDigit() }.take(20) + (1000..9999).random()
        api.post("/api/v1/admin-user", JSONObject().put("name", name).put("email", email).put("phone_number", phone)
            .put("username", username)) { code, body ->
            if (code in 200..299) {
                val id = runCatching { JSONObject(body).getJSONObject("data").getString("id") }.getOrNull()
                if (id != null) setRole(id, role) { api.toast("Invite sent to $email - they set a password from the email") }
                done(null); inviting = false; load()
            } else done(api.errorText(code, body, "Could not invite"))
        }
    }
    changing?.let { u ->
        val current = objects(u.optJSONArray("roles")).map { it.optString("name") }
        var pick by remember(u) { mutableStateOf(if (current.any { it.contains("admin", true) || it.contains("owner", true) }) ADMIN_ROLE else AGENT_ROLE) }
        PsFormSheet("Role for ${u.optString("name")}", onDismiss = { changing = null }, confirmText = "Save",
            onConfirm = { setRole(u.optString("id"), pick) { api.toast("Role changed. They see it after signing in again.") }; changing = null }) {
            RolePicker(pick) { pick = it }
        }
    }
    removing?.let { u ->
        PsConfirm("Remove ${u.optString("name")}?", "They can no longer sign in to this lounge. Their past sessions stay in your reports.",
            "Remove", onConfirm = {
                removing = null
                api.delete("/admin-users/${u.optString("id")}") { code, body ->
                    if (code in 200..299) { api.toast("Removed"); load() } else api.toast(api.errorText(code, body, "Could not remove"))
                }
            }, onDismiss = { removing = null })
    }
}

@Composable
private fun RoleExplainer() {
    val wide = isWide()
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> RoleCard(m, ADMIN_ROLE, PsTone.Busy, "Everything: screens, prices, games, Wi-Fi setup, reports and exports, logs, staff, billing.") },
        { m -> RoleCard(m, AGENT_ROLE, PsTone.Good, "Runs the lounge: start/stop sessions, queue, approve top-ups, vouchers. No screens, prices, logs or reports.") },
    )
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { cards.forEach { it(Modifier.weight(1f)) } }
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cards.forEach { it(Modifier.fillMaxWidth()) } }
}

@Composable
private fun RoleCard(modifier: Modifier, title: String, tone: Color, text: String) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.10f)).padding(12.dp)) {
        Text(title, color = tone, fontWeight = FontWeight.Bold)
        Text(text, color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun StaffRow(u: JSONObject, roleNames: List<String>, isMe: Boolean, onChange: () -> Unit, onRemove: () -> Unit) {
    val admin = roleNames.any { it.contains("admin", true) || it.contains("owner", true) || it.contains("manager", true) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PSBillThemeColors.Surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (admin) PsTone.Busy else PsTone.Good), contentAlignment = Alignment.Center) {
            Text(u.optString("name").take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.optString("name") + if (isMe) " (you)" else "", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(u.optString("email"), color = PSBillThemeColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (roleNames.isEmpty()) PsPill("No role", PsTone.Warn)
                roleNames.take(2).forEach { PsPill(it, if (it.contains("admin", true)) PsTone.Busy else PsTone.Good) }
            }
        }
        if (!isMe) {
            IconButton(onClick = onChange) { Icon(Icons.Filled.ManageAccounts, contentDescription = "Change role", tint = PSBillThemeColors.TextSecondary) }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.PersonRemove, contentDescription = "Remove", tint = PsTone.Bad) }
        }
    }
}

@Composable
private fun RolePicker(selected: String, onPick: (String) -> Unit) {
    listOf(AGENT_ROLE to "Attendant: sessions, queue, top-ups, vouchers", ADMIN_ROLE to "Admin: everything, like you").forEach { (r, d) ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (r == selected) PSBillThemeColors.PrimaryAccent.copy(alpha = 0.14f) else PSBillThemeColors.SurfaceAlt)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = r == selected, onClick = { onPick(r) })
            Column {
                Text(r, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(d, color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InviteForm(onDismiss: () -> Unit, onInvite: (String, String, String, String, (String?) -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(AGENT_ROLE) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    val phoneOk = Regex("^\\+?[0-9 ]{9,15}$").matches(phone.trim())
    PsFormSheet("Invite staff", onDismiss, "Send invite", busy = busy,
        confirmEnabled = name.isNotBlank() && emailOk && phoneOk,
        onConfirm = {
            busy = true; error = null
            onInvite(name.trim(), email.trim().lowercase(), phone.trim().replace(" ", ""), role) { err -> busy = false; error = err }
        }) {
        OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = email.isNotBlank() && !emailOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            supportingText = { Text("They get an email to set their own password") })
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = phone.isNotBlank() && !phoneOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        Text("Role", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        RolePicker(role) { role = it }
        error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
    }
}
