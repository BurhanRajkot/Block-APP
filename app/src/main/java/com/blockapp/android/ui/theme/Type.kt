package com.blockapp.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Starts from Material3's default type scale and only tightens the sizes this app actually
// leans on for emphasis — countdowns, headline totals, section labels — rather than replacing
// the whole scale. No custom font family: adding one would be a new dependency for a single-user
// tool that doesn't need one (see CLAUDE.md's dependency policy).
private val Base = Typography()

internal val AppTypography = Base.copy(
    displaySmall = Base.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = Base.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = Base.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(letterSpacing = 0.4.sp),
)
