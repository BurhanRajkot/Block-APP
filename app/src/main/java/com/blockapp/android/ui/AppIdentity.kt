package com.blockapp.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Display name and launcher icon for a package, as resolved from PackageManager. */
internal data class AppIdentity(val label: String, val icon: ImageBitmap?)

/**
 * Resolves [packageName] to something showable, memoised in [cache] for the life of the process.
 *
 * Decoding happens on the calling thread. That is only affordable because of the cache: a cold
 * call rasterises an adaptive icon, which is far too slow to run per frame or per scrolled row,
 * and AppPickerScreen draws one for every launchable app on the device. Still wrap the call in
 * `remember` at the call site — the cache keeps a scroll cheap, it doesn't make a re-decode free.
 *
 * A lock row outlives the package it points at: the app can be uninstalled while its lock is
 * still running, and Room keeps the row either way. So a missing package is an expected state
 * here, not an error — it falls back to the raw package name instead of throwing and taking the
 * block screen down with it. That fallback is cached too, so an uninstalled package doesn't hit
 * PackageManager again on every recomposition.
 */
internal fun loadAppIdentity(context: Context, packageName: String): AppIdentity {
    cache.get(packageName)?.let { return it }

    val pm = context.packageManager
    val identity = try {
        val info = pm.getApplicationInfo(packageName, 0)
        AppIdentity(
            label = pm.getApplicationLabel(info).toString(),
            icon = pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        AppIdentity(label = packageName, icon = null)
    }
    cache.put(packageName, identity)
    return identity
}

/**
 * Bounded on purpose. Every entry holds an [ICON_PX]² ARGB bitmap (~83KB), so an unbounded map
 * keyed by package would grow to tens of megabytes on a phone with a few hundred apps installed
 * — enough to get this process killed, which on this app means enforcement stops. 48 entries
 * (~4MB) comfortably covers the visible window of any list drawn here plus a screen of scrollback.
 */
private val cache = LruCache<String, AppIdentity>(48)

/**
 * Rasterisation size for launcher icons. Adaptive icons have no meaningful intrinsic size, so a
 * size has to be given explicitly; 144px covers the largest place one is drawn (the 56dp badge on
 * the block screen) on an xxhdpi display without decoding a full-resolution bitmap per row.
 */
private const val ICON_PX = 144
