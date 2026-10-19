package com.blockapp.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.blockapp.android.util.InstalledAppsProvider
import com.blockapp.android.util.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Display name and launcher icon for a package, as resolved from PackageManager. [isPlaceholder]
 * marks the stand-in [rememberAppIdentity] returns for the frame or two before the real one has
 * been decoded off the main thread.
 */
internal data class AppIdentity(
    val label: String,
    val icon: ImageBitmap?,
    val isPlaceholder: Boolean = false,
)

/**
 * [loadAppIdentity] for composables: a cache hit is returned in the same frame, and a miss is
 * decoded on [Dispatchers.IO] while a placeholder (neutral tile, label from the cheap label cache
 * if it is already there) is drawn. Calling loadAppIdentity inside `remember {}` did that decode
 * on the main thread, once per row, which is what made the first scroll of the app picker and
 * the first frame of Home stutter.
 */
@Composable
internal fun rememberAppIdentity(packageName: String): AppIdentity {
    val context = LocalContext.current
    val cached = remember(packageName) { cache.get(packageName) }
    if (cached != null) return cached
    val state = produceState(
        initialValue = AppIdentity(label = "", icon = null, isPlaceholder = true),
        key1 = packageName,
    ) {
        value = withContext(Dispatchers.IO) { loadAppIdentity(context, packageName) }
    }
    return state.value
}

/**
 * The launchable apps, loaded off the main thread. Null only on the very first load in a process;
 * after that the previous list is shown at once and silently replaced by a fresh one, so the
 * picker opens instantly the second time and still picks up an app installed in between.
 */
@Composable
internal fun rememberLaunchableApps(): List<LaunchableApp>? {
    val context = LocalContext.current
    val state = produceState(initialValue = lastAppList) {
        val fresh = withContext(Dispatchers.IO) {
            // A PackageManager failure leaves the old list (or the loading state) in place
            // rather than crashing the screen.
            runCatching { InstalledAppsProvider.listLaunchableApps(context) }.getOrNull()
        }
        if (fresh != null) {
            lastAppList = fresh
            value = fresh
        }
    }
    return state.value
}

@Volatile private var lastAppList: List<LaunchableApp>? = null

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
            label = InstalledAppsProvider.label(context, packageName),
            icon = pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        AppIdentity(label = packageName, icon = null)
    }
    cache.put(packageName, identity)
    return identity
}

/**
 * Renders [identity]'s launcher icon at [size].dp, or a generic app glyph if the package has
 * since been uninstalled (see [loadAppIdentity] on why that's an expected state, not an error).
 * Shared by every screen that lists apps — [AppPickerScreen], [FocusModeScreen], lock rows on
 * [HomeScreen] — so the fallback treatment can't drift between them.
 */
@Composable
internal fun AppIcon(identity: AppIdentity, size: Int) {
    if (identity.isPlaceholder) {
        Box(
            Modifier
                .size(size.dp)
                .clip(IconShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    } else if (identity.icon != null) {
        Image(
            bitmap = identity.icon,
            contentDescription = null,
            modifier = Modifier
                .size(size.dp)
                .clip(IconShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(IconShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size((size * 0.55f).dp),
            )
        }
    }
}

/**
 * Rounded square rather than a circle: most launcher icons are already squircles, and a circle
 * crop cuts into their corners.
 */
private val IconShape = RoundedCornerShape(percent = 28)

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
