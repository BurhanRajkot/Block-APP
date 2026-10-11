package com.blockapp.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.BlockApplication
import com.blockapp.android.data.ReelCountEntity
import com.blockapp.android.data.ReelDayTotal
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.usage.AppUsage
import com.blockapp.android.usage.DayUsage
import com.blockapp.android.usage.UsageStatsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class Range { Day, Week }

/**
 * The Insights tab: screen time and reels for any of the last [DAYS] days, hour by hour, or the
 * whole week at once. Screen time comes from [UsageStatsProvider.history] (needs Usage access)
 * and reels from Room (needs only Accessibility), so each half degrades on its own when its
 * permission is missing.
 */
@Composable
fun InsightsScreen(onLockApp: (packageName: String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication

    var hasAccess by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf<List<DayUsage>?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf(Range.Day) }
    var dayIndex by remember { mutableIntStateOf(DAYS - 1) }
    var reelHistory by remember { mutableStateOf<List<ReelDayTotal>>(emptyList()) }
    var dayReels by remember { mutableStateOf<List<ReelCountEntity>>(emptyList()) }
    var lockedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    val today = remember(resumes) { LocalDate.now() }
    val days = remember(today) { List(DAYS) { today.minusDays((DAYS - 1 - it).toLong()) } }
    val selectedDay = days[dayIndex]

    // Re-read on every resume: usage access may have just been granted in Settings, and a day's
    // figures keep growing while the user is away.
    LifecycleResumeEffect(Unit) {
        hasAccess = UsageStatsProvider.hasUsageAccess(context)
        resumes++
        onPauseOrDispose {}
    }
    LaunchedEffect(resumes, hasAccess) {
        if (!hasAccess) {
            history = null
            return@LaunchedEffect
        }
        history = withContext(Dispatchers.IO) {
            runCatching { UsageStatsProvider.history(context, DAYS) }.getOrNull()
        }
    }
    LaunchedEffect(today) {
        app.repository.observeReelHistory(days.first()).collectLatest { reelHistory = it }
    }
    LaunchedEffect(selectedDay) {
        app.repository.observeReelCounts(selectedDay).collectLatest { dayReels = it }
    }
    LaunchedEffect(Unit) {
        app.repository.activeLocks.collectLatest { lockedPackages = it.keys }
    }

    val reelsByDay = remember(reelHistory) { reelHistory.associateBy { it.day } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Insights", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                RangeToggle(range, onChange = { range = it })
            }
        }

        when (range) {
            Range.Day -> dayContent(
                days = days,
                dayIndex = dayIndex,
                onDayIndex = { dayIndex = it },
                usage = history?.getOrNull(dayIndex),
                hasAccess = hasAccess,
                history = history,
                reels = dayReels,
                lockedPackages = lockedPackages,
                onLockApp = onLockApp,
                onGrant = { openUsageAccessSettings(context) },
            )
            Range.Week -> weekContent(
                days = days,
                history = history,
                hasAccess = hasAccess,
                reelsByDay = reelsByDay,
                lockedPackages = lockedPackages,
                onPickDay = {
                    dayIndex = it
                    range = Range.Day
                },
                onLockApp = onLockApp,
                onGrant = { openUsageAccessSettings(context) },
            )
        }
    }
}

