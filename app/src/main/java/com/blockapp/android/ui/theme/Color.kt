package com.blockapp.android.ui.theme

import androidx.compose.ui.graphics.Color

// Graphite with one sharp accent. Dark-only on purpose: this app is opened at night, mid-impulse,
// and it shares its look with the block wall (BlockOverlayActivity), which has to be dark anyway.
//
// The greys are neutral, with only a faint olive cast toward the accent. The indigo they replaced,
// plus a violet-to-coral gradient on every hero, read as a generated template rather than a tool,
// so there are no gradients and no glows any more. Lime marks the things the user does, Ember
// marks the reel counter only, and a figure gets its emphasis from size and weight, not from
// colour.

internal val Ink950 = Color(0xFF0E0F0C) // window background
internal val Ink900 = Color(0xFF141512) // low container, nav bar
internal val Ink850 = Color(0xFF1A1C17) // cards
internal val Ink800 = Color(0xFF23261F) // raised: dialogs, chips, tracks
internal val Ink700 = Color(0xFF2E322A) // outlines
internal val Ink600 = Color(0xFF3C4137) // pressed / strong outline

internal val Bone = Color(0xFFF1F2EC)
internal val BoneMuted = Color(0xFF9A9D93)
internal val BoneFaint = Color(0xFF666A60)

internal val Lime = Color(0xFFC6F432)
/** Text and icons on a Lime fill. Near-black with the accent's hue, so it doesn't read grey. */
internal val LimeInk = Color(0xFF141A00)
internal val LimeWash = Color(0xFF263015)

internal val Ember = Color(0xFFFF7A3D)
internal val EmberWash = Color(0xFF3A2216)

/** Second chart series, only where two quantities share an axis. */
internal val Steel = Color(0xFF8FB3C9)
