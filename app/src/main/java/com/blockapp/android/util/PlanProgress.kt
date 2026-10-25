package com.blockapp.android.util

import com.blockapp.android.data.PlanDayEntity
import com.blockapp.android.data.ReelDayTotal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * The reel plan's scorekeeping: what the days since the plan started add up to. Pure, so the
 * plan screen, Home and the reel wall all read one answer from the same rows (reel_counts plus
 * plan_days) and nothing here can drift from what was recorded.
 *
 * Three ideas carry it:
 *
 * - **A clean day is one the user stopped on their own**: within the day's limit and no Tier 3
 *   wall. Staying under the limit alone is no achievement, because the wall makes going over
 *   all but impossible. Hitting the wall still counts as a day on plan ([Outcome.HELD]), it
 *   just isn't clean, and it breaks the streak.
 * - **No data is not a win.** A day with no reels counts as reel-free only if the service was
 *   running that day (PlanDayEntity.tracked). Otherwise it's [Outcome.NO_DATA]: it neither
 *   breaks nor extends a streak, and it adds nothing to "reels skipped".
 * - **Today is in progress.** It's shown, never scored: a streak is complete days, ending
 *   yesterday, so the number doesn't drop at the first reel of the morning.
 */
object PlanProgress {

    enum class Outcome {
        /** Reels watched, within the limit, no wall. */
        CLEAN,

        /** Tracked, and not a single reel. */
        REEL_FREE,

        /** A wall closed a viewer at least once. Still on plan. */
        HELD,

        /** More reels than the day's limit: counted while blocking wasn't enforcing it. */
        OVER,

        /** Nothing was watching. */
        NO_DATA,

        TODAY,
    }

    data class Day(
        val date: LocalDate,
        val reels: Int,
        val watchMs: Long,
        /** Null when no limit applied. */
        val limit: Int?,
        val limitWalls: Int,
        val quietWalls: Int,
        val tracked: Boolean,
        val outcome: Outcome,
    ) {
        val walls: Int get() = limitWalls + quietWalls
    }

    enum class Phase(val title: String, val blurb: String) {
        FIRST_STEP("First step", "Getting used to a limit. Notice when you reach for reels."),
        CUTTING("Cutting down", "Each week takes a little more off. Keep stopping before the wall."),
        GOAL("At your goal", "This is the target. Make it the normal."),
        HOLDING("Holding steady", "The plan is done and the goal holds. Keep the streak going."),
    }

    data class Week(
        val index: Int,
        val start: LocalDate,
        val scheduled: Int,
        /** Average over the week's tracked, complete days, or null when there are none yet. */
        val average: Int?,
        val cleanDays: Int,
        val scoredDays: Int,
        val isCurrent: Boolean,
    )

    data class Milestone(
        val title: String,
        val detail: String,
        /** 0..1 progress; 1 means reached. */
        val progress: Float,
    ) {
        val reached: Boolean get() = progress >= 1f
    }

    data class Summary(
        val plan: ReelPlan.Plan,
        /** 1-based day of the plan today is. */
        val dayNumber: Int,
        val totalDays: Int,
        val phase: Phase,
        val streak: Int,
        val bestStreak: Int,
        val cleanDays: Int,
        val scoredDays: Int,
        val reelsSkipped: Int,
        val minutesBack: Int,
        val wallsThisWeek: Int,
        /** Typical day over the last seven tracked complete days, or null. */
        val recentAverage: Int?,
        /** recentAverage against the baseline, as a percentage drop (positive is fewer), or null. */
        val dropPercent: Int?,
        val today: Day,
        val days: List<Day>,
        val weeks: List<Week>,
        val milestones: List<Milestone>,
    ) {
        /** The next milestone not reached yet, the closest first. */
        val nextMilestone: Milestone? get() = milestones.filter { !it.reached }.maxByOrNull { it.progress }
    }

