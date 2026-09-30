package com.blockapp.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Serif on display/title, default sans on body. No bundled font file and no network download
// (invariant 8 forbids INTERNET); FontFamily.Serif is the platform's letterpress face and is
// what keeps this from reading as default Material Roboto-on-indigo.
private val Display = FontFamily.Serif
private val Base = Typography()

internal val AppTypography = Base.copy(
    displaySmall = Base.displaySmall.copy(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = Base.headlineSmall.copy(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = Base.titleLarge.copy(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = Base.titleMedium.copy(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = Base.titleSmall.copy(
        fontFamily = Display,
        fontWeight = FontWeight.Medium,
    ),
    labelLarge = Base.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
    ),
    labelMedium = Base.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    ),
)
