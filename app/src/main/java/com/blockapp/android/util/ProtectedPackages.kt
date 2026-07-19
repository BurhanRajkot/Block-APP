package com.blockapp.android.util

/**
 * Packages this app must never treat as a normal, lockable app. Locking any of these creates a
 * self-inflicted lockout: Tier 2 of AppBlockAccessibilityService kicks the *entire* package
 * home the instant it comes to the foreground, and Settings/the package installer/Play Store
 * are exactly the screens setup and removal depend on. Single source of truth shared by the
 * app picker (which must never offer these) and the accessibility service (which already
 * treats Settings specially for Tier 1).
 */
object ProtectedPackages {
    val SETTINGS = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
    )

    val PACKAGE_INSTALLER = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    const val PLAY_STORE = "com.android.vending"

    val ALL: Set<String> = SETTINGS + PACKAGE_INSTALLER + PLAY_STORE
}
