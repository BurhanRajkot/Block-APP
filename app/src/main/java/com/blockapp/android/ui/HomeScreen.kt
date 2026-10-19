package com.blockapp.android.ui

import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.blockapp.android.BlockApplication
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.data.BlockedAppEntity
import com.blockapp.android.data.ReelCountEntity
import com.blockapp.android.data.ReelDayTotal
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.EmberWash
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.ui.theme.StatusColors
import com.blockapp.android.usage.DayUsage
import com.blockapp.android.usage.UsageStatsProvider
import com.blockapp.android.util.ReelLimit
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Today tab: protection state, today's reels against the plan, the last seven days, today's
 * screen time, the two ways to lock, running locks, and the apps the day went to. Every figure
 * here opens the matching day in HistoryScreen.
 *
 * Invariant 1 note: RemoveProtectionScreen is reached from here through the Settings tab in
 * MainActivity's bottom bar, which is always visible on this screen.
 *
 * Nothing at the top level of this composable may change every second. The countdowns tick
 * inside each [LockCard]; a ticker read up here used to recompose the whole screen once a
 * second, which was a large part of why scrolling it felt heavy.
 */
@Composable
fun HomeScreen(
    onFocusMode: () -> Unit,
    onAddLock: () -> Unit,
    onLockApp: (packageName: String) -> Unit,
    onHistory: (day: LocalDate?) -> Unit,
    onPlan: () -> Unit,
    onFixSetup: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    var locks by remember { mutableStateOf<List<BlockedAppEntity>>(emptyList()) }
    var isAdminActive by remember { mutableStateOf(true) }
    var isAccessibilityActive by remember { mutableStateOf(true) }
    var hasUsageAccess by remember { mutableStateOf(true) }
    var reelCounts by remember { mutableStateOf<List<ReelCountEntity>>(emptyList()) }
    var week by remember { mutableStateOf<List<ReelDayTotal>>(emptyList()) }
    var usage by remember { mutableStateOf<DayUsage?>(null) }
    var reelLimit by remember { mutableStateOf(ReelLimit.todayLimit(context)) }
    var plan by remember { mutableStateOf(ReelPlan.current(context)) }
    var quiet by remember { mutableStateOf(ReelPlan.quietHours(context)) }
    var editingLimit by remember { mutableStateOf(false) }

    // Advanced once a minute. LocalDate compares by value, so setting the same day again
    // recomposes nothing; only an actual midnight re-keys the collectors below.
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DAY_CHECK_MS)
            today = LocalDate.now()
        }
    }

    LaunchedEffect(Unit) {
        app.repository.activeEntities.collectLatest { locks = it }
    }
    LaunchedEffect(today) {
        app.repository.observeReelCounts(today).collectLatest { reelCounts = it }
    }
    LaunchedEffect(today) {
        app.repository.observeReelDays(today.minusDays(6), today).collectLatest { week = it }
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
        val job = app.applicationScope.launch {
            // applicationScope has no exception handler, so a failure here must not escape
            // and take the process (and enforcement) down with it (invariant 9).
            try {
                app.repository.expireAllDue()
                // Today's plan step, then re-read the limit it may have just lowered. Also picks
                // up a raised limit that took effect at midnight while the app was away.
                ReelPlan.refresh(context, app.repository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w("HomeScreen", "resume refresh failed", e)
            }
            withContext(Dispatchers.Main) {
                reelLimit = ReelLimit.todayLimit(context)
                plan = ReelPlan.current(context)
                quiet = ReelPlan.quietHours(context)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    // Screen time is re-read every minute while this screen is in front, and never while it's
    // in the background: a usage replay walks every event since midnight, which is cheap once a
    // minute and wasteful on a timer nobody is looking at. Each read is also archived, so today
    // is in History's archive even if History is never opened.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(hasUsageAccess) {
        if (!hasUsageAccess) {
            usage = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val day = withContext(Dispatchers.IO) {
                    // Usage access can be revoked between the check and the query.
                    runCatching { UsageStatsProvider.history(context, 1).last() }.getOrNull()
                }
                usage = day
                if (day != null) app.repository.archiveUsage(listOf(day))
                delay(USAGE_REFRESH_MS)
            }
        }
    }

    if (editingLimit) {
        ReelLimitDialog(
            onDismiss = { editingLimit = false },
            onDone = {
                editingLimit = false
                reelLimit = ReelLimit.todayLimit(context)
            },
        )
    }

    val isProtected = isAdminActive && isAccessibilityActive
    val reelsByPackage = remember(reelCounts) { reelCounts.associate { it.packageName to it.reels } }
    val lockedPackages = remember(locks) { locks.map { it.packageName }.toSet() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
        ) {
            item(key = "header") {
                Reveal(0) { Header(today = today, isProtected = isProtected, onFixSetup = onFixSetup) }
            }
            if (!isProtected) {
                item(key = "setup") {
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
            item(key = "reels") {
                Reveal(2) {
                    ReelHero(
                        counts = reelCounts,
                        isCounting = isAccessibilityActive,
                        limit = reelLimit,
                        plan = plan,
                        quiet = quiet,
                        today = today,
                        onOpen = { onHistory(today) },
                        onSetLimit = { editingLimit = true },
                        onPlan = onPlan,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            item(key = "week") {
                Reveal(3) {
                    WeekStrip(
                        today = today,
                        days = week,
                        limit = reelLimit,
                        onPick = { onHistory(it) },
                        onSeeAll = { onHistory(null) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            item(key = "screen") {
                Reveal(4) {
                    ScreenTimePanel(
                        usage = usage,
                        hasAccess = hasUsageAccess,
                        onOpen = { onHistory(today) },
                        onGrant = { openUsageAccessSettings(context) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            item(key = "actions") {
                Reveal(5) {
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
                item(key = "locks-header") { SectionHeader("Locked now") }
                itemsIndexed(locks, key = { _, lock -> lock.packageName }) { _, lock ->
                    Box(Modifier.animateItem()) { LockCard(lock = lock) }
                    Spacer(Modifier.height(10.dp))
                }
            }

            val apps = usage?.apps.orEmpty()
            if (apps.isNotEmpty()) {
                item(key = "apps") {
                    Column {
                        SectionHeader("Your apps today", action = "History", onAction = { onHistory(today) })
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

@Composable
private fun Header(today: LocalDate, isProtected: Boolean, onFixSetup: () -> Unit) {
    val date = remember(today) { today.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())) }
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

/**
 * Today's reels as the hero of the screen: a ring filling towards the limit, the plan's week, and
 * the reel-free hours. Kept visible at zero rather than hidden, because the point is to see the
 * number before opening a feed.
 */
@Composable
private fun ReelHero(
    counts: List<ReelCountEntity>,
    isCounting: Boolean,
    limit: Int?,
    plan: ReelPlan.Plan?,
    quiet: ReelPlan.QuietHours?,
    today: LocalDate,
    onOpen: () -> Unit,
    onSetLimit: () -> Unit,
    onPlan: () -> Unit,
) {
    val total = counts.sumOf { it.reels }
    val watchMs = counts.sumOf { it.watchMs }
    // A limit of 0 is "no reels today": the ring is full from the first reel, and empty before.
    val fraction = when {
        limit == null -> 0f
        limit == 0 -> if (total > 0) 1f else 0f
        else -> total.toFloat() / limit
    }
    Panel(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Reels today", color = Ember, modifier = Modifier.weight(1f))
            Text(
                when (limit) {
                    null -> "Set a limit"
                    0 -> "No reels"
                    else -> "Limit $limit"
                },
                style = MaterialTheme.typography.labelLarge,
                color = Ember,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(EmberWash)
                    .clickable(onClick = onSetLimit)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(fraction = fraction, size = 148.dp, stroke = 13.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedCount(total, style = MaterialTheme.typography.displayMedium)
                    Text(
                        when (limit) {
                            null -> "reels"
                            0 -> "of 0"
                            else -> "of $limit"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (limit != null) {
                    HeroFact(
                        value = when {
                            total >= limit -> "Done"
                            else -> "${limit - total}"
                        },
                        label = when {
                            limit == 0 -> "reels are off"
                            total >= limit -> "reels are off till midnight"
                            else -> "left today"
                        },
                    )
                }
                if (watchMs > 0L) {
                    HeroFact(value = formatHoursMinutes(watchMs), label = "spent watching")
                }
                if (quiet != null) {
                    val minute = LocalTime.now().let { it.hour * 60 + it.minute }
                    QuietChip(quiet = quiet, now = quiet.contains(minute))
                }
            }
        }

        val note = when {
            // Mirrors the SDK gate in AppBlockAccessibilityService.onServiceConnected.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "Counting needs Android 9 or newer."
            !isCounting -> "Counting is paused while Accessibility is off."
            total == 0 && limit == null ->
                "Short videos in Instagram, YouTube, TikTok, Reddit, LinkedIn and the like add up here."
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(12.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
        PlanStrip(plan = plan, today = today, onClick = onPlan)

        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                counts.take(MAX_REEL_APPS_SHOWN).forEach { ReelAppChip(it) }
            }
        }
    }
}

@Composable
private fun HeroFact(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietChip(quiet: ReelPlan.QuietHours, now: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (now) EmberWash else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Bedtime,
            contentDescription = null,
            tint = if (now) Ember else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (now) "Reel-free now" else quiet.label(),
            style = MaterialTheme.typography.labelMedium,
            color = if (now) Ember else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The plan's progress in one tappable line, or an invitation to build one. */
@Composable
private fun PlanStrip(plan: ReelPlan.Plan?, today: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (plan == null) {
                Text("Build a reel plan", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Five questions, then a limit that steps down each week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val week = plan.weekOf(today)
                Text(
                    "Week ${week + 1} of ${plan.weeks} · ${plan.goal.title}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                Meter((week + 1) / plan.weeks.toFloat(), Lime, height = 4.dp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The last seven days of reels as tappable columns, today last, with the limit as a faint line.
 * Each opens that day in History; the header opens the month.
 */
@Composable
private fun WeekStrip(
    today: LocalDate,
    days: List<ReelDayTotal>,
    limit: Int?,
    onPick: (LocalDate) -> Unit,
    onSeeAll: () -> Unit,
) {
    val byDay = remember(days) { days.associateBy { it.day } }
    val dates = remember(today) { List(7) { today.minusDays(6L - it) } }
    val values = dates.map { byDay[it.toString()]?.reels ?: 0 }
    val max = maxOf(values.maxOrNull() ?: 0, limit ?: 0, 1)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Last 7 days", modifier = Modifier.weight(1f))
            Text(
                "Calendar",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(116.dp)) {
            dates.forEachIndexed { index, date ->
                val value = values[index]
                val isToday = index == dates.lastIndex
                val over = limit != null && value > limit
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPick(date) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (value > 0) "$value" else "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (over) Ember else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .width(18.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val share = value / max.toFloat()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(share.coerceIn(0.04f, 1f))
                                .clip(RoundedCornerShape(50))
                                .background(
                                    when {
                                        value == 0 -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        over -> Ember
                                        isToday -> Lime
                                        else -> Lime.copy(alpha = 0.45f)
                                    },
                                ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
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
            Text("—", style = MaterialTheme.typography.displayMedium)
        } else {
            AnimatedDuration(usage.totalMillis, MaterialTheme.typography.displayMedium)
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

@Composable
private fun ReelAppChip(entry: ReelCountEntity) {
    val identity = rememberAppIdentity(entry.packageName)
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

/**
 * One running lock. It owns its one-second ticker, so a tick recomposes this card and nothing
 * else on the screen.
 */
@Composable
private fun LockCard(lock: BlockedAppEntity) {
    val identity = rememberAppIdentity(lock.packageName)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lock.blockUntil) {
        while (now < lock.blockUntil) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }
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

/** The rest of the list is one tap away on HistoryScreen. */
private const val MAX_APPS_ON_HOME = 6

private const val USAGE_REFRESH_MS = 60_000L

/** How often the screen checks whether midnight has passed. */
private const val DAY_CHECK_MS = 60_000L
