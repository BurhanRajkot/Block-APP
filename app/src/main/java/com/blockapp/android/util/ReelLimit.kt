package com.blockapp.android.util

import android.content.Context
import java.time.LocalDate

/**
 * The user's daily reel limit: once today's reel total reaches it, AppBlockAccessibilityService
 * backs out of any reel viewer that opens and shows the "reel limit reached" wall until midnight.
 *
 * Kept in SharedPreferences, like FocusModeSelection, since it's a setting rather than lock
 * state and needs no schema bump. Read on the service's main thread on every counted reel;
 * SharedPreferences caches in memory after the first load, so that is a map lookup.
 *
 * **Tightening is immediate, loosening waits until tomorrow.** Same reasoning as locks only
 * ever extending (CLAUDE.md invariant 4): if raising or removing the limit took effect at once,
 * the limit would be one tap away from not existing at the exact moment it matters. So a lower
 * limit (or a first one) applies now, and a higher one or "off" is stored as pending and
 * applied on the first read on or after the next local midnight.
 */
object ReelLimit {

    /** Offered in the picker. Anything else goes through the custom field. */
    val PRESETS = listOf(25, 50, 100, 150, 200)

    enum class Change { APPLIED_NOW, FROM_TOMORROW, UNCHANGED }

    /**
     * Today's limit, or null for none. 0 is a real limit, "no reels today", which the quit-reels
     * plan (ReelPlan) ends on; only [OFF] means none. Applies a pending change whose day has come.
     */
    fun todayLimit(context: Context): Int? {
        val prefs = prefs(context)
        val pendingFrom = prefs.getString(PENDING_FROM, null)?.let(LocalDate::parse)
        if (pendingFrom != null && !LocalDate.now().isBefore(pendingFrom)) {
            prefs.edit()
                .putInt(LIMIT, prefs.getInt(PENDING_LIMIT, OFF))
                .remove(PENDING_LIMIT)
                .remove(PENDING_FROM)
                .apply()
        }
        return prefs.getInt(LIMIT, OFF).takeIf { it >= 0 }
    }

    /** A loosening that takes effect tomorrow. [limit] null means the limit is being turned off. */
    data class Pending(val limit: Int?)

    /** The loosening waiting for tomorrow, or null if there is none. */
    fun pending(context: Context): Pending? {
        todayLimit(context) // applies it first if its day has already come
        val prefs = prefs(context)
        if (!prefs.contains(PENDING_FROM)) return null
        return Pending(prefs.getInt(PENDING_LIMIT, OFF).takeIf { it >= 0 })
    }

    /** Sets the limit to [limit] (null = off), immediately or from tomorrow; see the class doc. */
    fun set(context: Context, limit: Int?): Change {
        require(limit == null || limit >= 0) { "negative reel limit" }
        val current = todayLimit(context)
        val prefs = prefs(context)
        val tighter = limit != null && (current == null || limit < current)
        return when {
            limit == current -> {
                // Choosing today's value again cancels a pending loosening.
                prefs.edit().remove(PENDING_LIMIT).remove(PENDING_FROM).apply()
                Change.UNCHANGED
            }
            tighter -> {
                prefs.edit()
                    .putInt(LIMIT, limit ?: OFF)
                    .remove(PENDING_LIMIT)
                    .remove(PENDING_FROM)
                    .apply()
                Change.APPLIED_NOW
            }
            else -> {
                prefs.edit()
                    .putInt(PENDING_LIMIT, limit ?: OFF)
                    .putString(PENDING_FROM, LocalDate.now().plusDays(1).toString())
                    .apply()
                Change.FROM_TOMORROW
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "reel_limit"
    private const val LIMIT = "limit"
    private const val PENDING_LIMIT = "pending_limit"
    private const val PENDING_FROM = "pending_from"
    private const val OFF = -1
}
