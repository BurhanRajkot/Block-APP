package com.blockapp.android.ui.theme

import androidx.compose.ui.graphics.Color

// Indigo is the brand hue: confident and calm rather than playful, which matches an app whose
// job is to stand between the user and an impulse. Dark scheme values are lifted a step lighter
// than their light-scheme counterpart (standard M3 tonal practice) so text/icon contrast holds
// on a near-black surface without needing separate contrast tuning per screen.
internal val Indigo10 = Color(0xFF0E0B3D)
internal val Indigo20 = Color(0xFF1A1464)
internal val Indigo40 = Color(0xFF4338CA)
internal val Indigo80 = Color(0xFFB4B8FF)
internal val Indigo90 = Color(0xFFE0E1FF)
internal val Indigo95 = Color(0xFFEEEFFF)

internal val Slate10 = Color(0xFF12131C)
internal val Slate20 = Color(0xFF20222F)
internal val Slate40 = Color(0xFF585B72)
internal val Slate80 = Color(0xFFC2C4DD)
internal val Slate90 = Color(0xFFE2E2F5)

internal val Amber40 = Color(0xFF8A5A00)
internal val Amber80 = Color(0xFFFFC876)
internal val Amber90 = Color(0xFFFFE0B0)
internal val Amber10 = Color(0xFF2A1800)

internal val Red40 = Color(0xFFB3261E)
internal val Red80 = Color(0xFFFFB4A9)
internal val Red90 = Color(0xFFFFDAD4)
internal val Red10 = Color(0xFF410001)

// Neutral surfaces. The dark background (#121218) intentionally sits close to
// BlockOverlayActivity's WallBackground (#101014) — that screen is meant to read as a distinct
// "wall", but dark mode elsewhere shouldn't feel like a different app.
internal val NeutralLightBackground = Color(0xFFFAFAFC)
internal val NeutralLightSurface = Color(0xFFFFFFFF)
internal val NeutralLightSurfaceVariant = Color(0xFFEBEBF5)
internal val NeutralLightOutline = Color(0xFFC7C7D9)
internal val NeutralLightOnSurface = Color(0xFF1B1B23)
internal val NeutralLightOnSurfaceVariant = Color(0xFF5E5E6E)

internal val NeutralDarkBackground = Color(0xFF121218)
internal val NeutralDarkSurface = Color(0xFF1B1B23)
internal val NeutralDarkSurfaceVariant = Color(0xFF272733)
internal val NeutralDarkOutline = Color(0xFF48485A)
internal val NeutralDarkOnSurface = Color(0xFFE7E7F0)
internal val NeutralDarkOnSurfaceVariant = Color(0xFFAEAEC4)
