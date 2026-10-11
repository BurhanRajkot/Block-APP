package com.blockapp.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The platform sans throughout. No bundled font file and no network download (invariant 8
// forbids INTERNET). The character comes from weight and tracking instead: heavy, tightly
// tracked numerals for the figures this app is really about, and quiet small text around them.
private val Base = Typography()

/** Tabular figures, so a ticking countdown or a changing total doesn't jitter sideways. */
private const val TABULAR = "tnum"

internal val AppTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(
        fontWeight = FontWeight.Black,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-2.5).sp,
        fontFeatureSettings = TABULAR,
    ),
    displayMedium = Base.displayMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.8).sp,
        fontFeatureSettings = TABULAR,
    ),
    displaySmall = Base.displaySmall.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.2).sp,
        fontFeatureSettings = TABULAR,
    ),
    headlineLarge = Base.headlineLarge.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = Base.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = Base.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = Base.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = Base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = Base.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = Base.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = Base.bodyMedium.copy(letterSpacing = 0.sp),
    labelLarge = Base.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = Base.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR,
    ),
    labelSmall = Base.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    ),
)

