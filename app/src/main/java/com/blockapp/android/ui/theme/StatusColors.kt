package com.blockapp.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic status colours shared across screens that show granted / needs-attention / heads-up
 * state. Kept as plain literals rather than derived from the colour scheme — "granted" is a
 * status, not a brand colour, and must read the same regardless of theme.
 */
object StatusColors {
    val Success = Color(0xFF3D6B3A)
    val Danger = Color(0xFF9B1C1C)
    val Warning = Color(0xFF8A5A12)
}
