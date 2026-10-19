package com.blockapp.android.util

import android.content.Context
import com.blockapp.android.data.BlockRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The reel plan: what the onboarding quiz (QuizScreen) turns into, and what drives the daily reel
 * limit from then on. Two outputs, both enforced by AppBlockAccessibilityService's Tier 3:
 *
 * - **A limit that steps down week by week**, from a little under the user's own estimate to a
 *   goal, and that moves ahead of schedule when the user is already doing better (see [refresh]).
 *   It is written into [ReelLimit] rather than enforced separately, so there is still exactly one
 *   limit and one "only ever tighten" rule.
 * - **Reel-free hours**, a daily window in which any reel viewer is closed at once.
 *
 * **Nothing here can loosen anything on the spot**, the same rule as locks (CLAUDE.md invariant 4)
 * and ReelLimit. [refresh] only ever lowers the limit. Stopping the plan, and narrowing or
 * removing reel-free hours (including by retaking the quiz), take effect from tomorrow. Otherwise
 * "retake the quiz, answer differently" would be an in-app way to switch reels back on mid-binge.
 *
 * Kept in SharedPreferences like ReelLimit: it is settings, not lock state, and needs no schema
 * bump. [quietWindowAt] is read on the service's main thread when a reel session opens, which is
 * a map lookup after the first load.
 */
object ReelPlan {

    enum class Goal(val title: String, val blurb: String) {
        CUT_DOWN("Cut down gently", "Small weekly steps. Hard to notice, easy to keep."),
        CUT_HARD("Cut down fast", "Big steps over three weeks."),
        QUIT("Quit reels", "Down to zero in two weeks. The rest of each app keeps working."),
    }

    enum class Moment(val title: String) {
        MORNING("In bed in the morning"),
        DAYTIME("During work or study"),
        EVENING("In the evening"),
        LATE_NIGHT("Late at night"),
    }

    /** What the quiz asked. [reelsPerDay] is null for "no idea", and is then estimated. */
    data class Answers(
        val apps: Set<String>,
        val reelsPerDay: Int?,
        val minutesPerDay: Int,
        val moments: Set<Moment>,
        val goal: Goal,
    )

    /** A daily window in local minutes since midnight. [end] before [start] crosses midnight. */
    data class QuietHours(val start: Int, val end: Int) {
        fun contains(minuteOfDay: Int): Boolean =
            if (start <= end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end

        /** True when every minute of [other] is also inside this window. */
        fun covers(other: QuietHours): Boolean =
            (0 until MINUTES_PER_DAY).none { other.contains(it) && !contains(it) }

        fun label(): String = "${clock(start)} – ${clock(end)}"
    }

    data class Plan(
        val startDay: LocalDate,
        val baseline: Int,
        val startLimit: Int,
        val targetLimit: Int,
        val weeks: Int,
        val goal: Goal,
    ) {
        /** The limit for each week of the plan, first week first. After the last, it holds. */
        val steps: List<Int> = List(weeks) { week ->
            if (weeks == 1) {
                targetLimit
            } else {
                roundDownTo5(startLimit - (startLimit - targetLimit) * week / (weeks - 1).toFloat())
                    .coerceAtLeast(targetLimit)
            }
        }

        /** 0-based week of the plan [day] falls in, capped at the last step. */
        fun weekOf(day: LocalDate): Int =
            (ChronoUnit.DAYS.between(startDay, day).coerceAtLeast(0) / 7).toInt().coerceAtMost(weeks - 1)

        fun scheduledLimit(day: LocalDate): Int = steps[weekOf(day)]
    }

    // ── the engine ───────────────────────────────────────────────────────────────────────────

