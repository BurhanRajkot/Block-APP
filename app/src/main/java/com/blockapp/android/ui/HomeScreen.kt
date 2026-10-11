package com.blockapp.android.ui

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.blockapp.android.BlockApplication
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.data.BlockedAppEntity
import com.blockapp.android.data.ReelCountEntity
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.EmberWash
import com.blockapp.android.ui.theme.StatusColors
import com.blockapp.android.usage.DayUsage
import com.blockapp.android.usage.UsageStatsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Today tab: protection state, today's screen time and reels, the two ways to lock, running
 * locks, and the apps the day went to. Every figure here is also on InsightsScreen in more
 * depth. This screen is the glance, and tapping a figure goes there.
 *
 * Invariant 1 note: RemoveProtectionScreen is reached from here through the Settings tab in
 * MainActivity's bottom bar, which is always visible on this screen.
 */
@Composable
fun HomeScreen(
    onFocusMode: () -> Unit,
    onAddLock: () -> Unit,
    onLockApp: (packageName: String) -> Unit,
    onInsights: () -> Unit,
    onFixSetup: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    var locks by remember { mutableStateOf<List<BlockedAppEntity>>(emptyList()) }
    var isAdminActive by remember { mutableStateOf(true) }
    var isAccessibilityActive by remember { mutableStateOf(true) }
    var hasUsageAccess by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var reelCounts by remember { mutableStateOf<List<ReelCountEntity>>(emptyList()) }
    var usage by remember { mutableStateOf<DayUsage?>(null) }

    LaunchedEffect(Unit) {
        app.repository.activeEntities.collectLatest { locks = it }
    }

    // Keyed on the date, derived from the 1s ticker below. Without that, a screen left open past
    // midnight would keep showing yesterday's total. LocalDate compares by value, so the
    // collector only restarts when the day actually changes, not every tick.
    val today = remember(now) { LocalDate.now() }
    LaunchedEffect(today) {
        app.repository.observeReelCounts(today).collectLatest { reelCounts = it }
    }

    // Ticks once a second so the remaining-time figure stays live instead of showing a stale
    // duration computed only when the lock list last changed.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }

    // Re-check protection status every time this screen resumes (e.g. after returning from
    // Settings). This composable stays alive in the background while the user is off in
    // Settings, so a one-shot LaunchedEffect(Unit) would never see the updated state —
    // ON_RESUME is what actually fires when the user comes back.
    LifecycleResumeEffect(Unit) {
        isAdminActive = DeviceAdminHelper.isAdminActive(context)
        isAccessibilityActive = DeviceAdminHelper.isAccessibilityActive(context)
        hasUsageAccess = UsageStatsProvider.hasUsageAccess(context)
        // One-shot sweep, not a poll: catches a lock whose expiry alarm was missed (see
        // BlockApplication's cold-start sweep for why that can happen) every time the user
        // looks at this screen, at zero background cost since it only runs on resume.
        app.applicationScope.launch { app.repository.expireAllDue() }
        onPauseOrDispose {}
    }

    // Screen time is re-read every minute while this screen is in front, and never while it's
    // in the background: a usage replay walks every event since midnight, which is cheap once a
    // minute and wasteful on a timer nobody is looking at.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(hasUsageAccess) {
        if (!hasUsageAccess) {
            usage = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                usage = withContext(Dispatchers.IO) {
                    // Usage access can be revoked between the check and the query.
                    runCatching { UsageStatsProvider.history(context, 1).last() }.getOrNull()
                }
                delay(USAGE_REFRESH_MS)
            }
        }
    }

    val isProtected = isAdminActive && isAccessibilityActive
    val reelsByPackage = remember(reelCounts) { reelCounts.associate { it.packageName to it.reels } }
    val lockedPackages = remember(locks) { locks.map { it.packageName }.toSet() }

    Box(Modifier.fillMaxSize()) {
        AmbientGlow()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
        ) {
            item {
                Reveal(0) { Header(isProtected = isProtected, onFixSetup = onFixSetup) }
            }
            if (!isProtected) {
                item {
                    Reveal(1) {
                        SetupWarning(
                            isAdminActive = isAdminActive,
                            isAccessibilityActive = isAccessibilityActive,
                            onFix = onFixSetup,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Reveal(2) {
                    ScreenTimePanel(
                        usage = usage,
                        hasAccess = hasUsageAccess,
                        onOpen = onInsights,
                        onGrant = { openUsageAccessSettings(context) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Reveal(3) {
                    ReelsPanel(counts = reelCounts, isCounting = isAccessibilityActive, onOpen = onInsights)
                }
                Spacer(Modifier.height(12.dp))
                Reveal(4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(
                            "Focus mode",
                            onClick = onFocusMode,
                            icon = Icons.Filled.Bolt,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            "Lock an app",
                            onClick = onAddLock,
                            icon = Icons.Outlined.Lock,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (locks.isNotEmpty()) {
                item { Reveal(5) { SectionHeader("Locked now") } }
                itemsIndexed(locks, key = { _, lock -> lock.packageName }) { index, lock ->
                    Reveal(6 + index, Modifier.animateItem()) {
                        LockCard(lock = lock, now = now)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            val apps = usage?.apps.orEmpty()
            if (apps.isNotEmpty()) {
                item {
                    Reveal(6 + locks.size) {
                        Column {
                            SectionHeader("Your apps today", action = "See all", onAction = onInsights)
                            Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                                val shown = apps.take(MAX_APPS_ON_HOME)
                                val max = shown.first().foregroundMillis
                                shown.forEachIndexed { index, appUsage ->
                                    if (index > 0) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                    AppUsageRow(
                                        usage = appUsage,
                                        maxMillis = max,
                                        reels = reelsByPackage[appUsage.packageName] ?: 0,
                                        isLocked = appUsage.packageName in lockedPackages,
                                        onLock = { onLockApp(appUsage.packageName) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A large, very faint Volt glow drifting slowly behind the top of the screen. It gives the dark
 * page some depth and a sense that it's live, without competing with any figure on it.
 */
@Composable
private fun AmbientGlow() {
    val transition = rememberInfiniteTransition(label = "glow")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(GLOW_DRIFT_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val glow = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width * (0.15f + 0.7f * drift), size.height * 0.08f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.10f), Color.Transparent),
                center = center,
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = center,
        )
    }
}

@Composable
private fun Header(isProtected: Boolean, onFixSetup: () -> Unit) {
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(date)
            Spacer(Modifier.height(4.dp))
            Text(greeting(), style = MaterialTheme.typography.headlineLarge)
        }
        if (isProtected) {
            StatusPill("Protected", MaterialTheme.colorScheme.primary, live = true)
        } else {
            StatusPill("Off", StatusColors.Danger, onClick = onFixSetup)
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Still up?"
}

@Composable
private fun SetupWarning(isAdminActive: Boolean, isAccessibilityActive: Boolean, onFix: () -> Unit) {
    Panel(color = StatusColors.Danger.copy(alpha = 0.12f), onClick = onFix) {
        Text(
            "Blocking isn't fully on",
            style = MaterialTheme.typography.titleMedium,
            color = StatusColors.Danger,
        )
        Spacer(Modifier.height(4.dp))
        if (!isAccessibilityActive) {
            Text(
                "Accessibility is off, so locked apps still open and reels aren't counted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!isAdminActive) {
            Text(
                "Device Admin is off, so the app can be uninstalled mid-lock.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("Finish setup  →", style = MaterialTheme.typography.labelLarge, color = StatusColors.Danger)
    }
}

@Composable
private fun ScreenTimePanel(usage: DayUsage?, hasAccess: Boolean, onOpen: () -> Unit, onGrant: () -> Unit) {
    if (!hasAccess) {
        Panel { UsageAccessPrompt(onGrant = onGrant) }
        return
    }
    Panel(onClick = onOpen) {
        Eyebrow("Screen time")
        Spacer(Modifier.height(6.dp))
        if (usage == null) {
            Text("—", style = MaterialTheme.typography.displayLarge)
        } else {
            AnimatedDuration(usage.totalMillis, MaterialTheme.typography.displayLarge)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            AnimatedMiniStat(usage?.unlocks ?: 0, "unlocks", Modifier.weight(1f))
            AnimatedMiniStat(usage?.totalOpens ?: 0, "app opens", Modifier.weight(1f))
            AnimatedMiniStat(usage?.apps?.size ?: 0, "apps used", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        BarChart(
            values = usage?.hourly?.toList() ?: List(24) { 0L },
            color = MaterialTheme.colorScheme.primary,
            highlight = LocalTime.now().hour,
            chartHeight = 72.dp,
        )
        HourAxis()
    }
}

/**
 * Today's short-form video total, fed by ReelTracker through the accessibility service, with
 * the top few apps underneath so it's clear where the number came from. Kept visible at zero
 * rather than hidden, because the point is to see the number before opening a feed.
 */
@Composable
private fun ReelsPanel(counts: List<ReelCountEntity>, isCounting: Boolean, onOpen: () -> Unit) {
    val total = counts.sumOf { it.reels }
    val watchMs = counts.sumOf { it.watchMs }
    Panel(onClick = onOpen) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Reels today", color = Ember)
                Spacer(Modifier.height(6.dp))
                AnimatedCount(
                    total,
                    style = MaterialTheme.typography.displayMedium,
                    color = if (total > 0) Ember else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (watchMs > 0L) {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedDuration(watchMs, MaterialTheme.typography.titleLarge)
                    Text(
                        "spent watching",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val note = when {
            // Mirrors the SDK gate in AppBlockAccessibilityService.onServiceConnected.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "Counting needs Android 9 or newer."
            !isCounting -> "Counting is paused while Accessibility is off."
            total == 0 -> "Reels, Shorts, TikTok, and swipe-up videos in any app add up here."
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                counts.take(MAX_REEL_APPS_SHOWN).forEach { ReelAppChip(it) }
            }
        }
    }
}

@Composable
private fun ReelAppChip(entry: ReelCountEntity) {
    val context = LocalContext.current
    val identity = remember(entry.packageName) { loadAppIdentity(context, entry.packageName) }
    Row(
        Modifier
            .background(EmberWash, RoundedCornerShape(50))
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(identity, size = 22)
        Spacer(Modifier.width(6.dp))
        Text("${entry.reels}", style = MaterialTheme.typography.labelMedium, color = Ember)
    }
}

@Composable
private fun LockCard(lock: BlockedAppEntity, now: Long) {
    val context = LocalContext.current
    val identity = remember(lock.packageName) { loadAppIdentity(context, lock.packageName) }
    val remaining = (lock.blockUntil - now).coerceAtLeast(0L)
    val total = (lock.blockUntil - lock.blockedAt).coerceAtLeast(1L)
    val elapsedFraction = ((total - remaining).toFloat() / total).coerceIn(0f, 1f)

    Panel(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(identity, size = 44)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(identity.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (remaining > 0L) "Unlocks ${formatUnlockAt(lock.blockUntil)}" else "Unlocking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RollingText(
                if (remaining > 0L) formatCountdown(remaining) else "—",
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Meter(elapsedFraction, MaterialTheme.colorScheme.primary, height = 4.dp)
    }
}

/**
 * Per-app chips under the reel total. More than four stops fitting one row at phone width, and
 * the long tail of apps with a reel or two tells the user nothing.
 */
private const val MAX_REEL_APPS_SHOWN = 4

/** The rest of the list is one tap away on InsightsScreen. */
private const val MAX_APPS_ON_HOME = 6

private const val USAGE_REFRESH_MS = 60_000L

/** One sweep of the background glow across the top. Slow enough to notice only over time. */
private const val GLOW_DRIFT_MS = 14_000
