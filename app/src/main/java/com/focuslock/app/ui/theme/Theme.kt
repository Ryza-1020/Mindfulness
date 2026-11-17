package com.focuslock.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.focuslock.app.data.ThemeMode

/** Calm, low-contrast-but-legible palette. */
private val Light = lightColorScheme(
    primary = Color(0xFF3A6B73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE7EB),
    onPrimaryContainer = Color(0xFF0E2A2F),
    secondary = Color(0xFF5C6B73),
    secondaryContainer = Color(0xFFDDE4E6),
    onSecondaryContainer = Color(0xFF1A2428),
    background = Color(0xFFF7F8F7),
    surface = Color(0xFFF7F8F7),
    surfaceVariant = Color(0xFFE3E8E8),
    surfaceContainer = Color(0xFFEDF1F1),
    surfaceContainerHigh = Color(0xFFE6EBEB),
    outline = Color(0xFF8A9597),
    tertiaryContainer = Color(0xFFFBE9D0),
    onTertiaryContainer = Color(0xFF3B2A12),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FCBD4),
    onPrimary = Color(0xFF00363C),
    primaryContainer = Color(0xFF1F4D54),
    onPrimaryContainer = Color(0xFFCDE7EB),
    secondary = Color(0xFFB4C3C8),
    secondaryContainer = Color(0xFF2E3A3D),
    onSecondaryContainer = Color(0xFFDDE4E6),
    background = Color(0xFF111415),
    surface = Color(0xFF111415),
    surfaceVariant = Color(0xFF2A3132),
    surfaceContainer = Color(0xFF1A1F20),
    surfaceContainerHigh = Color(0xFF222829),
    outline = Color(0xFF6F7A7C),
    tertiaryContainer = Color(0xFF4A3A22),
    onTertiaryContainer = Color(0xFFFBE9D0),
)

/** Colors of the work-session screens: true black for OLED battery saving, soft grey text. */
object SessionColors {
    val background = Color(0xFF000000)
    val text = Color(0xFFD5DCDD)
    val dimText = Color(0xFF7C8789)
    val accent = Color(0xFF6FA9B2)
    val track = Color(0xFF1C2324)
}

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

val TimerTextStyle = TextStyle(
    fontSize = 72.sp,
    fontWeight = FontWeight.Light,
    fontFeatureSettings = "tnum",
    letterSpacing = 1.sp,
)

@Composable
fun FocusLockTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
