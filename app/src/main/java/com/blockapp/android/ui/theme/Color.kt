package com.blockapp.android.ui.theme

import androidx.compose.ui.graphics.Color

// Graphite with one electric accent. Dark-only on purpose: this app is opened at night, mid-
// impulse, and it shares its look with the block wall (BlockOverlayActivity), which has to be
// dark anyway. Volt marks the things the user does, Ember marks the reel counter, and nothing
// else on screen is coloured, so both read at a glance.

internal val Ink950 = Color(0xFF0A0B0D) // window background
internal val Ink900 = Color(0xFF111316) // low container
internal val Ink850 = Color(0xFF15181C) // cards
internal val Ink800 = Color(0xFF1C1F24) // raised: dialogs, chips, tracks
internal val Ink700 = Color(0xFF262A31) // outlines
internal val Ink600 = Color(0xFF353A42) // pressed / strong outline

internal val Fog = Color(0xFFF2F3F5)
internal val FogMuted = Color(0xFF8E949D)
internal val FogFaint = Color(0xFF5D636C)

internal val Volt = Color(0xFFD7FF3A)
internal val VoltDeep = Color(0xFF0E1200)
internal val VoltWash = Color(0xFF232B0A)

internal val Ember = Color(0xFFFF5A73)
internal val EmberWash = Color(0xFF331820)

/** Second chart series, only where two quantities share an axis (InsightsScreen). */
internal val Sky = Color(0xFF6CC4FF)