    /**
     * Turns quiz answers into a plan. Pure, so QuizScreen can preview it before anything is
     * saved. The start sits below the user's own estimate on purpose: the first day should
     * already feel like a change, and people underestimate how many reels they watch.
     */
    fun build(answers: Answers, today: LocalDate = LocalDate.now()): Plan {
        val baseline = (answers.reelsPerDay ?: (answers.minutesPerDay * REELS_PER_MINUTE))
            .coerceIn(MIN_BASELINE, MAX_BASELINE)
        val (startShare, targetShare, weeks) = when (answers.goal) {
            Goal.CUT_DOWN -> Triple(0.8f, 0.3f, 6)
            Goal.CUT_HARD -> Triple(0.6f, 0.15f, 3)
            Goal.QUIT -> Triple(0.5f, 0f, 2)
        }
        val target = when (answers.goal) {
            Goal.QUIT -> 0
            else -> roundDownTo5(baseline * targetShare).coerceAtLeast(MIN_TARGET)
        }
        val start = roundDownTo5(baseline * startShare).coerceAtLeast(target)
        return Plan(today, baseline, start, target, weeks, answers.goal)
    }

    /** The reel-free hours the quiz suggests from the moments the user picked, or null. */
    fun suggestedQuietHours(moments: Set<Moment>): QuietHours? {
        val night = Moment.LATE_NIGHT in moments
        val morning = Moment.MORNING in moments
        return when {
            night && morning -> QuietHours(23 * 60, 9 * 60)
            night -> QuietHours(23 * 60, 7 * 60)
            morning -> QuietHours(5 * 60, 9 * 60)
            else -> null
        }
    }

    /**
     * Today's limit under the plan, given the reels actually watched on the last few complete
     * days (oldest first). This is the "works towards the user" part: once a typical recent day
     * plus some headroom is already under the scheduled step, the limit follows the user down
     * instead of waiting for the calendar. Never below the goal, never above the schedule.
     *
     * Zero days are left out, and the typical day is the median, not the mean. A day with no
     * count is far more often a day nothing was counted (Accessibility switched off by an
     * update, the phone off, a flight) than a day of no reels, and the old mean of the last
     * three days took it at face value: one such day pulled the average to a third, and since
     * the limit only ever goes down, it stayed near the goal for the rest of the plan.
     */
    fun limitFor(plan: Plan, day: LocalDate, recentDailyReels: List<Int>): Int {
        val scheduled = plan.scheduledLimit(day)
        val counted = recentDailyReels.takeLast(ADAPT_WINDOW_DAYS).filter { it > 0 }.sorted()
        if (counted.size < ADAPT_AFTER_DAYS) return scheduled
        val typical = counted[counted.size / 2].toDouble()
        val adaptive = roundUpTo5(typical * ADAPT_HEADROOM).coerceAtLeast(plan.targetLimit)
        return minOf(scheduled, adaptive)
    }

    // ── storage ──────────────────────────────────────────────────────────────────────────────

    /** True once the quiz was finished or skipped, so it is only offered on first launch. */
    fun quizSeen(context: Context): Boolean = prefs(context).getBoolean(QUIZ_SEEN, false)

    fun markQuizSeen(context: Context) {
        prefs(context).edit().putBoolean(QUIZ_SEEN, true).apply()
    }

    /** The running plan, or null. Applies a pending stop whose day has come. */
    fun current(context: Context): Plan? {
        val prefs = prefs(context)
        val stopFrom = prefs.getString(STOP_FROM, null)?.let(LocalDate::parse)
        if (stopFrom != null && !LocalDate.now().isBefore(stopFrom)) {
            prefs.edit().remove(PLAN_START).remove(STOP_FROM).apply()
        }
        val start = prefs.getString(PLAN_START, null)?.let(LocalDate::parse) ?: return null
        return Plan(
            startDay = start,
            baseline = prefs.getInt(BASELINE, 0),
            startLimit = prefs.getInt(START_LIMIT, 0),
            targetLimit = prefs.getInt(TARGET_LIMIT, 0),
            weeks = prefs.getInt(WEEKS, 1).coerceAtLeast(1),
            goal = Goal.entries.getOrElse(prefs.getInt(GOAL, 0)) { Goal.CUT_DOWN },
        )
    }

    /** Whether a stop is waiting for tomorrow. */
    fun stopPending(context: Context): Boolean {
        current(context)
        return prefs(context).contains(STOP_FROM)
    }

