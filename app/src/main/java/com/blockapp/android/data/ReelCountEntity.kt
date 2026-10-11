package com.blockapp.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * How many short-form videos were watched in [packageName] on [day], and roughly how long was
 * spent in that app's reel viewer. Written only through [BlockRepository.addReelsWatched], which
 * the accessibility service's ReelTracker feeds.
 *
 * [day] is an ISO `yyyy-MM-dd` date in the device's zone at the moment of counting, so "today"
 * rolls over at the phone's local midnight rather than UTC's. Past days are kept rather than
 * pruned; at one row per app per day the table stays tiny.
 *
 * [watchMs] was added in schema v3. Its `defaultValue` must stay "0": MIGRATION_2_3 adds the
 * column with `DEFAULT 0`, and Room compares the default too when it validates the migrated table.
 */
@Entity(tableName = "reel_counts", primaryKeys = ["day", "packageName"])
data class ReelCountEntity(
    val day: String,
    val packageName: String,
    val reels: Int,
    @ColumnInfo(defaultValue = "0") val watchMs: Long = 0L,
)

/** One day's reel totals across every app, for the history charts on InsightsScreen. */
data class ReelDayTotal(
    val day: String,
    val reels: Int,
    val watchMs: Long,
)
