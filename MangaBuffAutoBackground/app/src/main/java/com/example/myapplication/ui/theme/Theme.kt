package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LiquidGlassColorScheme = darkColorScheme(
    primary = Color(0xFF52E5FF),
    onPrimary = Color(0xFF07151B),
    primaryContainer = Color(0xFF174B60),
    onPrimaryContainer = Color(0xFFD8F8FF),
    secondary = Color(0xFFB66DFF),
    onSecondary = Color(0xFF180B25),
    secondaryContainer = Color(0xFF45305F),
    onSecondaryContainer = Color(0xFFF2E4FF),
    tertiary = Color(0xFF8F9DFF),
    background = Color(0xFF151820),
    onBackground = Color(0xFFF4F6FF),
    surface = Color(0xE61B1E29),
    onSurface = Color(0xFFF4F6FF),
    surfaceVariant = Color(0xFF2A2E3B),
    onSurfaceVariant = Color(0xFFD0D6E5),
    outline = Color(0xFF8F9AAF),
    error = Color(0xFFFF7188),
    errorContainer = Color(0xFF542531),
    onErrorContainer = Color(0xFFFFDCE2)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LiquidGlassColorScheme,
        typography = Typography,
        content = content
    )
}
