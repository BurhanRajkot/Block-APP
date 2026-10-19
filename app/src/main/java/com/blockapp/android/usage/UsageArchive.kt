package com.blockapp.android.usage

import android.content.Context
import com.blockapp.android.data.BlockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies recent screen time out of the system's usage events into Room (BlockRepository's usage
 * archive), so History can show every day since install and not just the system's last week.
 *
 * Runs from three places, because no single one is guaranteed: process start (BlockApplication),
 * a daily inexact alarm (UsageArchiveReceiver, re-armed on boot), and every time History or Home
 * reads usage anyway. [DAYS] covers the system's whole event window, so a phone left untouched
 * for a few days still loses nothing as long as any one of them runs within that week.
 *
 * Without Usage access it does nothing, and the archive keeps what it already had.
 */
object UsageArchive {

    suspend fun archiveRecent(context: Context, repository: BlockRepository) {
        if (!UsageStatsProvider.hasUsageAccess(context)) return
        val days = withContext(Dispatchers.IO) {
            // Access can be revoked between the check and the query.
            runCatching { UsageStatsProvider.history(context, DAYS) }.getOrNull()
        } ?: return
        repository.archiveUsage(days)
    }

    /**
     * How many days each run replays. The system keeps raw events for roughly a week to ten
     * days; asking for more only replays empty days, which the archive ignores.
     */
    private const val DAYS = 10
}
