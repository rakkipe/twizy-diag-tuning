package be.technop.openrlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFFFCC00),       // Renault-geel accent
    onPrimary = Color(0xFF1A1A1A),
    secondary = Color(0xFF7FD1FF),
    background = Color(0xFF101214),
    surface = Color(0xFF181B1E),
    error = Color(0xFFFF6B6B),
)
private val Light = lightColorScheme(
    primary = Color(0xFFB58A00),
    secondary = Color(0xFF0066AA),
)

@Composable
fun OpenRLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
