package com.blockapp.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Rust,
    onPrimary = PaperRaised,
    primaryContainer = RustWash,
    onPrimaryContainer = RustDeep,
    secondary = InkMuted,
    onSecondary = PaperRaised,
    secondaryContainer = PaperInset,
    onSecondaryContainer = Ink,
    tertiary = Rust,
    onTertiary = PaperRaised,
    tertiaryContainer = RustWash,
    onTertiaryContainer = RustDeep,
    error = Color(0xFF9B1C1C),
    onError = PaperRaised,
    errorContainer = Color(0xFFF4D6D2),
    onErrorContainer = Color(0xFF3B0A0A),
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperInset,
    onSurfaceVariant = InkMuted,
    outline = Rule,
)

private val DarkColors = darkColorScheme(
    primary = RustOnDark,
    onPrimary = RustDeep,
    primaryContainer = RustOnDarkContainer,
    onPrimaryContainer = Cream,
    secondary = CreamMuted,
    onSecondary = Night,
    secondaryContainer = NightInset,
    onSecondaryContainer = Cream,
    tertiary = RustOnDark,
    onTertiary = RustDeep,
    tertiaryContainer = RustOnDarkContainer,
    onTertiaryContainer = Cream,
    error = Color(0xFFE8A39A),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF6B1F1A),
    onErrorContainer = Color(0xFFF4D6D2),
    background = Night,
    onBackground = Cream,
    surface = NightRaised,
    onSurface = Cream,
    surfaceVariant = NightInset,
    onSurfaceVariant = CreamMuted,
    outline = NightRule,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

@Composable
fun BlockAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
