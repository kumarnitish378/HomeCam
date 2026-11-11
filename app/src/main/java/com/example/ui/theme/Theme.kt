package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * One fixed light scheme on purpose: the status colours (teal / red / amber) mean the same
 * thing on every phone, so dynamic wallpaper colours are not used.
 */
private val HomeCamColors = lightColorScheme(
    primary = LiveTeal,
    onPrimary = Color.White,
    primaryContainer = LiveTealTint,
    onPrimaryContainer = LiveTealDeep,
    secondary = InkNavy,
    onSecondary = Color.White,
    secondaryContainer = Mist,
    onSecondaryContainer = InkNavy,
    tertiary = AlertAmber,
    onTertiary = InkNavy,
    tertiaryContainer = AlertAmberTint,
    onTertiaryContainer = AlertAmberDeep,
    error = RecordRed,
    onError = Color.White,
    errorContainer = RecordRedTint,
    onErrorContainer = Color(0xFF8C1D1F),
    background = Porcelain,
    onBackground = InkNavy,
    surface = Surface,
    onSurface = InkNavy,
    surfaceVariant = Porcelain,
    onSurfaceVariant = Slate,
    surfaceContainerLowest = Surface,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = Surface,
    surfaceContainerHighest = Porcelain,
    outline = Pebble,
    outlineVariant = Mist
)

/** Big surfaces 20dp, controls 12dp, small pills 8dp. */
private val HomeCamShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HomeCamColors,
        typography = Typography,
        shapes = HomeCamShapes,
        content = content
    )
}
