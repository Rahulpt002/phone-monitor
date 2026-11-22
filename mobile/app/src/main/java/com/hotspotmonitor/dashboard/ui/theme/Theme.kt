package com.hotspotmonitor.dashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.agent.protocol.Grade

/** Semantic colours beyond Material's roles (same palette as the web Dashboard, index.css). */
@Immutable
data class HsmColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val fg: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
    val okSoft: Color,
    val warnSoft: Color,
    val dangerSoft: Color,
    val infoSoft: Color,
    val gradeExcellent: Color,
    val gradeGood: Color,
    val gradeFair: Color,
    val gradePoor: Color,
    val dark: Boolean,
) {
    fun grade(g: Grade?): Color = when (g) {
        Grade.EXCELLENT -> gradeExcellent
        Grade.GOOD -> gradeGood
        Grade.FAIR -> gradeFair
        Grade.POOR -> gradePoor
        null -> muted
    }
}

val DarkHsm = HsmColors(
    bg = Color(0xFF0E1014), surface = Color(0xFF161A21), surface2 = Color(0xFF1D222B), surface3 = Color(0xFF282E39),
    border = Color(0xFF262C36), fg = Color(0xFFE8EDF3), muted = Color(0xFF9AA4B2), faint = Color(0xFF68717E),
    accent = Color(0xFF5AA9FF), onAccent = Color(0xFF06121F), ok = Color(0xFF34D399), warn = Color(0xFFFBBF24),
    danger = Color(0xFFF87171), info = Color(0xFF7CC4FF), okSoft = Color(0x2434D399), warnSoft = Color(0x24FBBF24),
    dangerSoft = Color(0x29F87171), infoSoft = Color(0x247CC4FF), gradeExcellent = Color(0xFF34D399),
    gradeGood = Color(0xFFA3E635), gradeFair = Color(0xFFFBBF24), gradePoor = Color(0xFFF87171), dark = true,
)

val LightHsm = HsmColors(
    bg = Color(0xFFF4F6FA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF0F3F7), surface3 = Color(0xFFE3E8EF),
    border = Color(0xFFDCE2EA), fg = Color(0xFF111827), muted = Color(0xFF5B6575), faint = Color(0xFF8A94A3),
    accent = Color(0xFF0B63CE), onAccent = Color(0xFFFFFFFF), ok = Color(0xFF047857), warn = Color(0xFFA16207),
    danger = Color(0xFFB91C1C), info = Color(0xFF0B63CE), okSoft = Color(0x1A047857), warnSoft = Color(0x24CA8A04),
    dangerSoft = Color(0x1AB91C1C), infoSoft = Color(0x1A0B63CE), gradeExcellent = Color(0xFF047857),
    gradeGood = Color(0xFF4D7C0F), gradeFair = Color(0xFFA16207), gradePoor = Color(0xFFB91C1C), dark = false,
)

val LocalHsm = staticCompositionLocalOf { DarkHsm }

/** Shortcut: `hsm.ok`, `hsm.muted`, ... */
val hsm: HsmColors
    @Composable get() = LocalHsm.current

enum class ThemeMode { SYSTEM, DARK, LIGHT }

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.6.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun HsmTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val c = if (dark) DarkHsm else LightHsm
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.infoSoft, onPrimaryContainer = c.fg,
            secondary = c.accent, onSecondary = c.onAccent, secondaryContainer = c.surface3, onSecondaryContainer = c.fg,
            background = c.bg, onBackground = c.fg, surface = c.surface, onSurface = c.fg, surfaceVariant = c.surface2,
            onSurfaceVariant = c.muted, surfaceContainer = c.surface, surfaceContainerHigh = c.surface2,
            surfaceContainerHighest = c.surface3, surfaceContainerLow = c.surface, surfaceContainerLowest = c.bg,
            outline = c.border, outlineVariant = c.border, error = c.danger, onError = Color(0xFF1A0606),
            errorContainer = c.dangerSoft, onErrorContainer = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.infoSoft, onPrimaryContainer = c.fg,
            secondary = c.accent, onSecondary = c.onAccent, secondaryContainer = c.surface3, onSecondaryContainer = c.fg,
            background = c.bg, onBackground = c.fg, surface = c.surface, onSurface = c.fg, surfaceVariant = c.surface2,
            onSurfaceVariant = c.muted, surfaceContainer = c.surface, surfaceContainerHigh = c.surface2,
            surfaceContainerHighest = c.surface3, surfaceContainerLow = c.surface, surfaceContainerLowest = c.bg,
            outline = c.border, outlineVariant = c.border, error = c.danger, onError = Color.White,
            errorContainer = c.dangerSoft, onErrorContainer = c.danger,
        )
    }
    CompositionLocalProvider(LocalHsm provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
