package com.example.psbill.customer

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/*
 * Shared building blocks for the staff app: who is signed in (PSBill Admin or Agent), the API,
 * responsive helpers (phone vs tablet), form sheets, confirm dialogs and file sharing.
 */

/** GET /api/v1/profile. PSBill Admin (owner powers) or PSBill Agent (runs the screens). */
class PsProfile(val json: JSONObject) {
    private val user = json.optJSONObject("user") ?: JSONObject()
    private val business = json.optJSONObject("business") ?: JSONObject()
    val name: String = user.optString("name").ifBlank { user.optString("email") }
    val email: String = user.optString("email")
    val roles: List<String> = user.optJSONArray("roles")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    val isOwner: Boolean = json.optBoolean("is_owner", false)
    val isAdmin: Boolean = json.optString("psbill_role").let { if (it.isBlank()) isOwner else it == "admin" }
    val roleLabel: String = if (isAdmin) "PSBill Admin" else "PSBill Agent"
    val businessId: String = business.optString("id")
    val businessName: String = business.optString("business_name").ifBlank { business.optString("name") }
    val isSignupAccount: Boolean = json.optBoolean("is_signup_account", false)

    fun can(what: String): Boolean = json.optJSONObject("can")?.optBoolean(what, isAdmin) ?: isAdmin
}

val LocalPsProfile = compositionLocalOf<PsProfile?> { null }
val LocalPsApi = compositionLocalOf<PsApi?> { null }

/** Shows the content only to PSBill Admins (owner); agents see `otherwise` (nothing by default). */
@Composable
fun RequireOwner(otherwise: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    if (LocalPsProfile.current?.isAdmin == true) content() else otherwise()
}

/** Tablet / landscape / TV width. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

@Composable
fun isExpanded(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/** Columns for a grid of cards: 1 on phones, 2 on tablets, 3 on big screens. */
@Composable
fun gridColumns(): Int = LocalConfiguration.current.screenWidthDp.let { if (it >= 1100) 3 else if (it >= 600) 2 else 1 }

/** Header of a page opened from a hub (back arrow, title, optional action). */
@Composable
fun PsSubHeader(title: String, subtitle: String? = null, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PSBillThemeColors.TextPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        }
        action?.invoke()
    }
}

/** Horizontal choice chips that scroll on small phones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}

/**
 * A form: a bottom sheet on phones, a centred dialog on tablets. The body scrolls, so long forms
 * stay reachable above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsFormSheet(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", color = PSBillThemeColors.TextSecondary) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onConfirm, enabled = confirmEnabled && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = if (danger) PsTone.Bad else PSBillThemeColors.PrimaryAccent),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(confirmText)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (isWide()) {
        Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f).heightIn(max = 720.dp).imePadding(),
                shape = RoundedCornerShape(20.dp), color = PSBillThemeColors.Surface,
            ) { Box(Modifier.padding(top = 16.dp)) { body() } }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { if (!busy) onDismiss() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PSBillThemeColors.Surface,
        ) { Box(Modifier.imePadding()) { body() } }
    }
}

@Composable
fun PsConfirm(title: String, text: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = PSBillThemeColors.TextPrimary) },
        text = { Text(text, color = PSBillThemeColors.TextSecondary) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = if (danger) PsTone.Bad else PSBillThemeColors.PrimaryAccent)) {
                Text(confirmText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PSBillThemeColors.TextSecondary) } },
        containerColor = PSBillThemeColors.Surface,
    )
}

/** A row in a hub: icon tile, title, subtitle, chevron. */
@Composable
fun PsHubRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, tone: Color = PSBillThemeColors.PrimaryAccent, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PSBillThemeColors.Surface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tone)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = PSBillThemeColors.TextSecondary)
    }
}

/** Loading / error / empty wrapper used by every list. */
@Composable
fun PsLoadState(loading: Boolean, error: String?, empty: Boolean, emptyText: String, onRetry: () -> Unit, content: @Composable () -> Unit) {
    when {
        loading && empty -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PSBillThemeColors.PrimaryAccent)
        }
        error != null && empty -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error, color = PSBillThemeColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
        empty -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = PSBillThemeColors.TextSecondary)
        }
        else -> content()
    }
}

/** Opens the share sheet (WhatsApp, email, Drive...) for a file in the app cache. */
fun shareFile(context: Context, file: File, mime: String, title: String = "Share") {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Opens a file in another app (PDF viewer, Sheets...), else the share sheet. */
fun openFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(view) }.onFailure { shareFile(context, file, mime) }
}

fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback).ifBlank { fallback }

fun objects(arr: org.json.JSONArray?): List<JSONObject> =
    arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
