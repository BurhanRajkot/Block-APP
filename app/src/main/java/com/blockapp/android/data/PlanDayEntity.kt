package com.blockapp.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What one day looked like to the reel plan (schema v5), alongside that day's reel_counts rows.
 * util/PlanProgress turns these into streaks, milestones and the weekly review.
 *
 * - [limitReels] is the limit in force at the end of the day, or [NO_LIMIT]. Recorded rather than
 *   recomputed, because the plan adapts: the day's limit depends on the days before it, and a
 *   later rebuild would rewrite the schedule the past was judged against.
 * - [limitWalls] and [quietWalls] count the times Tier 3 closed a reel viewer that day, for the
 *   limit and for reel-free hours. A day the user stopped on their own is a day with neither,
 *   which is what a "clean" day means.
 * - [tracked] is 1 once the accessibility service was running at some point that day. A day with
 *   no reels is only a reel-free day if something was watching; otherwise it's a day with no
 *   data (Accessibility off after an update, the phone off), and counting it as a win inflated
 *   "reels skipped" and streaks.
 *
 * Written only through BlockRepository's plan-day methods. Never deleted.
 */
@Entity(tableName = "plan_days")
data class PlanDayEntity(
    @PrimaryKey val day: String,
    val limitReels: Int = NO_LIMIT,
    val limitWalls: Int = 0,
    val quietWalls: Int = 0,
    val tracked: Int = 0,
) {
    companion object {
        const val NO_LIMIT = -1
    }
}
