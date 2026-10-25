package com.blockapp.android.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.blockapp.android.alarm.AlarmScheduler
import com.blockapp.android.usage.DayUsage
import com.blockapp.android.util.PlanProgress
import com.blockapp.android.util.ProtectedPackages
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Single read/write entry point for lock state, used by the UI, the accessibility service,
 * and the alarm/boot receivers.
 */
class BlockRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
) {
    private val dao: BlockDao = database.blockDao()

    private val _activeLocks = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * packageName -> blockUntil epoch millis, kept in memory so the accessibility service can
     * check it synchronously on every window-state-changed event instead of hitting Room.
     */
    val activeLocks: StateFlow<Map<String, Long>> = _activeLocks

    val activeEntities: Flow<List<BlockedAppEntity>> get() = dao.observeActive()

    init {
        dao.observeActive()
            .onEach { list -> _activeLocks.value = list.associate { it.packageName to it.blockUntil } }
            .launchIn(scope)
    }

    /**
     * Locking Settings, the package installer, or Play Store would kick those packages home
     * the instant they open — including for this app's own setup/removal flows, which live
     * inside Settings. That's a self-inflicted lockout, so it's refused here as a backstop
     * even though the app picker already excludes them from the list (see ProtectedPackages).
     *
     * **Locks only ever extend, never shorten.** [BlockDao.insert] is REPLACE, so without the
     * merge below, re-locking an already-locked app for a shorter time would overwrite its
     * blockUntil and end the running lock early — a fully in-app early exit, which is the one
     * thing this app exists to prevent. An unlock key is the only thing allowed to bring a
     * blockUntil forward (see [applyUnlockKey]), because that key can only be minted off-device.
     *
     * The original [BlockedAppEntity.blockedAt] is kept for the same reason it's stored at all:
     * it's the denominator of the progress ring on HomeScreen and the block screen, so resetting
     * it on an extend would snap a nearly-finished lock back to 0%.
     */
    fun lockApp(packageName: String, label: String, blockUntil: Long) {
        if (packageName in ProtectedPackages.ALL) return
        scope.launch {
            val existing = dao.getActiveLock(packageName)
            val effectiveUntil = maxOf(blockUntil, existing?.blockUntil ?: 0L)
            dao.insert(
                BlockedAppEntity(
                    packageName = packageName,
                    appLabel    = label,
                    blockedAt   = existing?.blockedAt ?: System.currentTimeMillis(),
                    blockUntil  = effectiveUntil,
                ),
            )
            AlarmScheduler.schedule(context, packageName, effectiveUntil)
        }
    }

    /**
     * Direct DB read (bypassing the in-memory [activeLocks] cache), used by the accessibility
     * service right after it (re)connects — e.g. after the process was killed and restarted —
     * when the in-memory cache may not have caught up with Room's async Flow yet.
     */
    suspend fun getActiveLockUntil(packageName: String): Long? = getActiveLock(packageName)?.blockUntil

    /**
     * The full record for one active lock, for UI that needs more than the expiry time —
     * BlockOverlayActivity uses [BlockedAppEntity.blockedAt] to show how far through the lock the
     * user already is. Deliberately not folded into [activeLocks]: that map is read on every
     * window-state event by the accessibility service, so it stays a bare packageName -> Long to
     * keep that path a single map lookup, and a display-only field must not widen it.
     */
    suspend fun getActiveLock(packageName: String): BlockedAppEntity? = dao.getActiveLock(packageName)

    suspend fun expireDuePackage(packageName: String) {
        val lock = dao.getActiveLock(packageName) ?: return
        if (lock.blockUntil <= System.currentTimeMillis()) {
            dao.deactivate(packageName)
        }
    }

    suspend fun expireAllDue() {
        val now = System.currentTimeMillis()
        dao.getActiveOnce().filter { it.blockUntil <= now }.forEach { dao.deactivate(it.packageName) }
    }

    suspend fun getActiveOnce(): List<BlockedAppEntity> = dao.getActiveOnce()

    /** Per-app reel counts for [day], most-watched first. */
    fun observeReelCounts(day: LocalDate): Flow<List<ReelCountEntity>> =
        dao.observeReelCounts(day.toString())

    /** Today's reels across every app, for the floating ReelBubble. */
    fun observeReelTotal(day: LocalDate): Flow<Int> =
        observeReelCounts(day).map { counts -> counts.sumOf { it.reels } }

    /** Reel totals per day from [fromDay] on, oldest first. Days with no reels have no row. */
    fun observeReelHistory(fromDay: LocalDate): Flow<List<ReelDayTotal>> =
        dao.observeReelHistory(fromDay.toString())

    /**
     * Adds [count] reels and [watchMs] of reel-viewer time to today's tally for [packageName].
     * Either may be zero: a session ending adds its last stretch of time with no new reel.
     * Called from the accessibility service's main thread, so the write is launched rather than
     * awaited.
     *
     * A failed write is dropped rather than rethrown. [scope] has no exception handler, so an
     * uncaught SQLiteException here (disk full, DB locked) would take the whole process down —
     * and with it the accessibility service and every running lock — over a statistic.
     * Losing a few counted reels is the right trade (CLAUDE.md invariant 9).
     */
    fun addReelsWatched(packageName: String, count: Int, watchMs: Long) {
        if (count <= 0 && watchMs <= 0L) return
        val day = LocalDate.now().toString()
        scope.launch {
            try {
                dao.insertReelCountIfAbsent(ReelCountEntity(day, packageName, reels = 0))
                dao.incrementReelCount(day, packageName, count, watchMs)
            } catch (e: SQLiteException) {
                // Dropped on purpose; see KDoc.
            }
        }
    }

    /** Returns false only when the key's nonce was already used (replay). */
    suspend fun applyUnlockKey(targetPackage: String, newUntil: Long, nonce: String): Boolean {
        if (dao.isNonceUsed(nonce) > 0) return false
        dao.markNonceUsed(UsedNonceEntity(nonce, System.currentTimeMillis()))

        val now = System.currentTimeMillis()
        if (targetPackage == "*") {
            if (newUntil <= now) {
                dao.deactivateAll()
            } else {
                dao.getActiveOnce().forEach { entity ->
                    dao.updateBlockUntil(entity.packageName, newUntil)
                    AlarmScheduler.schedule(context, entity.packageName, newUntil)
                }
            }
        } else {
            if (newUntil <= now) {
                dao.deactivate(targetPackage)
            } else {
                dao.updateBlockUntil(targetPackage, newUntil)
                AlarmScheduler.schedule(context, targetPackage, newUntil)
            }
        }
        return true
    }

    // ── reel plan days (schema v5) ──────────────────────────────────────────────────────────

    /**
     * Notes today's limit, and that the service is running today when [tracked]. Called by the
     * service when it connects and at every midnight, and by ReelPlan.refresh whenever it may
     * have changed the limit. Fire-and-forget and dropped on failure, like [addReelsWatched].
     */
    fun recordPlanDay(limit: Int?, tracked: Boolean) {
        val day = LocalDate.now().toString()
        scope.launch {
            try {
                dao.insertPlanDayIfAbsent(PlanDayEntity(day))
                dao.setPlanDayLimit(day, limit ?: PlanDayEntity.NO_LIMIT)
                if (tracked) dao.markPlanDayTracked(day)
            } catch (e: SQLiteException) {
                // Dropped on purpose; see addReelsWatched.
            }
        }
    }

    /** One Tier 3 wall: the daily limit when [quiet] is false, reel-free hours when true. */
    fun recordReelWall(quiet: Boolean, limit: Int?) {
        val day = LocalDate.now().toString()
        scope.launch {
            try {
                dao.insertPlanDayIfAbsent(PlanDayEntity(day, tracked = 1))
                dao.setPlanDayLimit(day, limit ?: PlanDayEntity.NO_LIMIT)
                dao.markPlanDayTracked(day)
                dao.addPlanDayWalls(day, limitWalls = if (quiet) 0 else 1, quietWalls = if (quiet) 1 else 0)
            } catch (e: SQLiteException) {
                // Dropped on purpose; see addReelsWatched.
            }
        }
    }

    fun observePlanDays(from: LocalDate, to: LocalDate): Flow<List<PlanDayEntity>> =
        dao.observePlanDays(from.toString(), to.toString())

    /** The plan's scorecard from its first day to [today], re-emitted as either side changes. */
    fun observePlanProgress(plan: ReelPlan.Plan, today: LocalDate): Flow<PlanProgress.Summary> =
        combine(observeReelDays(plan.startDay, today), observePlanDays(plan.startDay, today)) { reels, records ->
            PlanProgress.summarize(plan, today, PlanProgress.days(plan, today, reels, records))
        }

    // ── history ─────────────────────────────────────────────────────────────────────────────

    /** Per-day reel totals in [from]..[to] inclusive. Days with no reels have no row. */
    fun observeReelDays(from: LocalDate, to: LocalDate): Flow<List<ReelDayTotal>> =
        dao.observeReelDays(from.toString(), to.toString())

    /** Per-app reel totals over [from]..[to], most first. */
    fun observeReelApps(from: LocalDate, to: LocalDate): Flow<List<ReelAppTotal>> =
        dao.observeReelApps(from.toString(), to.toString())

    fun observeUsageDays(from: LocalDate, to: LocalDate): Flow<List<UsageDayEntity>> =
        dao.observeUsageDays(from.toString(), to.toString())

    fun observeUsageApps(day: LocalDate): Flow<List<UsageAppEntity>> = dao.observeUsageApps(day.toString())

    fun observeUsageAppTotals(from: LocalDate, to: LocalDate): Flow<List<UsageAppTotal>> =
        dao.observeUsageAppTotals(from.toString(), to.toString())

    /** The first day with any recorded screen time or reels, or null on a fresh install. */
    suspend fun earliestRecordedDay(): LocalDate? =
        dao.earliestRecordedDay()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /**
     * Reel totals for the [days] complete days before today, oldest first, zero-filled. What
     * ReelPlan.refresh adapts to. Days before the first recorded one are left out rather than
     * counted as zero, or a fresh install would read as three perfect days and drop the limit
     * to its floor on day one.
     */
    suspend fun recentDailyReels(days: Int): List<Int> {
        val today = LocalDate.now()
        val from = today.minusDays(days.toLong())
        val earliest = earliestRecordedDay() ?: return emptyList()
        val byDay = dao.reelDaysOnce(from.toString(), today.minusDays(1).toString()).associateBy { it.day }
        return (0 until days)
            .map { from.plusDays(it.toLong()) }
            .filter { !it.isBefore(earliest) }
            .map { byDay[it.toString()]?.reels ?: 0 }
    }

    /**
     * Copies [days] of screen time into the archive. Two rules keep a replay from making the
     * archive worse:
     * - an empty day is never written, because a day older than the system's event window
     *   replays as empty, and writing that would erase a day the archive still had;
     * - a day is only replaced by one at least as large. The oldest day of a replay can come out
     *   short (its lookback misses a session that started earlier), and the fuller copy wins.
     *
     * A failed write is dropped, like [addReelsWatched]: this runs from a receiver and from
     * screens, and a statistic must not take the process down (CLAUDE.md invariant 9).
     */
    suspend fun archiveUsage(days: List<DayUsage>) {
        try {
            for (usage in days) {
                if (usage.totalMillis <= 0L && usage.unlocks == 0) continue
                val key = usage.day.toString()
                val existing = dao.usageDay(key)
                if (existing != null && existing.totalMs > usage.totalMillis) continue
                database.withTransaction {
                    dao.upsertUsageDay(
                        UsageDayEntity(
                            day = key,
                            totalMs = usage.totalMillis,
                            opens = usage.totalOpens,
                            unlocks = usage.unlocks,
                            hourly = usage.hourly.joinToString(","),
                        ),
                    )
                    dao.clearUsageApps(key)
                    dao.insertUsageApps(
                        usage.apps.map {
                            UsageAppEntity(key, it.packageName, it.label, it.foregroundMillis, it.opens)
                        },
                    )
                }
            }
        } catch (e: SQLiteException) {
            // Dropped on purpose; see KDoc.
        }
    }
}
