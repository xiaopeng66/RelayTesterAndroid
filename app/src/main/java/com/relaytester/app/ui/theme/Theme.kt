package com.relaytester.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

private val LightScheme = lightColorScheme(
    primary = Color(0xFF155EEF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE9FF),
    onPrimaryContainer = Color(0xFF0B3B93),
    secondary = Color(0xFF475467),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0F8),
    onSecondaryContainer = Color(0xFF1D2939),
    tertiary = Color(0xFF078A78),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4F6EE),
    onTertiaryContainer = Color(0xFF005143),
    error = Color(0xFFB42318),
    onError = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFEAF0F8),
    onSurfaceVariant = Color(0xFF475467),
    outline = Color(0xFF98A2B3),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4C7FF),
    onPrimary = Color(0xFF002D6F),
    primaryContainer = Color(0xFF1B4A9B),
    onPrimaryContainer = Color(0xFFDDE9FF),
    secondary = Color(0xFFC7D0DE),
    onSecondary = Color(0xFF29313D),
    secondaryContainer = Color(0xFF293544),
    onSecondaryContainer = Color(0xFFEAF0F8),
    tertiary = Color(0xFF65D8C4),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005143),
    onTertiaryContainer = Color(0xFFD4F6EE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFF3F5F8),
    surface = Color(0xFF111927),
    onSurface = Color(0xFFF3F5F8),
    surfaceVariant = Color(0xFF202B3A),
    onSurfaceVariant = Color(0xFFC7D0DE),
    outline = Color(0xFF8D99AA),
)

@Composable
fun RelayTesterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = RelayTesterTypography,
        content = content,
    )
}
