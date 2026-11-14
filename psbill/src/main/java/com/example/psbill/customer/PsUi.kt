package com.example.psbill.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * One dark theme for the whole app. Before this the app had no MaterialTheme, so every
 * text field, dropdown and dialog used the LIGHT defaults (near-black text) on the dark
 * background - the "inputs are dark, not visible" problem. Everything below reads from
 * this scheme, so new screens are readable without per-field colour overrides.
 */
private val PsColorScheme = darkColorScheme(
    primary = PSBillThemeColors.PrimaryAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E1F5E),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = PSBillThemeColors.SecondaryAccent,
    onSecondary = Color(0xFF00281A),
    secondaryContainer = Color(0xFF0F3B2E),
    onSecondaryContainer = Color(0xFFB7F5DA),
    tertiary = PSBillThemeColors.Amber,
    onTertiary = Color(0xFF2B1700),
    background = PSBillThemeColors.Canvas,
    onBackground = PSBillThemeColors.TextPrimary,
    surface = PSBillThemeColors.Surface,
    onSurface = PSBillThemeColors.TextPrimary,
    surfaceVariant = PSBillThemeColors.SurfaceAlt,
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceContainerLowest = PSBillThemeColors.Canvas,
    surfaceContainerLow = PSBillThemeColors.Surface,
    surfaceContainer = PSBillThemeColors.Surface,
    surfaceContainerHigh = Color(0xFF1C2640),
    surfaceContainerHighest = Color(0xFF243049),
    outline = Color(0xFF475569),
    outlineVariant = PSBillThemeColors.Border,
    error = PSBillThemeColors.Crimson,
    onError = Color.White,
    inverseSurface = PSBillThemeColors.TextPrimary,
    inverseOnSurface = PSBillThemeColors.Canvas,
)

@Composable
fun PSBillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PsColorScheme, content = content)
}

/** Status colours used by pills and row accents. */
object PsTone {
    val Good = PSBillThemeColors.SecondaryAccent
    val Busy = PSBillThemeColors.PrimaryAccent
    val Warn = PSBillThemeColors.Amber
    val Bad = PSBillThemeColors.Crimson
    val Idle = PSBillThemeColors.TextSecondary
}

@Composable
fun PsPill(text: String, tone: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = tone, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** Page heading: one line title + optional one line of help. */
@Composable
fun PsPageHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)
            }
        }
        trailing?.invoke()
    }
}

/** Small uppercase label above a group of rows. */
@Composable
fun PsSectionLabel(text: String, count: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text.uppercase(), color = PSBillThemeColors.TextSecondary,
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.2.sp
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count", color = PSBillThemeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

/** A number tile for the top-of-page summary. */
@Composable
fun PsStat(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PSBillThemeColors.Surface)
            .border(1.dp, PSBillThemeColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(value, color = tone, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1)
        Text(label, color = PSBillThemeColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

/** Plain surface block (replacement for the old heavy title cards). */
@Composable
fun PsSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PSBillThemeColors.Surface)
            .border(1.dp, PSBillThemeColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

/** One tappable row: coloured dot, title, one detail line, right-hand value, chevron. */
@Composable
fun PsListRow(
    title: String,
    detail: String,
    tone: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailingValue: String? = null,
    trailingTone: Color = PSBillThemeColors.TextPrimary,
    pill: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PSBillThemeColors.Surface)
            .border(1.dp, PSBillThemeColors.Border, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
            else Box(Modifier.size(10.dp).clip(CircleShape).background(tone))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (pill != null) {
                    Spacer(Modifier.width(8.dp))
                    PsPill(pill, tone)
                }
            }
            if (detail.isNotBlank()) {
                Text(detail, color = PSBillThemeColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailingValue != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailingValue, color = trailingTone, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = PSBillThemeColors.TextSecondary)
        }
    }
}

/** Centered message for an empty list, with an optional action. */
@Composable
fun PsEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(PSBillThemeColors.SurfaceAlt),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = PSBillThemeColors.TextSecondary, modifier = Modifier.size(28.dp)) }
        Text(title, color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(message, color = PSBillThemeColors.TextSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Screen status -> (label, tone) used everywhere a screen is listed. */
fun screenStatus(node: org.json.JSONObject): Pair<String, Color> {
    val status = node.optString("status", "LOCKED").uppercase()
    val online = !node.has("is_online") || node.optBoolean("is_online", true)
    // the TV reports its HDMI input: "standby"/"unplugged" = the console is off during a session
    val console = node.optString("console_state", "").lowercase()
    val consoleOff = console == "standby" || console == "unplugged"
    return when {
        node.optBoolean("is_asleep", false) -> "ASLEEP" to PsTone.Idle
        !online -> "OFFLINE" to PsTone.Bad
        consoleOff && status in setOf("ACTIVE", "OVERTIME", "PENDING") -> "CONSOLE OFF" to PsTone.Warn
        pendingTopupRequest(node) != null && status in setOf("ACTIVE", "OVERTIME") -> "WANTS TIME" to PsTone.Warn
        status == "OVERTIME" -> "OVERTIME" to PsTone.Warn
        status == "ACTIVE" || status == "PENDING" -> "PLAYING" to PsTone.Busy
        node.optBoolean("has_ended_session", false) -> "TO PAY" to PsTone.Warn
        else -> "FREE" to PsTone.Good
    }
}

/** The player's top-up request from the TV, while it waits for staff (10 minutes). */
fun pendingTopupRequest(node: org.json.JSONObject): org.json.JSONObject? {
    val r = node.optJSONObject("topup_request") ?: return null
    if (r.optString("status") != "PENDING") return null
    val at = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(r.optString("at").take(19))?.time
    }.getOrNull() ?: return null
    return if (System.currentTimeMillis() - at < 10 * 60_000L) r else null
}

/** A start the player asked for on the TV while others are queued (another game, or before their
 *  turn was called), while it waits for staff (10 minutes). */
fun pendingGameRequest(node: org.json.JSONObject): org.json.JSONObject? {
    val r = node.optJSONObject("game_request") ?: return null
    if (r.optString("status") != "PENDING") return null
    val at = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(r.optString("at").take(19))?.time
    }.getOrNull() ?: return null
    return if (System.currentTimeMillis() - at < 10 * 60_000L) r else null
}

fun nodeName(node: org.json.JSONObject): String =
    node.optString("device_name").takeIf { it.isNotBlank() && it != "null" }
        ?: node.optString("name").takeIf { it.isNotBlank() && it != "null" }
        ?: "TV screen"

fun nodeGame(node: org.json.JSONObject): String =
    node.optString("current_game").takeIf { it.isNotBlank() && it != "null" }.orEmpty()

fun kes(amount: Double): String =
    if (amount % 1.0 == 0.0) "KSh ${amount.toLong()}" else "KSh ${String.format(java.util.Locale.US, "%.2f", amount)}"
