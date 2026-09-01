package com.blockapp.android.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Decides which installed apps a "Focus Mode" one-tap lock should target: social media,
 * entertainment, and games — the categories of app someone reaches for on impulse — with the
 * apps someone actually needs to stay reachable on left alone.
 *
 * No network access exists to look this up remotely (see CLAUDE.md invariant 8), so detection is
 * entirely local and two-layered: a curated list of well-known package names, backed by
 * [ApplicationInfo.category] for anything not in that list. The curated list exists because most
 * apps never declare `android:appCategory` in their manifest at all — it defaults to
 * [ApplicationInfo.CATEGORY_UNDEFINED] — so category alone would miss most of the apps this
 * feature is actually for. Category is what lets "any other social media or entertainment app"
 * generalise past that fixed list instead of requiring every app on earth be named here.
 */
object FocusModeApps {

    /**
     * Never a target even if it also matches a category or the known-package list below — this
     * is the one app the user asked Focus Mode to leave alone. The business variant is included
     * so a work-profile install isn't locked either.
     */
    private val EXCLUDED = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
    )

    /**
     * Popular social/entertainment/game packages, named explicitly because most of them report
     * [ApplicationInfo.CATEGORY_UNDEFINED] on real devices. Not attempted as an exhaustive list —
     * [FOCUS_CATEGORIES] below is what catches the long tail.
     */
    private val KNOWN_PACKAGES = setOf(
        // social
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca", // Messenger
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.snapchat.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.ss.android.ugc.trill", // TikTok, some regions
        "com.pinterest",
        "com.linkedin.android",
        "com.discord",
        "com.tencent.mm", // WeChat
        "com.bumble.app",
        "com.tinder",
        // video / streaming
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.netflix.mediaclient",
        "tv.twitch.android.app",
        "in.startv.hotstar",
        "com.amazon.avod.thirdpartyclient", // Prime Video
        // audio entertainment
        "com.spotify.music",
        // games
        "com.supercell.clashofclans",
        "com.supercell.clashroyale",
        "com.supercell.brawlstars",
        "com.supercell.hayday",
        "com.supercell.boombeach",
        "com.king.candycrushsaga",
        "com.king.candycrushsodasaga",
        "com.mojang.minecraftpe",
        "com.tencent.ig", // PUBG Mobile
        "com.pubg.imobile",
        "com.dts.freefireth", // Free Fire
        "com.roblox.client",
        "com.miHoYo.GenshinImpact",
        "com.activision.callofduty.shooter", // Call of Duty: Mobile
    )

    // ApplicationInfo.category was added in API 26, which is this app's minSdk, so every
    // supported device reports it — no SDK_INT guard needed (same reasoning as
    // OnboardingScreen's ACTION_APP_NOTIFICATION_SETTINGS note).
    private val FOCUS_CATEGORIES = setOf(
        ApplicationInfo.CATEGORY_SOCIAL,
        ApplicationInfo.CATEGORY_GAME,
        ApplicationInfo.CATEGORY_VIDEO,
    )

    private fun isTarget(context: Context, packageName: String): Boolean {
        if (packageName in EXCLUDED) return false
        if (packageName in KNOWN_PACKAGES) return true
        return try {
            context.packageManager.getApplicationInfo(packageName, 0).category in FOCUS_CATEGORIES
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Installed, launchable apps Focus Mode would lock right now. Built on
     * [InstalledAppsProvider.listLaunchableApps], which already excludes this app itself and
     * every [ProtectedPackages] entry, so Focus Mode inherits that backstop instead of
     * re-implementing it.
     */
    fun findTargets(context: Context): List<LaunchableApp> =
        InstalledAppsProvider.listLaunchableApps(context)
            .filter { isTarget(context, it.packageName) }
}
