package gpes.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5CAD), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FA), onPrimaryContainer = Color(0xFF0A2E52),
    secondary = Color(0xFF4F6071), secondaryContainer = Color(0xFFD9E3EF), onSecondaryContainer = Color(0xFF1A2733),
    background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFEAEFF5), surfaceContainerHigh = Color(0xFFE2E8F0),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC7F5), onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF1D4A78), onPrimaryContainer = Color(0xFFD6E6FA),
    secondary = Color(0xFFB7C8DA), secondaryContainer = Color(0xFF384858), onSecondaryContainer = Color(0xFFD9E3EF),
    background = Color(0xFF101418), surface = Color(0xFF101418),
    surfaceContainer = Color(0xFF1B2128), surfaceContainerHigh = Color(0xFF252C34),
    error = Color(0xFFF2B8B5),
)

/** Meaning colors for trust and evidence; the same everywhere, never used for decoration. */
data class StatusColors(val good: Color, val warn: Color, val bad: Color, val idle: Color)

private val LightStatus = StatusColors(Color(0xFF2E7D32), Color(0xFFB26A00), Color(0xFFC62828), Color(0xFF6B7783))
private val DarkStatus = StatusColors(Color(0xFF7BD389), Color(0xFFFFC55C), Color(0xFFFF8A80), Color(0xFF9AA6B2))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun GpesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
