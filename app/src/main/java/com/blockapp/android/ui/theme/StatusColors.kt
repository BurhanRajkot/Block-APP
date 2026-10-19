package com.blockapp.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic status colours shared across screens that show granted / needs-attention / heads-up
 * state. Kept as plain literals rather than derived from the colour scheme — "granted" is a
 * status, not a brand colour. Tuned for the dark-only theme: each is used both as text and as a
 * 10-15% wash behind that text, and has to stay legible on Ink850 in both roles. Success is a
 * green that is clearly not Lime, so "granted" never reads as a button.
 */
object StatusColors {
    val Success = Color(0xFF5BD68A)
    val Danger = Color(0xFFFF5D5D)
    val Warning = Color(0xFFF2C14E)
}
