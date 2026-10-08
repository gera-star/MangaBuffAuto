package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MangaBuffDarkColorScheme = darkColorScheme(
    primary = MbOrange,
    onPrimary = Color.White,
    primaryContainer = MbOrangeContainer,
    onPrimaryContainer = Color.White,
    secondary = MbBlue,
    onSecondary = Color.White,
    secondaryContainer = MbNavySurfaceVariant,
    onSecondaryContainer = MbWhite,
    tertiary = MbOrangeLight,
    onTertiary = Color.Black,
    background = MbNavy,
    onBackground = MbWhite,
    surface = MbNavySurface,
    onSurface = MbWhite,
    surfaceVariant = MbNavySurfaceVariant,
    onSurfaceVariant = MbMuted,
    outline = Color(0xFF60717D),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF5B1C1C),
    onErrorContainer = Color.White
)

private val MangaBuffLightColorScheme = lightColorScheme(
    primary = MbOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0CC),
    onPrimaryContainer = Color(0xFF4A1A00),
    secondary = MbBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E5ED),
    onSecondaryContainer = Color(0xFF102531),
    tertiary = Color(0xFFE85D00),
    onTertiary = Color.White,
    background = Color(0xFFF5F7F8),
    onBackground = Color(0xFF101418),
    surface = Color.White,
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE7EDF1),
    onSurfaceVariant = Color(0xFF44525C),
    outline = Color(0xFF71818B),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        MangaBuffDarkColorScheme
    } else {
        MangaBuffLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
