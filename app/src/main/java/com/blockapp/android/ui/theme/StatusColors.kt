package com.blockapp.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic status colours shared across every screen that shows a granted/needs-attention/
 * heads-up state (HomeScreen's protection banner, SettingsScreen, OnboardingScreen,
 * RemoveProtectionScreen, UnlockKeyScreen, AppPickerScreen's "Locked" chip). Kept as plain
 * literals rather than derived from the colour scheme — "granted" is a status, not a brand
 * colour, and must read the same regardless of theme or light/dark mode. Single source of truth
 * so the six near-identical private copies that used to live in each screen file can't drift.
 */
object StatusColors {
    val Success = Color(0xFF2E7D32)
    val Danger = Color(0xFFB71C1C)
    val Warning = Color(0xFFB26A00)
}
