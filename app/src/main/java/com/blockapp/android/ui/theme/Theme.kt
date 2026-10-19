package com.blockapp.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// surface == background so Material's default top bars sit flush with the page instead of
// drawing a band. Cards use the surfaceContainer tiers, and the older screens that still draw
// cards in surfaceVariant get the same card colour.
private val Colors = darkColorScheme(
    primary = Lime,
    onPrimary = LimeInk,
    primaryContainer = LimeWash,
    onPrimaryContainer = Lime,
    inversePrimary = LimeInk,
    secondary = BoneMuted,
    onSecondary = Ink950,
    secondaryContainer = Ink800,
    onSecondaryContainer = Bone,
    tertiary = Ember,
    onTertiary = Ink950,
    tertiaryContainer = EmberWash,
    onTertiaryContainer = Ember,
    error = StatusColors.Danger,
    onError = Ink950,
    errorContainer = Color(0xFF3A1717),
    onErrorContainer = StatusColors.Danger,
    background = Ink950,
    onBackground = Bone,
    surface = Ink950,
    onSurface = Bone,
    surfaceVariant = Ink850,
    onSurfaceVariant = BoneMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Bone,
    inverseOnSurface = Ink950,
    outline = Ink700,
    outlineVariant = Ink800,
    scrim = Color.Black,
    surfaceBright = Ink800,
    surfaceDim = Ink950,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink900,
    surfaceContainer = Ink850,
    surfaceContainerHigh = Ink800,
    surfaceContainerHighest = Ink800,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The [darkTheme] parameter is kept so call sites (BlockOverlayActivity passes `true`) don't
 * have to change, but there is one palette: see Color.kt for why the app is dark-only.
 */
@Composable
fun BlockAppTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = Colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
