package com.blockapp.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = NeutralLightSurface,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Slate40,
    onSecondary = NeutralLightSurface,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate10,
    tertiary = Amber40,
    onTertiary = NeutralLightSurface,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    error = Red40,
    onError = NeutralLightSurface,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = NeutralLightBackground,
    onBackground = NeutralLightOnSurface,
    surface = NeutralLightSurface,
    onSurface = NeutralLightOnSurface,
    surfaceVariant = NeutralLightSurfaceVariant,
    onSurfaceVariant = NeutralLightOnSurfaceVariant,
    outline = NeutralLightOutline,
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo20,
    onPrimaryContainer = Indigo90,
    secondary = Slate80,
    onSecondary = Slate20,
    secondaryContainer = Slate20,
    onSecondaryContainer = Slate90,
    tertiary = Amber80,
    onTertiary = Amber10,
    tertiaryContainer = Amber40,
    onTertiaryContainer = Amber90,
    error = Red80,
    onError = Red10,
    errorContainer = Red40,
    onErrorContainer = Red90,
    background = NeutralDarkBackground,
    onBackground = NeutralDarkOnSurface,
    surface = NeutralDarkSurface,
    onSurface = NeutralDarkOnSurface,
    surfaceVariant = NeutralDarkSurfaceVariant,
    onSurfaceVariant = NeutralDarkOnSurfaceVariant,
    outline = NeutralDarkOutline,
)

@Composable
fun BlockAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
