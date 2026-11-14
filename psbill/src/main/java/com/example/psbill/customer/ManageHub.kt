package com.example.psbill.customer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/*
 * "Manage" tab: everything the owner sets up from the phone (games, wallpapers, staff, billing),
 * plus the account card and sign out. Agents get only their account here.
 */
@Composable
fun ManageHub(
    profile: PsProfile?,
    nodes: List<JSONObject>,
    games: List<JSONObject>,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf("HUB") }
    BackHandler(enabled = page != "HUB") { page = "HUB" }
    val back = { page = "HUB" }
    when (page) {
        "GAMES" -> GamesScreen(games, onBack = back, onChanged = onRefresh)
        "WALLPAPERS" -> WallpapersScreen(nodes, onBack = back, onChanged = onRefresh)
        "STAFF" -> StaffScreen(onBack = back)
        "BILLING" -> BillingScreen(onBack = back)
        "PASSWORD" -> PasswordScreen(onBack = back)
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountCard(profile, onSignOut)
            if (profile?.isAdmin == true) {
                PsSectionLabel("Your lounge")
                val cols = gridColumns()
                val items = listOf(
                    Triple(Icons.Filled.SportsEsports, "Games and covers", "Add games, prices per minute, cover photos") to "GAMES",
                    Triple(Icons.Filled.Wallpaper, "TV wallpapers", "The lock-screen picture on each TV") to "WALLPAPERS",
                    Triple(Icons.Filled.Group, "Staff and roles", "Invite attendants (PSBill Agent) or admins") to "STAFF",
                    Triple(Icons.Filled.ReceiptLong, "Billing and invoices", "Your PSBill plan, invoices and tokens") to "BILLING",
                )
                items.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (t, key) ->
                            Box(Modifier.weight(1f)) { PsHubRow(t.first, t.second, t.third) { page = key } }
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("Prices, queue rules and the meter colours are in Screens > Prices. Router setup is in the Wi-Fi tab.",
                    color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            } else if (profile != null) {
                PsSurface {
                    Text("You are signed in as a PSBill Agent", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("You can start and stop sessions, approve the queue and top-ups, and sell vouchers. " +
                        "Screens, prices, games, reports, staff and billing are for the lounge admin.",
                        color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
                }
            }
            PsSectionLabel("Account")
            PsHubRow(Icons.Filled.Password, "Change password", "Your own sign-in password", PsTone.Warn) { page = "PASSWORD" }
            PsHubRow(Icons.AutoMirrored.Filled.Logout, "Sign out", "Stop alerts on this phone and sign out", PsTone.Bad, onSignOut)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountCard(profile: PsProfile?, onSignOut: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PSBillThemeColors.Surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(PSBillThemeColors.PrimaryAccent), contentAlignment = Alignment.Center) {
            Text((profile?.name ?: "?").take(1).uppercase(), color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(profile?.name ?: "Loading...", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(profile?.email ?: "", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                profile?.let {
                    PsPill(it.roleLabel, if (it.isAdmin) PsTone.Busy else PsTone.Good)
                    if (it.businessName.isNotBlank()) PsPill(it.businessName, PsTone.Idle)
                }
            }
        }
        IconButton(onClick = onSignOut) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out", tint = PSBillThemeColors.TextSecondary)
        }
    }
}

@Composable
private fun PasswordScreen(onBack: () -> Unit) {
    val api = LocalPsApi.current ?: return
    val context = LocalContext.current
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PsSubHeader("Change password", "At least 8 characters", onBack)
        OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fresh, { fresh = it }, label = { Text("New password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            isError = fresh.isNotEmpty() && fresh.length < 8,
            supportingText = { if (fresh.isNotEmpty() && fresh.length < 8) Text("At least 8 characters") })
        OutlinedTextField(again, { again = it }, label = { Text("New password again") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            isError = again.isNotEmpty() && again != fresh,
            supportingText = { if (again.isNotEmpty() && again != fresh) Text("The two do not match") })
        error?.let { Text(it, color = PsTone.Bad, fontSize = 13.sp) }
        Button(
            onClick = {
                busy = true; error = null
                api.post("/api/v1/profile/password", JSONObject().put("current_password", current).put("new_password", fresh)) { code, body ->
                    busy = false
                    if (code in 200..299) {
                        SavedLogin.save(context, fresh)
                        api.toast("Password changed")
                        onBack()
                    } else error = api.errorText(code, body, "Could not change the password")
                }
            },
            enabled = !busy && current.isNotBlank() && fresh.length >= 8 && fresh == again,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Saving..." else "Change password") }
    }
}
