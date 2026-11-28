package com.example.psbill.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.psbill.ui.theme.AjiriwaColors

/**
 * One reason we ask for a group of Android permissions, in plain words (owner 2026-11-27: before asking for
 * SMS / call log / contacts / location, say what each is for and recommend a phone used only for the business).
 */
data class PermissionReason(val title: String, val why: String, val permissions: List<String>)

object PermissionReasons {
    val SMS = PermissionReason(
        "Messages (SMS)",
        "Reads M-Pesa messages so a payment becomes a sale on its own, and sends receipts, delivery and reminder " +
            "SMS to your customers from this phone.",
        listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS),
    )
    val CALLS = PermissionReason(
        "Call log and phone",
        "Counts calls from customers so we know who is likely to order today, and links a caller to their orders.",
        listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE),
    )
    val CONTACTS = PermissionReason(
        "Contacts",
        "Shows your customers' names instead of numbers. A contact only counts as a customer after they buy.",
        listOf(Manifest.permission.READ_CONTACTS),
    )
    val LOCATION = PermissionReason(
        "Location",
        "Draws the delivery route and confirms a delivery was made at the customer's place.",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )
    val NOTIFICATIONS = PermissionReason(
        "Notifications",
        "Tells you when a filter change, a meter reading or a delivery is due.",
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
    )
    val ALL = listOf(SMS, CALLS, CONTACTS, LOCATION, NOTIFICATIONS)

    /** The reasons that cover these permissions, in a fixed order. */
    fun forPermissions(perms: Collection<String>): List<PermissionReason> =
        ALL.filter { r -> r.permissions.any { it in perms } }
}

const val BUSINESS_PHONE_ADVICE =
    "We recommend a phone used only for the business. This app reads the messages, calls and contacts on this " +
        "phone - on a personal phone that would include your private ones."

/**
 * Explains, then asks. Returns a function taking the permissions to request: the missing ones are listed with
 * their reasons in a dialog; "Continue" opens Android's own permission prompts, "Not now" asks nothing.
 * [onDone] runs after Android's answer, or at once when nothing is missing or the owner picked "Not now"
 * (granted = what is granted now).
 */
@Composable
fun rememberExplainedPermissions(onDone: (granted: Set<String>) -> Unit): (Array<String>) -> Unit {
    val context = LocalContext.current
    val me = remember { Any() }
    var pending by remember { mutableStateOf<List<String>?>(null) }
    fun grantedNow(perms: Collection<String>) =
        perms.filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }.toSet()
    fun release() {
        if (ExplainerGate.owner === me) ExplainerGate.owner = null
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        release()
        onDone(res.filterValues { it }.keys + grantedNow(res.keys))
    }
    // several parts of the app ask at sign-in (location agent, SMS module): one explainer at a time
    LaunchedEffect(pending, ExplainerGate.owner) {
        if (pending != null && ExplainerGate.owner == null) ExplainerGate.owner = me
    }
    val want = pending
    if (want != null && ExplainerGate.owner === me) {
        val still = want.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (still.isEmpty()) {
            LaunchedEffect(want) {
                pending = null
                release()
                onDone(want.toSet())
            }
        } else {
            PermissionExplainerDialog(
                reasons = PermissionReasons.forPermissions(still),
                onContinue = {
                    pending = null
                    launcher.launch(still.toTypedArray())   // the gate is released when Android answers
                },
                onLater = {
                    pending = null
                    release()
                    onDone(grantedNow(want))
                },
            )
        }
    }
    return remember(context) {
        { perms ->
            val missing = perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) onDone(perms.toSet()) else pending = missing
        }
    }
}

/** Who shows the explainer now (null = nobody). */
private object ExplainerGate {
    var owner by mutableStateOf<Any?>(null)
}

@Composable
fun PermissionExplainerDialog(reasons: List<PermissionReason>, onContinue: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = AjiriwaColors.SurfaceAlt,
        title = { Text("Before Android asks", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    BUSINESS_PHONE_ADVICE,
                    color = Color(0xFF1F1300),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(AjiriwaColors.Warning).padding(12.dp),
                )
                Text(
                    "Next, Android will ask you to allow these. Here is what each one is for:",
                    color = AjiriwaColors.TextSecondary, fontSize = 14.sp,
                )
                reasons.forEach { r ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(r.title, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(r.why, color = AjiriwaColors.TextSecondary, fontSize = 14.sp)
                    }
                }
                Text(
                    "Everything stays in your business account. You can switch any of these off later in Android " +
                        "Settings > Apps > Ajiriwa > Permissions.",
                    color = AjiriwaColors.TextMuted, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
            ) { Text("Continue", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Not now", color = AjiriwaColors.TextSecondary) } },
    )
}