    /**
     * Starts [plan] with [quiet] hours, replacing any plan already running. Safe to call with
     * a "looser" plan: its limit can't raise ReelLimit (see [refresh]), and [setQuietHours]
     * delays any narrowing of the window to tomorrow.
     */
    fun start(context: Context, answers: Answers, plan: Plan, quiet: QuietHours?) {
        prefs(context).edit()
            .putString(PLAN_START, plan.startDay.toString())
            .putInt(BASELINE, plan.baseline)
            .putInt(START_LIMIT, plan.startLimit)
            .putInt(TARGET_LIMIT, plan.targetLimit)
            .putInt(WEEKS, plan.weeks)
            .putInt(GOAL, plan.goal.ordinal)
            .putInt(ANSWER_MINUTES, answers.minutesPerDay)
            .putStringSet(ANSWER_APPS, answers.apps)
            .remove(STOP_FROM)
            .putBoolean(QUIZ_SEEN, true)
            .apply()
        setQuietHours(context, quiet)
        val limit = ReelLimit.todayLimit(context)
        if (limit == null || plan.scheduledLimit(LocalDate.now()) < limit) {
            ReelLimit.set(context, plan.scheduledLimit(LocalDate.now()))
        }
    }

    /** Ends the plan from tomorrow. Today's limit and reel-free hours stay as they are. */
    fun stopFromTomorrow(context: Context) {
        prefs(context).edit().putString(STOP_FROM, LocalDate.now().plusDays(1).toString()).apply()
        setQuietHours(context, null)
    }

    /** The apps the user said pull them in, as quiz labels. Shown on the plan card. */
    fun answeredApps(context: Context): Set<String> =
        prefs(context).getStringSet(ANSWER_APPS, emptySet()).orEmpty()

    /**
     * Applies the plan to today's ReelLimit. Lowers it only, so it is safe to call as often as
     * convenient: on process start, on every Home resume, daily from UsageArchiveReceiver, and
     * at midnight from the service. The first form reads the recent days itself.
     * [recentDailyReels] are the last few complete days' reel totals, oldest first.
     */
    suspend fun refresh(context: Context, repository: BlockRepository) {
        if (current(context) == null) return
        refresh(context, repository.recentDailyReels(ADAPT_WINDOW_DAYS))
    }

    fun refresh(context: Context, recentDailyReels: List<Int>) {
        val plan = current(context) ?: return
        val today = LocalDate.now()
        val planned = limitFor(plan, today, recentDailyReels)
        val limit = ReelLimit.todayLimit(context)
        // Equal is left alone: ReelLimit.set with today's own value cancels a pending change.
        if (limit == null || planned < limit) ReelLimit.set(context, planned)
    }

    // ── reel-free hours ──────────────────────────────────────────────────────────────────────

    /** Today's reel-free hours, or null. Applies a pending change whose day has come. */
    fun quietHours(context: Context): QuietHours? {
        val prefs = prefs(context)
        val pendingFrom = prefs.getString(QUIET_PENDING_FROM, null)?.let(LocalDate::parse)
        if (pendingFrom != null && !LocalDate.now().isBefore(pendingFrom)) {
            prefs.edit()
                .putInt(QUIET_START, prefs.getInt(QUIET_PENDING_START, OFF))
                .putInt(QUIET_END, prefs.getInt(QUIET_PENDING_END, OFF))
                .remove(QUIET_PENDING_START)
                .remove(QUIET_PENDING_END)
                .remove(QUIET_PENDING_FROM)
                .apply()
        }
        return readQuiet(prefs.getInt(QUIET_START, OFF), prefs.getInt(QUIET_END, OFF))
    }

    /** The reel-free hours waiting for tomorrow, wrapped so "off tomorrow" is distinct from none. */
    fun pendingQuietHours(context: Context): Pair<QuietHours?, Boolean> {
        quietHours(context)
        val prefs = prefs(context)
        if (!prefs.contains(QUIET_PENDING_FROM)) return null to false
        return readQuiet(prefs.getInt(QUIET_PENDING_START, OFF), prefs.getInt(QUIET_PENDING_END, OFF)) to true
    }