private fun LazyListScope.dayContent(
    days: List<LocalDate>,
    dayIndex: Int,
    onDayIndex: (Int) -> Unit,
    usage: DayUsage?,
    hasAccess: Boolean,
    history: List<DayUsage>?,
    reels: List<ReelCountEntity>,
    lockedPackages: Set<String>,
    onLockApp: (String) -> Unit,
    onGrant: () -> Unit,
) {
    val day = days[dayIndex]
    val isToday = dayIndex == days.lastIndex
    item(key = "day-top") {
        Reveal(0) {
            Column {
                DayStepper(
                    label = dayLabel(day, isToday),
                    dayOrder = dayIndex,
                    canBack = dayIndex > 0,
                    canForward = !isToday,
                    onBack = { onDayIndex(dayIndex - 1) },
                    onForward = { onDayIndex(dayIndex + 1) },
                )
                Spacer(Modifier.height(12.dp))
                if (!hasAccess) {
                    Panel { UsageAccessPrompt(onGrant = onGrant) }
                } else {
                    val average = history
                        ?.filterIndexed { index, it -> index != days.lastIndex && it.totalMillis > 0L }
                        ?.map { it.totalMillis }
                        ?.takeIf { it.isNotEmpty() }
                        ?.average()
                        ?.toLong()
                    Panel {
                        Eyebrow("Screen time")
                        Spacer(Modifier.height(6.dp))
                        if (usage == null) {
                            Text("—", style = MaterialTheme.typography.displayLarge)
                        } else {
                            AnimatedDuration(usage.totalMillis, MaterialTheme.typography.displayLarge)
                        }
                        if (average != null) {
                            Text(
                                "Your daily average is ${formatHoursMinutes(average)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth()) {
                            AnimatedMiniStat(usage?.unlocks ?: 0, "unlocks", Modifier.weight(1f))
                            AnimatedMiniStat(usage?.totalOpens ?: 0, "app opens", Modifier.weight(1f))
                            AnimatedMiniStat(reels.sumOf { it.reels }, "reels", Modifier.weight(1f), color = Ember)
                        }
                        Spacer(Modifier.height(18.dp))
                        BarChart(
                            values = usage?.hourly?.toList() ?: List(24) { 0L },
                            color = MaterialTheme.colorScheme.primary,
                            highlight = if (isToday) LocalTime.now().hour else null,
                            chartHeight = 110.dp,
                        )
                        HourAxis()
                    }
                }
            }
        }
    }

    if (reels.isNotEmpty()) {
        item(key = "day-reels") {
            Reveal(1) {
                Column {
                    SectionHeader("Reels")
                    ReelBreakdown(reels)
                }
            }
        }
    }

    val apps = usage?.apps.orEmpty()
    if (hasAccess) {
        item(key = "day-apps") {
            Reveal(2) {
                Column {
                    SectionHeader("Apps")
                    if (apps.isEmpty()) {
                        Text(
                            if (history == null) "Loading…" else "No app time recorded for this day.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val reelsByPackage = reels.associate { it.packageName to it.reels }
                        AppList(apps, reelsByPackage, lockedPackages, onLockApp)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.weekContent(
    days: List<LocalDate>,
    history: List<DayUsage>?,
    hasAccess: Boolean,
    reelsByDay: Map<String, ReelDayTotal>,
    lockedPackages: Set<String>,
    onPickDay: (Int) -> Unit,
    onLockApp: (String) -> Unit,
    onGrant: () -> Unit,
) {
    val labels = days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    item(key = "week-top") {
        Reveal(0) {
            Column {
                if (!hasAccess) {
                    Panel { UsageAccessPrompt(onGrant = onGrant) }
                } else {
                    val totals = history?.map { it.totalMillis } ?: List(days.size) { 0L }
                    val daysWithData = totals.count { it > 0L }.coerceAtLeast(1)
                    Panel {
                        Eyebrow("Screen time · last 7 days")
                        Spacer(Modifier.height(6.dp))
                        AnimatedDuration(totals.sum(), MaterialTheme.typography.displayMedium)
                        Text(
                            "${formatHoursMinutes(totals.sum() / daysWithData)} a day on average",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        BarChart(
                            values = totals,
                            color = MaterialTheme.colorScheme.primary,
                            highlight = days.lastIndex,
                            labels = labels,
                            onSelect = onPickDay,
                            chartHeight = 120.dp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap a day to open it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val reelCounts = days.map { (reelsByDay[it.toString()]?.reels ?: 0).toLong() }
                val reelTime = days.sumOf { reelsByDay[it.toString()]?.watchMs ?: 0L }
                Panel {
                    Eyebrow("Reels · last 7 days", color = Ember)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedCount(
                            reelCounts.sum().toInt(),
                            style = MaterialTheme.typography.displayMedium,
                            color = Ember,
                            modifier = Modifier.weight(1f),
                        )
                        if (reelTime > 0L) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatHoursMinutes(reelTime), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "spent watching",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    BarChart(
                        values = reelCounts,
                        color = Ember,
                        highlight = days.lastIndex,
                        labels = labels,
                        onSelect = onPickDay,
                        chartHeight = 90.dp,
                    )
                }
            }
        }
    }

    if (hasAccess && history != null) {
        val weekApps = history
            .flatMap { it.apps }
            .groupBy { it.packageName }
            .map { (pkg, entries) ->
                AppUsage(pkg, entries.first().label, entries.sumOf { it.foregroundMillis }, entries.sumOf { it.opens })
            }
            .sortedByDescending { it.foregroundMillis }
        if (weekApps.isNotEmpty()) {
            item(key = "week-apps") {
                Reveal(1) {
                    Column {
                        SectionHeader("Top apps this week")
                        AppList(weekApps, emptyMap(), lockedPackages, onLockApp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppList(
    apps: List<AppUsage>,
    reelsByPackage: Map<String, Int>,
    lockedPackages: Set<String>,
    onLockApp: (String) -> Unit,
) {
    Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        val max = apps.first().foregroundMillis
        apps.forEachIndexed { index, usage ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AppUsageRow(
                usage = usage,
                maxMillis = max,
                reels = reelsByPackage[usage.packageName] ?: 0,
                isLocked = usage.packageName in lockedPackages,
                onLock = { onLockApp(usage.packageName) },
            )
        }
    }
}

@Composable
private fun ReelBreakdown(reels: List<ReelCountEntity>) {
    val context = LocalContext.current
    Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        val max = reels.maxOf { it.reels }.coerceAtLeast(1)
        reels.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val identity = remember(entry.packageName) { loadAppIdentity(context, entry.packageName) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(identity, size = 36)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            identity.label,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${entry.reels}", style = MaterialTheme.typography.labelMedium, color = Ember)
                    }
                    Spacer(Modifier.height(7.dp))
                    Meter(entry.reels.toFloat() / max, Ember, height = 5.dp)
                    if (entry.watchMs > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${formatHoursMinutes(entry.watchMs)} watching",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Day/Week switch. The Volt pill slides under the chosen option rather than jumping. */
@Composable
private fun RangeToggle(range: Range, onChange: (Range) -> Unit) {
    val optionWidth = 72.dp
    val offset by animateDpAsState(
        optionWidth * range.ordinal,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "toggle",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .width(optionWidth)
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
        Row {
            Range.entries.forEach { option ->
                val selected = option == range
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "toggleText",
                )
                Box(
                    Modifier
                        .width(optionWidth)
                        .height(36.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onChange(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.name, style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
        }
    }
}

@Composable
private fun DayStepper(
    label: String,
    dayOrder: Int,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack, enabled = canBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        // The label slides the way the user is moving through the days.
        AnimatedContent(
            targetState = label to dayOrder,
            transitionSpec = {
                val forward = targetState.second > initialState.second
                (slideInHorizontally { if (forward) it / 2 else -it / 2 } + fadeIn())
                    .togetherWith(slideOutHorizontally { if (forward) -it / 2 else it / 2 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "day",
        ) { (text, _) ->
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onForward, enabled = canForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

private fun dayLabel(day: LocalDate, isToday: Boolean): String = when {
    isToday -> "Today"
    day == LocalDate.now().minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
}

/**
 * How far back Insights goes. The system keeps raw usage events for about this long (see
 * UsageStatsProvider.history), so a longer range would mostly show empty days.
 */
private const val DAYS = 7
