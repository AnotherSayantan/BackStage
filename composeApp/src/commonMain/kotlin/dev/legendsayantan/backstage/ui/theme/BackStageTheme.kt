package dev.legendsayantan.backstage.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm architectural matte-carbon & anodized brass palette
val BgBase = Color(0xFF0C0C0E)
val BgSurface = Color(0xFF141417)
val BgSurfaceHover = Color(0xFF1E1E23)
val TextMain = Color(0xFFF2EFE9)
val TextMuted = Color(0xFF8E8B82)
val AccentPrimary = Color(0xFFE5A93C)
val AccentSecondary = Color(0xFF6B9E88)
val BorderColor = Color(0xFF26262C)
val DangerColor = Color(0xFFD9534F)
val SuccessColor = Color(0xFF5E9E72)
val WarningColor = Color(0xFFD99B26)

private val BackStageDarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    secondary = AccentSecondary,
    background = BgBase,
    surface = BgSurface,
    surfaceVariant = BgSurfaceHover,
    onPrimary = Color(0xFF12100B),
    onSecondary = Color(0xFF0D1411),
    onBackground = TextMain,
    onSurface = TextMain,
    onSurfaceVariant = TextMuted,
    error = DangerColor,
    outline = BorderColor,
)

@Composable
fun BackStageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BackStageDarkColorScheme,
        content = content,
    )
}

