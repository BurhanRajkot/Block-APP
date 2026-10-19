package com.blockapp.android.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.blockapp.android.util.InstalledAppsProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class AppUsage(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long,
    /** Times the app was brought to the front from somewhere else (or from a screen-off). */
    val opens: Int,
)

/** One local calendar day of screen time. */
class DayUsage(
    val day: LocalDate,
    /** Most-used first. Only apps with a launcher icon; see [UsageStatsProvider.history]. */
    val apps: List<AppUsage>,
    /** Foreground millis per local hour of the day, index 0 = midnight to 1am. */
    val hourly: LongArray,
    /** Times the lock screen was dismissed. Always 0 below API 28, which has no such event. */
    val unlocks: Int,
) {
    val totalMillis: Long = apps.sumOf { it.foregroundMillis }
    val totalOpens: Int = apps.sumOf { it.opens }

    companion object {
        fun empty(day: LocalDate) = DayUsage(day, emptyList(), LongArray(HOURS_PER_DAY), 0)
    }
}

object UsageStatsProvider {

    /**
     * PACKAGE_USAGE_STATS is a special app-op granted from the "Usage access" settings list,
     * not a normal runtime permission — there's no ActivityResultContract for it, so callers
     * have to poll this (typically on resume, after sending the user to that settings screen).
     */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The last [days] local days of screen time, oldest first, today last. Blocking (it walks
     * every usage event in the range), so call it off the main thread.
     *
     * Built from the raw activity resume/pause event stream, not from queryUsageStats. The
     * aggregated buckets that queryUsageStats returns overlap the requested range instead of
     * being clipped to it. That is why the old "today" figure could include part of
     * yesterday, and the buckets can't be split by hour at all. Replaying events gives exact
     * per-day and per-hour numbers and an open count.
     *
     * Only launchable apps are reported (see [InstalledAppsProvider.launchablePackages], which
     * also drops this app and the protected system packages). Every package still takes part
     * in the replay, though. Pressing home must end the previous app's session even though the
     * launcher itself is never listed.
     *
     * The system only keeps a limited window of raw events, typically a week or more. A day
     * older than that comes back empty rather than wrong.
     */
    fun history(context: Context, days: Int): List<DayUsage> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = today.minusDays(days - 1L)
        val rangeStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        // Packages only. Labels are resolved for the few apps that end up in the result, not
        // for every installed app (see InstalledAppsProvider.label for why that matters).
        val launchable = InstalledAppsProvider.launchablePackages(context)
        val acc = Array(days) { DayAccumulator() }

