package com.fotoframe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FrameColors = darkColorScheme(
    primary = Color(0xFF3D6FE0),
    onPrimary = Color.White,
    background = Color(0xFF101214),
    onBackground = Color.White,
    surface = Color(0xFF1B1E22),
    onSurface = Color.White
)

/**
 * Рамка всегда тёмная: светлый интерфейс на большом экране в комнате
 * слепит и отвлекает от самих фотографий.
 */
@Composable
fun FotoFrameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FrameColors, content = content)
}