    /**
     * Joins [reelDays] and [planDays] for every day from the plan's start to [today].
     * Days are oldest first.
     */
    fun days(
        plan: ReelPlan.Plan,
        today: LocalDate,
        reelDays: List<ReelDayTotal>,
        planDays: List<PlanDayEntity>,
    ): List<Day> {
        val reels = reelDays.associateBy { it.day }
        val records = planDays.associateBy { it.day }
        val count = ChronoUnit.DAYS.between(plan.startDay, today).coerceAtLeast(0) + 1
        return (0 until count).map { offset ->
            val date = plan.startDay.plusDays(offset)
            val key = date.toString()
            val total = reels[key]
            val record = records[key]
            // A day with no record (the app wasn't opened and the service never ran) is judged
            // against the schedule, the best guess of what applied.
            val limit = when {
                record == null -> plan.scheduledLimit(date)
                record.limitReels == PlanDayEntity.NO_LIMIT -> null
                else -> record.limitReels
            }
            val reelCount = total?.reels ?: 0
            // Any reel counted proves the service was running, whatever the record says.
            val tracked = (record?.tracked ?: 0) > 0 || reelCount > 0
            val limitWalls = record?.limitWalls ?: 0
            val quietWalls = record?.quietWalls ?: 0
            val outcome = when {
                date == today -> Outcome.TODAY
                !tracked -> Outcome.NO_DATA
                limit != null && reelCount > limit -> Outcome.OVER
                limitWalls + quietWalls > 0 -> Outcome.HELD
                reelCount == 0 -> Outcome.REEL_FREE
                else -> Outcome.CLEAN
            }
            Day(date, reelCount, total?.watchMs ?: 0L, limit, limitWalls, quietWalls, tracked, outcome)
        }
    }

