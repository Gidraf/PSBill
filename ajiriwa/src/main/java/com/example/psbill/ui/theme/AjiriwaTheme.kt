package com.example.psbill.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Obsidian Kinetic Design System
 * Enterprise Dark Obsidian aesthetic: Deep #0b1326 canvas, glassmorphic slate panels (#131b2e / #171f33),
 * 1px slate borders (#334155), neon violet (#8B5CF6 / #d0bcff) & emerald green (#10B981) status accents.
 */
object AjiriwaColors {
    val Canvas = Color(0xFF0B1326)            // Deep Obsidian background (#0b1326 / #0F172A)
    val Surface = Color(0xFF131B2E)           // Glass card container low
    val SurfaceAlt = Color(0xFF171F33)        // Raised surface container
    val SurfaceHigh = Color(0xFF222A3D)       // Surface container high
    val SurfaceHighest = Color(0xFF2D3449)    // Surface container highest
    val Border = Color(0xFF334155)           // 1px Slate glass border stroke (#334155)
    val BorderVariant = Color(0xFF494454)    // Hairline outline variant (#494454)
    val Primary = Color(0xFFD0BCFF)          // Primary lavender tone (#d0bcff)
    val PrimaryAccent = Color(0xFF8B5CF6)    // Neon Violet primary action (#8B5CF6)
    val PrimaryDim = Color(0xFF7C3AED)       // Deep Violet hover/pressed (#7C3AED)
    val PrimaryContainer = Color(0xFFA078FF) // Primary container (#a078ff)
    val OnPrimaryContainer = Color(0xFF340080)// On primary container (#340080)
    val Secondary = Color(0xFFC0C1FF)        // Secondary lavender indigo (#c0c1ff)
    val SecondaryContainer = Color(0xFF3131C0)// Secondary container (#3131c0)
    val OnSecondaryContainer = Color(0xFFB0B2FF)// On secondary container (#b0b2ff)
    val Tertiary = Color(0xFFB9C7E0)         // Tertiary slate blue (#b9c7e0)
    val Success = Color(0xFF10B981)          // Emerald Green active/online status (#10b981)
    val Warning = Color(0xFFF59E0B)          // Amber Gold warning/pending (#f59e0b)
    val Danger = Color(0xFFEF4444)           // Crimson Red error/locked (#ef4444)
    val ErrorContainer = Color(0xFF93000A)   // Error container background (#93000a)
    val OnErrorContainer = Color(0xFFFFDAD6) // On error text (#ffdad6)
    val TextPrimary = Color(0xFFDAE2FD)      // On-surface primary text (#dae2fd)
    val TextSecondary = Color(0xFFCBC3D7)    // On-surface variant text (#cbc3d7)
    val TextMuted = Color(0xFF958EA0)        // Muted outline text (#958ea0)
    val OnPrimary = Color(0xFF3C0091)        // Text on primary (#3c0091)
}

private val DarkScheme = darkColorScheme(
    primary = AjiriwaColors.Primary,
    onPrimary = AjiriwaColors.OnPrimary,
    primaryContainer = AjiriwaColors.PrimaryContainer,
    onPrimaryContainer = AjiriwaColors.OnPrimaryContainer,
    secondary = AjiriwaColors.Secondary,
    secondaryContainer = AjiriwaColors.SecondaryContainer,
    onSecondaryContainer = AjiriwaColors.OnSecondaryContainer,
    tertiary = AjiriwaColors.Tertiary,
    background = AjiriwaColors.Canvas,
    onBackground = AjiriwaColors.TextPrimary,
    surface = AjiriwaColors.Surface,
    onSurface = AjiriwaColors.TextPrimary,
    surfaceVariant = AjiriwaColors.SurfaceAlt,
    onSurfaceVariant = AjiriwaColors.TextSecondary,
    error = AjiriwaColors.Danger,
    errorContainer = AjiriwaColors.ErrorContainer,
    onErrorContainer = AjiriwaColors.OnErrorContainer,
    outline = AjiriwaColors.Border,
    outlineVariant = AjiriwaColors.BorderVariant,
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp, letterSpacing = (-0.02).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
)

@Composable
fun AjiriwaTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val systemDark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}
