package com.keyspaceapps.goodtv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val GoodTvColors = darkColorScheme(
    primary = Color(0xFF22C55E),
    onPrimary = Color(0xFF052E16),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF101828),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1D2939),
    onSurfaceVariant = Color(0xFFCBD5E1),
)

@Composable
fun GoodTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GoodTvColors) {
        // Plain Text outside a Surface/ListItem would otherwise default to black on our black background.
        CompositionLocalProvider(LocalContentColor provides GoodTvColors.onBackground, content = content)
    }
}