        fun dayIndex(millis: Long): Int =
            ChronoUnit.DAYS.between(firstDay, Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
                .toInt()

        // Splits [start, end) at local hour boundaries, which also splits it at midnight.
        fun record(pkg: String, start: Long, end: Long) {
            if (pkg !in launchable) return
            var cursor = maxOf(start, rangeStart)
            val stop = minOf(end, now)
            while (cursor < stop) {
                val at = Instant.ofEpochMilli(cursor).atZone(zone)
                val hourEnd = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val chunkEnd = minOf(stop, hourEnd)
                val index = dayIndex(cursor)
                if (index in 0 until days) {
                    val day = acc[index]
                    val chunk = chunkEnd - cursor
                    day.millis[pkg] = (day.millis[pkg] ?: 0L) + chunk
                    day.hourly[at.hour] += chunk
                }
                cursor = chunkEnd
            }
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // Starts LOOKBACK_MS early so an app already open at the first midnight has its resume
        // event in the stream. Without it, that session has a pause but no resume, and it is
        // dropped. record() clips the extra hours back out.
        val events = usm.queryEvents(rangeStart - LOOKBACK_MS, now) ?: return emptyResult(firstDay, days)

        // Resumed activities per package. A package's session runs while it has at least one.
        // Counting activities rather than flagging the package matters for multi-activity apps.
        // When activity B of an app starts on top of A, A's pause must not end the app's session
        // while B is still in front.
        val resumed = HashMap<String, MutableSet<String>>()
        val sessionStart = HashMap<String, Long>()
        var lastEndedPackage: String? = null
        var lastEndedAt = 0L

        fun endSession(pkg: String, at: Long) {
            val start = sessionStart.remove(pkg) ?: return
            record(pkg, start, at)
            lastEndedPackage = pkg
            lastEndedAt = at
        }

        fun endAll(at: Long) {
            sessionStart.keys.toList().forEach { endSession(it, at) }
            resumed.clear()
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            val pkg = event.packageName ?: continue
            val at = event.timeStamp
            when (event.eventType) {
                EVENT_RESUMED -> {
                    // A different app coming to the front ends whatever was in front before.
                    // The pause for it is usually already in the stream, but not always (a
                    // process killed in the background, events dropped under load), and without
                    // this the old app went on accumulating time in parallel with the new one
                    // until the screen next turned off. Split-screen, where two apps are really
                    // both resumed, is undercounted instead, which is the better error here.
                    sessionStart.keys.filter { it != pkg }.forEach { other ->
                        resumed.remove(other)
                        endSession(other, at)
                    }
                    val activities = resumed.getOrPut(pkg) { HashSet() }
                    activities += event.className.orEmpty()
                    if (pkg !in sessionStart) {
                        sessionStart[pkg] = at
                        // A quick hop between two activities of one app (pause, then resume a
                        // moment later) is the same visit, not a second open.
                        val sameVisit = pkg == lastEndedPackage && at - lastEndedAt < REOPEN_GAP_MS
                        if (!sameVisit && at >= rangeStart && pkg in launchable) {
                            val index = dayIndex(at)
                            if (index in 0 until days) {
                                val day = acc[index]
                                day.opens[pkg] = (day.opens[pkg] ?: 0) + 1
                            }
                        }
                    }
                }
                EVENT_PAUSED, EVENT_STOPPED -> {
                    val activities = resumed[pkg] ?: continue
                    activities -= event.className.orEmpty()
                    if (activities.isEmpty()) {
                        resumed.remove(pkg)
                        endSession(pkg, at)
                    }
                }
                // Screen-off ends everything. Some devices keep an activity resumed behind the
                // keyguard, and without this the time the screen spent off would be counted.
                EVENT_SCREEN_OFF, EVENT_SHUTDOWN -> endAll(at)
                EVENT_KEYGUARD_HIDDEN -> if (at >= rangeStart) {
                    val index = dayIndex(at)
                    if (index in 0 until days) acc[index].unlocks++
                }
            }
        }
        endAll(now)

        return acc.mapIndexed { index, day ->
            DayUsage(
                day = firstDay.plusDays(index.toLong()),
                apps = day.millis
                    .filterValues { it >= MIN_REPORTED_MS }
                    .map { (pkg, millis) ->
                        AppUsage(pkg, InstalledAppsProvider.label(context, pkg), millis, day.opens[pkg] ?: 0)
                    }
                    .sortedByDescending { it.foregroundMillis },
                hourly = day.hourly,
                unlocks = day.unlocks,
            )
        }
    }

    private fun emptyResult(firstDay: LocalDate, days: Int) =
        List(days) { DayUsage.empty(firstDay.plusDays(it.toLong())) }

    private class DayAccumulator {
        val millis = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val hourly = LongArray(HOURS_PER_DAY)
        var unlocks = 0
    }

    // Raw values rather than the named constants: ACTIVITY_RESUMED/PAUSED/STOPPED were only
    // named in API 29 (the first two under their old MOVE_TO_FOREGROUND/BACKGROUND names
    // before that), and SCREEN_NON_INTERACTIVE/KEYGUARD_HIDDEN in API 28. The values are the
    // same on every release, and older devices simply never send the newer types.
    private const val EVENT_RESUMED = 1
    private const val EVENT_PAUSED = 2
    private const val EVENT_SCREEN_OFF = 16
    private const val EVENT_KEYGUARD_HIDDEN = 18
    private const val EVENT_STOPPED = 23
    private const val EVENT_SHUTDOWN = 26

    /**
     * How far before the first day the replay starts. An app left open longer than this
     * across midnight loses the part before the lookback; longer means more events to walk.
     */
    private const val LOOKBACK_MS = 3 * 60 * 60_000L

    /** A resume this soon after the same app's last pause continues that visit. */
    private const val REOPEN_GAP_MS = 2_000L

    /**
     * Below this an app is left out of the list. Brief flashes, like an app that opens and
     * immediately hands off to another, would otherwise fill the bottom of the list with "0m".
     */
    private const val MIN_REPORTED_MS = 5_000L
}

internal const val HOURS_PER_DAY = 24
