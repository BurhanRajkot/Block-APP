package com.blockapp.android.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.BlockApplication
import com.blockapp.android.data.ReelAppTotal
import com.blockapp.android.data.ReelDayTotal
import com.blockapp.android.data.UsageDayEntity
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.usage.AppUsage
import com.blockapp.android.usage.UsageArchive
import com.blockapp.android.usage.UsageStatsProvider
import kotlinx.coroutines.flow.collectLatest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private enum class Span(val label: String) { Day("Day"), Week("Week"), Month("Month") }

private enum class Metric(val label: String) { Reels("Reels"), ScreenTime("Screen time") }

/**
 * The History tab: any day, week or month since the app was installed, as far back as the
 * archive goes. Everything here is read from Room, not from the system: screen time from the
 * usage archive (UsageArchive copies it out of the system's week of usage events every time this
 * screen opens), reels from reel_counts. That is what lets a day from three months ago open at
 * all, and what Auto Backup brings back after a reinstall.
 *
 * Each half still degrades on its own. Without Usage access screen time stops growing (the
 * archive keeps what it had), and reels need only Accessibility.
 */
@Composable
fun HistoryScreen(
    initialDay: LocalDate? = null,
    onLockApp: (packageName: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication

    var span by rememberSaveable { mutableStateOf(if (initialDay != null) Span.Day else Span.Month) }
    var focusEpochDay by rememberSaveable { mutableStateOf((initialDay ?: LocalDate.now()).toEpochDay()) }
    var metric by rememberSaveable { mutableStateOf(Metric.Reels) }
    val focus = LocalDate.ofEpochDay(focusEpochDay)

    var hasAccess by remember { mutableStateOf(true) }
    var resumes by remember { mutableIntStateOf(0) }
    var earliest by remember { mutableStateOf<LocalDate?>(null) }
    var lockedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    val today = remember(resumes) { LocalDate.now() }
    val installed = remember { installDay(context) }
    val firstDay = minOf(earliest ?: today, installed, today)

    // Archive first, so today and the last week are in Room before they're read. Re-run on every
    // resume: a day's figures keep growing while the user is away, and usage access may have just
    // been granted in Settings.
    LifecycleResumeEffect(Unit) {
        hasAccess = UsageStatsProvider.hasUsageAccess(context)
        resumes++
        onPauseOrDispose {}
    }
    LaunchedEffect(resumes) {
        UsageArchive.archiveRecent(context, app.repository)
        earliest = app.repository.earliestRecordedDay()
    }
    LaunchedEffect(Unit) {
        app.repository.activeLocks.collectLatest { lockedPackages = it.keys }
    }

    val range = when (span) {
        Span.Day -> focus to focus
        Span.Week -> weekStart(focus) to weekStart(focus).plusDays(6)
        Span.Month -> YearMonth.from(focus).atDay(1) to YearMonth.from(focus).atEndOfMonth()
    }

    var usageDays by remember { mutableStateOf<Map<LocalDate, UsageDayEntity>>(emptyMap()) }
    var reelDays by remember { mutableStateOf<Map<LocalDate, ReelDayTotal>>(emptyMap()) }
    var apps by remember { mutableStateOf<List<AppUsage>?>(null) }
    var reelApps by remember { mutableStateOf<List<ReelAppTotal>>(emptyList()) }

    LaunchedEffect(range) {
        app.repository.observeUsageDays(range.first, range.second).collectLatest { list ->
            usageDays = list.associateBy { LocalDate.parse(it.day) }
        }
    }
    LaunchedEffect(range) {
        app.repository.observeReelDays(range.first, range.second).collectLatest { list ->
            reelDays = list.associateBy { LocalDate.parse(it.day) }
        }
    }
    LaunchedEffect(range, span) {
        apps = null
        if (span == Span.Day) {
            app.repository.observeUsageApps(range.first).collectLatest { list ->
                apps = list.map { AppUsage(it.packageName, it.label, it.foregroundMs, it.opens) }
            }
        } else {
            app.repository.observeUsageAppTotals(range.first, range.second).collectLatest { list ->
                apps = list.map { AppUsage(it.packageName, it.label, it.foregroundMs, it.opens) }
            }
        }
    }
    LaunchedEffect(range) {
        app.repository.observeReelApps(range.first, range.second).collectLatest { reelApps = it }
    }

    fun move(step: Long) {
        val next = when (span) {
            Span.Day -> focus.plusDays(step)
            Span.Week -> focus.plusWeeks(step)
            Span.Month -> focus.plusMonths(step)
        }
        focusEpochDay = next.coerceIn(firstDay, today).toEpochDay()
    }

    val canBack = range.first > firstDay
    val canForward = range.second < today

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 28.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    "History",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                Text(
                    "Every day since ${firstDay.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SegmentedToggle(
                    options = Span.entries,
                    selected = span,
                    label = { it.label },
                    onSelect = { span = it },
                )
                Spacer(Modifier.height(12.dp))
                PeriodStepper(
                    label = periodLabel(span, range, today),
                    order = range.first.toEpochDay(),
                    canBack = canBack,
                    canForward = canForward,
                    onBack = { move(-1) },
                    onForward = { move(1) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        when (span) {
            Span.Month -> monthContent(
                month = YearMonth.from(focus),
                metric = metric,
                onMetric = { metric = it },
                usageDays = usageDays,
                reelDays = reelDays,
                firstDay = firstDay,
                today = today,
                onPickDay = {
                    focusEpochDay = it.toEpochDay()
                    span = Span.Day
                },
            )
            Span.Week -> weekContent(
                start = range.first,
                usageDays = usageDays,
                reelDays = reelDays,
                firstDay = firstDay,
                today = today,
                onPickDay = {
                    focusEpochDay = it.toEpochDay()
                    span = Span.Day
                },
            )
            Span.Day -> dayContent(
                day = focus,
                isToday = focus == today,
                usage = usageDays[focus],
                reels = reelDays[focus],
            )
        }

        if (reelApps.isNotEmpty()) {
            item(key = "reel-apps") {
                Column {
                    SectionHeader("Reels by app")
                    ReelBreakdown(reelApps)
                }
            }
        }

        item(key = "apps") {
            Column {
                SectionHeader(if (span == Span.Day) "Apps" else "Top apps")
                val list = apps
                when {
                    !hasAccess && list.isNullOrEmpty() -> Panel {
                        UsageAccessPrompt(onGrant = { openUsageAccessSettings(context) })
                    }
                    list == null -> LoadingRows(count = 3)
                    list.isEmpty() -> EmptyNote("No screen time recorded for this ${span.label.lowercase()}.")
                    else -> {
                        val reelsByPackage = reelApps.associate { it.packageName to it.reels }
                        Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                            val shown = list.take(MAX_APPS)
                            val max = shown.first().foregroundMillis
                            shown.forEachIndexed { index, usage ->
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
                }
            }
        }
    }
}

// ── month ─────────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.monthContent(
    month: YearMonth,
    metric: Metric,
    onMetric: (Metric) -> Unit,
    usageDays: Map<LocalDate, UsageDayEntity>,
    reelDays: Map<LocalDate, ReelDayTotal>,
    firstDay: LocalDate,
    today: LocalDate,
    onPickDay: (LocalDate) -> Unit,
) {
    item(key = "month-calendar") {
        val color = if (metric == Metric.Reels) Ember else Lime
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(
                    if (metric == Metric.Reels) "Reels per day" else "Screen time per day",
                    color = color,
                    modifier = Modifier.weight(1f),
                )
                Metric.entries.forEach { option ->
                    val selected = option == metric
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            )
                            .clickable { onMetric(option) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            HeatCalendar(
                month = month,
                valueOf = { day ->
                    if (metric == Metric.Reels) {
                        (reelDays[day]?.reels ?: 0).toLong()
                    } else {
                        usageDays[day]?.totalMs ?: 0L
                    }
                },
                color = color,
                selected = null,
                enabled = { !it.isBefore(firstDay) && !it.isAfter(today) },
                onSelect = onPickDay,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Tap a day to open it. Brighter is more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item(key = "month-summary") {
        val reels = reelDays.values.sumOf { it.reels }
        val screen = usageDays.values.sumOf { it.totalMs }
        val trackedDays = daysTracked(month.atDay(1), month.atEndOfMonth(), firstDay, today)
        val cleanDays = (0 until trackedDays.toInt())
            .map { maxOf(month.atDay(1), firstDay).plusDays(it.toLong()) }
            .count { (reelDays[it]?.reels ?: 0) == 0 }
        Spacer(Modifier.height(12.dp))
        SummaryRow(
            reels = reels,
            screenMs = screen,
            days = trackedDays,
            cleanDays = cleanDays,
        )
    }
}

// ── week ──────────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.weekContent(
    start: LocalDate,
    usageDays: Map<LocalDate, UsageDayEntity>,
    reelDays: Map<LocalDate, ReelDayTotal>,
    firstDay: LocalDate,
    today: LocalDate,
    onPickDay: (LocalDate) -> Unit,
) {
    val days = List(7) { start.plusDays(it.toLong()) }
    val labels = days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    val highlight = days.indexOf(today).takeIf { it >= 0 }
    item(key = "week-charts") {
        Column {
            Panel {
                val reels = days.map { (reelDays[it]?.reels ?: 0).toLong() }
                Eyebrow("Reels", color = Ember)
                Spacer(Modifier.height(6.dp))
                AnimatedCount(reels.sum().toInt(), MaterialTheme.typography.displayMedium, color = Ember)
                Spacer(Modifier.height(16.dp))
                BarChart(
                    values = reels,
                    color = Ember,
                    highlight = highlight,
                    labels = labels,
                    onSelect = { index -> days[index].takeIf { it in firstDay..today }?.let(onPickDay) },
                    chartHeight = 110.dp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                val screen = days.map { usageDays[it]?.totalMs ?: 0L }
                Eyebrow("Screen time")
                Spacer(Modifier.height(6.dp))
                AnimatedDuration(screen.sum(), MaterialTheme.typography.displayMedium)
                val tracked = daysTracked(days.first(), days.last(), firstDay, today).coerceAtLeast(1)
                Text(
                    "${formatHoursMinutes(screen.sum() / tracked)} a day on average",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                BarChart(
                    values = screen,
                    color = Lime,
                    highlight = highlight,
                    labels = labels,
                    onSelect = { index -> days[index].takeIf { it in firstDay..today }?.let(onPickDay) },
                    chartHeight = 110.dp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap a bar to open that day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── day ───────────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.dayContent(
    day: LocalDate,
    isToday: Boolean,
    usage: UsageDayEntity?,
    reels: ReelDayTotal?,
) {
    item(key = "day-top") {
        Column {
            Panel {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Reels", color = Ember)
                        Spacer(Modifier.height(4.dp))
                        AnimatedCount(reels?.reels ?: 0, MaterialTheme.typography.displayLarge, color = Ember)
                    }
                    if ((reels?.watchMs ?: 0L) > 0L) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatHoursMinutes(reels!!.watchMs), style = MaterialTheme.typography.titleLarge)
                            Text(
                                "spent watching",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                Eyebrow("Screen time")
                Spacer(Modifier.height(6.dp))
                if (usage == null) {
                    Text("—", style = MaterialTheme.typography.displayMedium)
                    Text(
                        if (isToday) "Waiting for usage access." else "Nothing was recorded on this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AnimatedDuration(usage.totalMs, MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        AnimatedMiniStat(usage.unlocks, "unlocks", Modifier.weight(1f))
                        AnimatedMiniStat(usage.opens, "app opens", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(18.dp))
                    BarChart(
                        values = parseHourly(usage.hourly),
                        color = Lime,
                        highlight = if (isToday) LocalTime.now().hour else null,
                        chartHeight = 110.dp,
                    )
                    HourAxis()
                }
            }
        }
    }
}

// ── pieces ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SummaryRow(reels: Int, screenMs: Long, days: Long, cleanDays: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            Eyebrow("Reels", color = Ember)
            Spacer(Modifier.height(4.dp))
            AnimatedCount(reels, MaterialTheme.typography.headlineMedium, color = Ember)
            Text(
                "${if (days > 0) reels / days else 0} a day",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Panel(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            Eyebrow("Screen")
            Spacer(Modifier.height(4.dp))
            Text(formatHoursMinutes(screenMs), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Text(
                "${formatHoursMinutes(if (days > 0) screenMs / days else 0L)} a day",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Panel(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            Eyebrow("Reel-free")
            Spacer(Modifier.height(4.dp))
            AnimatedCount(cleanDays, MaterialTheme.typography.headlineMedium)
            Text(
                if (cleanDays == 1) "day" else "days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReelBreakdown(reels: List<ReelAppTotal>) {
    Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        val max = reels.maxOf { it.reels }.coerceAtLeast(1)
        reels.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val identity = rememberAppIdentity(entry.packageName)
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

@Composable
private fun PeriodStepper(
    label: String,
    order: Long,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = canBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier")
        }
        // The label slides the way the user is moving through time.
        AnimatedContent(
            targetState = label to order,
            transitionSpec = {
                val forward = targetState.second > initialState.second
                (slideInHorizontally { if (forward) it / 2 else -it / 2 } + fadeIn())
                    .togetherWith(slideOutHorizontally { if (forward) -it / 2 else it / 2 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "period",
        ) { (text, _) ->
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onForward, enabled = canForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later")
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

private fun periodLabel(span: Span, range: Pair<LocalDate, LocalDate>, today: LocalDate): String {
    val (from, to) = range
    return when (span) {
        Span.Day -> when (from) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> from.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
        }
        Span.Week -> if (today in from..to) {
            "This week"
        } else {
            val pattern = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            "${from.format(pattern)} – ${to.format(pattern)}"
        }
        Span.Month -> from.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }
}

private fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Days of [from]..[to] that fall between the first recorded day and today, for averages. */
private fun daysTracked(from: LocalDate, to: LocalDate, firstDay: LocalDate, today: LocalDate): Long {
    val start = maxOf(from, firstDay)
    val end = minOf(to, today)
    return if (end.isBefore(start)) 0L else end.toEpochDay() - start.toEpochDay() + 1
}

private fun parseHourly(hourly: String): List<Long> {
    val values = hourly.split(',').map { it.toLongOrNull() ?: 0L }
    return if (values.size == HOURS) values else List(HOURS) { values.getOrElse(it) { 0L } }
}

/**
 * The day this app was first installed on this phone. A reinstall resets it, which is why
 * History also goes back to the earliest day in the (backed-up) archive, whichever is older.
 */
private fun installDay(context: Context): LocalDate {
    val installed = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrDefault(System.currentTimeMillis())
    return Instant.ofEpochMilli(installed).atZone(ZoneId.systemDefault()).toLocalDate()
}

private const val HOURS = 24

/** A month of a busy phone can list a hundred apps; past this the tail says nothing. */
private const val MAX_APPS = 15