    /** A window that covers today's applies now. Anything narrower, or off, waits for tomorrow. */
    fun setQuietHours(context: Context, quiet: QuietHours?) {
        val current = quietHours(context)
        val prefs = prefs(context)
        val tighter = quiet != null && (current == null || quiet.covers(current))
        if (quiet == current) {
            prefs.edit().remove(QUIET_PENDING_START).remove(QUIET_PENDING_END).remove(QUIET_PENDING_FROM).apply()
        } else if (tighter) {
            prefs.edit()
                .putInt(QUIET_START, quiet!!.start)
                .putInt(QUIET_END, quiet.end)
                .remove(QUIET_PENDING_START)
                .remove(QUIET_PENDING_END)
                .remove(QUIET_PENDING_FROM)
                .apply()
        } else {
            prefs.edit()
                .putInt(QUIET_PENDING_START, quiet?.start ?: OFF)
                .putInt(QUIET_PENDING_END, quiet?.end ?: OFF)
                .putString(QUIET_PENDING_FROM, LocalDate.now().plusDays(1).toString())
                .apply()
        }
    }

    /**
     * The reel-free window [nowMillis] falls in, as (start, end) epoch millis, or null if it is
     * outside one. The end is where the wall's countdown runs to.
     */
    fun quietWindowAt(context: Context, nowMillis: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val quiet = quietHours(context) ?: return null
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        val minute = now.hour * 60 + now.minute
        if (!quiet.contains(minute)) return null
        val today = now.toLocalDate()
        // Which calendar day this window started on: yesterday, for the after-midnight half
        // of a window that crosses midnight.
        val startDay = if (quiet.start > quiet.end && minute < quiet.end) today.minusDays(1) else today
        val start = startDay.atStartOfDay(zone).plusMinutes(quiet.start.toLong())
        val endDay = if (quiet.start > quiet.end) startDay.plusDays(1) else startDay
        val end = endDay.atStartOfDay(zone).plusMinutes(quiet.end.toLong())
        return start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
    }

    private fun readQuiet(start: Int, end: Int): QuietHours? =
        if (start < 0 || end < 0 || start == end) null else QuietHours(start, end)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun roundDownTo5(value: Float): Int = (value / 5f).toInt() * 5
    private fun roundUpTo5(value: Double): Int = (ceil(value / 5.0) * 5).roundToInt()

    private fun clock(minuteOfDay: Int): String {
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val suffix = if (hour < 12) "AM" else "PM"
        return if (minute == 0) "$h12 $suffix" else "$h12:${"%02d".format(minute)} $suffix"
    }

    /** A reel runs 15-30s, plus the scroll: about three a minute of reel time. */
    private const val REELS_PER_MINUTE = 3

    /** Below this an estimate is noise. Above it, a plan's first week would be no limit at all. */
    private const val MIN_BASELINE = 20
    private const val MAX_BASELINE = 600

    /** The lowest goal the cut-down plans aim for: a few reels a day is a realistic floor. */
    private const val MIN_TARGET = 5

    /**
     * Complete days with a count before the limit starts following the user down. Fewer than
     * three and a single quiet day (a flight, a busy Monday) would drag the limit to its floor.
     */
    private const val ADAPT_AFTER_DAYS = 3

    /** The recent days [limitFor] looks at, of which ADAPT_AFTER_DAYS must have a count. */
    private const val ADAPT_WINDOW_DAYS = 5

    /** Headroom over the recent average, so following the user down never lands on a wall at once. */
    private const val ADAPT_HEADROOM = 1.15

    private const val MINUTES_PER_DAY = 24 * 60

    private const val PREFS = "reel_plan"
    private const val QUIZ_SEEN = "quiz_seen"
    private const val PLAN_START = "start"
    private const val BASELINE = "baseline"
    private const val START_LIMIT = "start_limit"
    private const val TARGET_LIMIT = "target_limit"
    private const val WEEKS = "weeks"
    private const val GOAL = "goal"
    private const val ANSWER_MINUTES = "answer_minutes"
    private const val ANSWER_APPS = "answer_apps"
    private const val STOP_FROM = "stop_from"
    private const val QUIET_START = "quiet_start"
    private const val QUIET_END = "quiet_end"
    private const val QUIET_PENDING_START = "quiet_pending_start"
    private const val QUIET_PENDING_END = "quiet_pending_end"
    private const val QUIET_PENDING_FROM = "quiet_pending_from"
    private const val OFF = -1
}