    fun summarize(plan: ReelPlan.Plan, today: LocalDate, days: List<Day>): Summary {
        val totalDays = plan.weeks * DAYS_PER_WEEK
        val dayNumber = (ChronoUnit.DAYS.between(plan.startDay, today).toInt() + 1).coerceAtLeast(1)
        val complete = days.filter { it.outcome != Outcome.TODAY }
        val scored = complete.filter { it.outcome != Outcome.NO_DATA }
        val isClean = { day: Day -> day.outcome == Outcome.CLEAN || day.outcome == Outcome.REEL_FREE }

        // Streaks run over scored days only: a day with no data is skipped, not a break.
        var best = 0
        var run = 0
        for (day in scored) {
            run = if (isClean(day)) run + 1 else 0
            best = maxOf(best, run)
        }
        val streak = run

        val skipped = scored.sumOf { (plan.baseline - it.reels).coerceAtLeast(0) }
        val recent = scored.takeLast(DAYS_PER_WEEK)
        val recentAverage = recent.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.reels } / list.size }
        val drop = recentAverage?.let { ((plan.baseline - it) * 100f / plan.baseline).roundToInt() }
        val weekStart = today.minusDays((DAYS_PER_WEEK - 1).toLong())
        val wallsThisWeek = days.filter { !it.date.isBefore(weekStart) }.sumOf { it.walls }

        val phase = when {
            dayNumber > totalDays -> Phase.HOLDING
            plan.weekOf(today) == plan.weeks - 1 -> Phase.GOAL
            plan.weekOf(today) == 0 -> Phase.FIRST_STEP
            else -> Phase.CUTTING
        }

        val weeks = (0 until plan.weeks).map { index ->
            val start = plan.startDay.plusWeeks(index.toLong())
            val end = start.plusDays((DAYS_PER_WEEK - 1).toLong())
            val inWeek = scored.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            Week(
                index = index,
                start = start,
                scheduled = plan.steps[index],
                average = inWeek.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.reels } / list.size },
                cleanDays = inWeek.count(isClean),
                scoredDays = inWeek.size,
                isCurrent = index == plan.weekOf(today) && dayNumber <= totalDays,
            )
        }

        val minutesBack = (skipped * REEL_SECONDS / 60f).roundToInt()
        val cleanDays = scored.count(isClean)
        val reelFreeDays = scored.count { it.outcome == Outcome.REEL_FREE }
        val goalDay = (plan.weeks - 1) * DAYS_PER_WEEK + 1
        val milestones = listOf(
            milestone("First clean day", "Stop before the wall for a whole day", cleanDays, 1),
            milestone("Three in a row", "Three clean days back to back", best, 3),
            milestone("A clean week", "Seven clean days in a row", best, 7),
            milestone("Two clean weeks", "Fourteen clean days in a row", best, 14),
            milestone("A reel-free day", "A whole day without a single reel", reelFreeDays, 1),
            milestone("100 reels skipped", "Against your old typical day", skipped, 100),
            milestone("1,000 reels skipped", "Against your old typical day", skipped, 1_000),
            milestone("5 hours back", "Time you'd have spent on reels", minutesBack, 5 * 60),
            milestone("A whole day back", "24 hours not spent on reels", minutesBack, 24 * 60),
            milestone("Reached the goal", "Made it to the last step", dayNumber, goalDay),
            milestone("Plan complete", "Every week of the plan done", dayNumber - 1, totalDays),
        )

        return Summary(
            plan = plan,
            dayNumber = dayNumber,
            totalDays = totalDays,
            phase = phase,
            streak = streak,
            bestStreak = best,
            cleanDays = cleanDays,
            scoredDays = scored.size,
            reelsSkipped = skipped,
            minutesBack = minutesBack,
            wallsThisWeek = wallsThisWeek,
            recentAverage = recentAverage,
            dropPercent = drop,
            today = days.lastOrNull { it.outcome == Outcome.TODAY }
                ?: Day(today, 0, 0L, plan.scheduledLimit(today), 0, 0, false, Outcome.TODAY),
            days = days,
            weeks = weeks,
            milestones = milestones,
        )
    }

    /**
     * A sentence or two about how it's going, from the numbers rather than from a pep-talk
     * list: the plan screen and the wall show the first that applies.
     */
    fun insights(summary: Summary): List<String> = buildList {
        val scored = summary.days.filter { it.outcome != Outcome.TODAY && it.outcome != Outcome.NO_DATA }
        val lastWeek = scored.takeLast(DAYS_PER_WEEK)
        val weekBefore = scored.dropLast(DAYS_PER_WEEK).takeLast(DAYS_PER_WEEK)
        if (lastWeek.size >= MIN_DAYS_TO_COMPARE && weekBefore.size >= MIN_DAYS_TO_COMPARE) {
            val now = lastWeek.sumOf { it.reels } / lastWeek.size
            val before = weekBefore.sumOf { it.reels } / weekBefore.size
            if (before > 0 && now < before) {
                add("${((before - now) * 100f / before).roundToInt()}% fewer reels a day than the week before.")
            } else if (now > before) {
                add("A heavier week than the one before. The limit doesn't move up, so it's still on track.")
            }
        }
        if (summary.streak >= 2) add("${summary.streak} clean days in a row. Today makes it ${summary.streak + 1}.")
        val quiet = lastWeek.sumOf { it.quietWalls }
        if (quiet > 0) add("Reel-free hours stepped in $quiet ${if (quiet == 1) "time" else "times"} this week.")
        val held = lastWeek.count { it.outcome == Outcome.HELD }
        if (held >= HARD_STEP_DAYS) {
            add("You've met the wall $held days this week. That's the step working. Try stopping a few reels before it.")
        }
        summary.dropPercent?.takeIf { it > 0 }?.let {
            add("Your typical day is $it% under where you started.")
        }
        if (isEmpty()) add("Stop before the wall today and it counts as a clean day.")
    }

    private fun milestone(title: String, detail: String, value: Int, target: Int) =
        Milestone(title, detail, (value.toFloat() / target).coerceIn(0f, 1f))

    private const val DAYS_PER_WEEK = 7

    /** A reel plus the swipe to the next. Same figure as the quiz's "hours back" estimate. */
    private const val REEL_SECONDS = 20f

    /** Fewer scored days than this in either week and a week-on-week change is noise. */
    private const val MIN_DAYS_TO_COMPARE = 3

    /** Walls on this many days in a week means the current step is a hard one. */
    private const val HARD_STEP_DAYS = 4
}
