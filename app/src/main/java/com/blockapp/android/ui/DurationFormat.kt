package com.blockapp.android.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Verbose remaining time for list rows: `2h 05m 30s`, `5m 04s`, `9s`. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm %02ds".format(hours, minutes, seconds)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}

/** Clock-style remaining time for the big countdown on the block screen: `1:04:09`, `4:09`. */
internal fun formatCountdown(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Wall-clock time a lock lifts. Shown alongside the countdown because a bare "6h 12m" gives no
 * sense of when the lock actually ends — the weekday prefix appears only when that time falls on
 * another day, so an overnight lock can't be misread as ending this evening.
 */
internal fun formatUnlockAt(blockUntil: Long): String {
    val now = Calendar.getInstance()
    val end = Calendar.getInstance().apply { timeInMillis = blockUntil }
    val sameDay = now.get(Calendar.YEAR) == end.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == end.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "h:mm a" else "EEE h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(blockUntil))
}
