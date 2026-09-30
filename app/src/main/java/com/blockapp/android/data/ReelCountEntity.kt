package com.blockapp.android.data

import androidx.room.Entity

/**
 * How many short-form videos were watched in [packageName] on [day]. Written only through
 * [BlockRepository.addReelsWatched], which ReelScrollDetector feeds from the accessibility
 * service.
 *
 * [day] is an ISO `yyyy-MM-dd` date in the device's zone at the moment of counting, so "today"
 * rolls over at the phone's local midnight rather than UTC's. Past days are kept rather than
 * pruned; at one row per app per day the table stays tiny.
 */
@Entity(tableName = "reel_counts", primaryKeys = ["day", "packageName"])
data class ReelCountEntity(
    val day: String,
    val packageName: String,
    val reels: Int,
)
