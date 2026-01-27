package com.mobileai.notes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF111111),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEAEAEA),
    onPrimary = Color(0xFF111111),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEAEAEA),
)

@Composable
fun MobileAINotesTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

