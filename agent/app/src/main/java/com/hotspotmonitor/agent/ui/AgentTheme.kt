package com.hotspotmonitor.agent.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Good / warning / bad colours for status values, tuned for contrast in each theme. */
@Immutable
data class StatusColors(val good: Color, val warn: Color, val bad: Color, val muted: Color)

private val LightStatus = StatusColors(
    good = Color(0xFF0B6B2B),
    warn = Color(0xFF8A5300),
    bad = Color(0xFFB00020),
    muted = Color(0xFF4A4F57),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF7BE495),
    warn = Color(0xFFFFC857),
    bad = Color(0xFFFF8A80),
    muted = Color(0xFFB8BEC8),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

// High contrast: the local UI is mostly read outdoors, often in sunlight, on a phone on a pole.
private val LightColors = lightColorScheme(
    primary = Color(0xFF003F73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF2E4A62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4F2),
    onSecondaryContainer = Color(0xFF0B1D2C),
    background = Color.White,
    onBackground = Color(0xFF0D0F12),
    surface = Color.White,
    onSurface = Color(0xFF0D0F12),
    surfaceVariant = Color(0xFFE9EDF2),
    onSurfaceVariant = Color(0xFF262B33),
    outline = Color(0xFF5B616B),
    error = Color(0xFFB00020),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF00487F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFB7CBDF),
    onSecondary = Color(0xFF1A2F40),
    background = Color.Black,
    onBackground = Color(0xFFF2F4F7),
    surface = Color.Black,
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1E232A),
    onSurfaceVariant = Color(0xFFDDE2EA),
    outline = Color(0xFF9AA1AC),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0008),
)

private val BaseTypography = Typography()

private val AgentTypography = BaseTypography.copy(
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
)

/** App theme: high-contrast colours and larger type for outdoor use. Follows the system dark mode. */
@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AgentTypography,
            content = content,
        )
    }
}
