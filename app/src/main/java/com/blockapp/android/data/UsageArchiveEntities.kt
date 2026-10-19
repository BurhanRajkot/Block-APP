package com.blockapp.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One local day of screen time, copied out of the system's usage events (UsageArchive).
 *
 * The copy exists because the system only keeps raw usage events for about a week, so without it
 * the History screen could never show anything older. It is also what Auto Backup carries over
 * a reinstall, along with reel_counts. Added in schema v4; MIGRATION_3_4 must match it column for
 * column (CLAUDE.md invariant 7).
 *
 * [hourly] is 24 comma-separated millisecond totals, midnight first: a day is only ever read
 * whole, and a 24-column table or a child table would be more schema for no query that needs it.
 */
@Entity(tableName = "usage_days")
data class UsageDayEntity(
    @PrimaryKey val day: String,
    val totalMs: Long,
    val opens: Int,
    val unlocks: Int,
    val hourly: String,
)

/**
 * One app's share of a [UsageDayEntity]. [label] is stored, not looked up, so an app that has
 * since been uninstalled (or a history restored onto a new phone) still shows its name.
 */
@Entity(tableName = "usage_apps", primaryKeys = ["day", "packageName"])
data class UsageAppEntity(
    val day: String,
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val opens: Int,
)

/** An app's total over a range of days, for the week and month views. */
data class UsageAppTotal(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val opens: Int,
)

/** An app's reel total over a range of days. */
data class ReelAppTotal(
    val packageName: String,
    val reels: Int,
    val watchMs: Long,
)
