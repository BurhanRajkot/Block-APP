package com.blockapp.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blockapp.android.R

// Two bundled faces, both under the SIL Open Font License, both variable fonts in res/font:
//
// - Bricolage Grotesque for figures and headings. It has an optical-size axis, so the same file
//   draws a tight, high-contrast face at display sizes and a sturdier one at title sizes, and a
//   width axis, used slightly narrowed so big counts fit beside a ring at phone width.
// - Manrope for everything read rather than looked at: body, labels, buttons. Even and open at
//   small sizes on a dark background, which the platform sans was not at 12sp in BoneMuted.
//
// Bundled rather than fetched through a downloadable-fonts provider: that route goes through
// Play services and simply falls back to the platform font when it can't, and invariant 8 rules
// out fetching anything ourselves. Both files together add about 570KB to the APK.
//
// Variable fonts need API 26 for their variation settings, which is this app's minSdk.

/** Tabular figures, so a ticking countdown or a changing total doesn't jitter sideways. */
private const val TABULAR = "tnum"

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, opticalSize: Float) = Font(
    R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
        FontVariation.width(DISPLAY_WIDTH),
    ),
)

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Big figures and screen titles: the display optical size. */
internal val DisplayFamily = FontFamily(
    bricolage(500, 60f),
    bricolage(600, 60f),
    bricolage(700, 60f),
    bricolage(800, 60f),
)

/** Section and card titles: the same face at its text optical size. */
internal val HeadingFamily = FontFamily(
    bricolage(500, 24f),
    bricolage(600, 24f),
    bricolage(700, 24f),
    bricolage(800, 24f),
)

internal val TextFamily = FontFamily(
    manrope(400),
    manrope(500),
    manrope(600),
    manrope(700),
    manrope(800),
)

private fun style(
    family: FontFamily,
    weight: Int,
    size: Int,
    line: Int,
    tracking: TextUnit = 0.sp,
    features: String? = null,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
    fontFeatureSettings = features,
)

internal val AppTypography = Typography(
    // Bricolage is already tight at its display optical size, so the tracking stays near zero:
    // the -0.03em the platform sans needed ran this face's letters into each other.
    displayLarge = style(DisplayFamily, 800, 64, 66, (-0.015).em, TABULAR),
    displayMedium = style(DisplayFamily, 800, 50, 54, (-0.012).em, TABULAR),
    displaySmall = style(DisplayFamily, 700, 36, 42, (-0.008).em, TABULAR),
    headlineLarge = style(DisplayFamily, 700, 31, 37, (-0.005).em),
    headlineMedium = style(HeadingFamily, 700, 25, 31, (-0.005).em),
    headlineSmall = style(HeadingFamily, 700, 21, 27),
    titleLarge = style(HeadingFamily, 700, 19, 24),
    titleMedium = style(TextFamily, 700, 16, 22),
    titleSmall = style(TextFamily, 700, 14, 19),
    bodyLarge = style(TextFamily, 500, 16, 24),
    bodyMedium = style(TextFamily, 500, 14, 21),
    bodySmall = style(TextFamily, 500, 13, 18),
    labelLarge = style(TextFamily, 700, 14, 18),
    labelMedium = style(TextFamily, 700, 12, 16, 0.01.em, TABULAR),
    labelSmall = style(TextFamily, 800, 11, 14, 0.12.em),
)

/** wdth on Bricolage's 75..100 axis: a touch narrow, so "1,240" fits beside a ring. */
private const val DISPLAY_WIDTH = 92f
