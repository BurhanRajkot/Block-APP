package com.blockapp.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockDao {

    @Query("SELECT * FROM blocked_apps WHERE active = 1 ORDER BY blockUntil ASC")
    fun observeActive(): Flow<List<BlockedAppEntity>>

    @Query("SELECT * FROM blocked_apps WHERE active = 1")
    suspend fun getActiveOnce(): List<BlockedAppEntity>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName AND active = 1 LIMIT 1")
    suspend fun getActiveLock(packageName: String): BlockedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlockedAppEntity)

    @Query("UPDATE blocked_apps SET active = 0 WHERE packageName = :packageName")
    suspend fun deactivate(packageName: String)

    @Query("UPDATE blocked_apps SET active = 0 WHERE active = 1")
    suspend fun deactivateAll()

    @Query("UPDATE blocked_apps SET blockUntil = :newUntil WHERE packageName = :packageName")
    suspend fun updateBlockUntil(packageName: String, newUntil: Long)

    @Query("SELECT COUNT(*) FROM used_nonces WHERE nonce = :nonce")
    suspend fun isNonceUsed(nonce: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markNonceUsed(entity: UsedNonceEntity)

    @Query("SELECT * FROM reel_counts WHERE day = :day ORDER BY reels DESC")
    fun observeReelCounts(day: String): Flow<List<ReelCountEntity>>

    @Query(
        "SELECT day, SUM(reels) AS reels, SUM(watchMs) AS watchMs FROM reel_counts " +
            "WHERE day >= :fromDay GROUP BY day ORDER BY day ASC",
    )
    fun observeReelHistory(fromDay: String): Flow<List<ReelDayTotal>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReelCountIfAbsent(entity: ReelCountEntity)

    @Query(
        "UPDATE reel_counts SET reels = reels + :count, watchMs = watchMs + :watchMs " +
            "WHERE day = :day AND packageName = :packageName",
    )
    suspend fun incrementReelCount(day: String, packageName: String, count: Int, watchMs: Long)

    @Query(
        "SELECT day, SUM(reels) AS reels, SUM(watchMs) AS watchMs FROM reel_counts " +
            "WHERE day BETWEEN :fromDay AND :toDay GROUP BY day ORDER BY day ASC",
    )
    fun observeReelDays(fromDay: String, toDay: String): Flow<List<ReelDayTotal>>

    @Query(
        "SELECT day, SUM(reels) AS reels, SUM(watchMs) AS watchMs FROM reel_counts " +
            "WHERE day BETWEEN :fromDay AND :toDay GROUP BY day ORDER BY day ASC",
    )
    suspend fun reelDaysOnce(fromDay: String, toDay: String): List<ReelDayTotal>

    @Query(
        "SELECT packageName, SUM(reels) AS reels, SUM(watchMs) AS watchMs FROM reel_counts " +
            "WHERE day BETWEEN :fromDay AND :toDay GROUP BY packageName ORDER BY reels DESC",
    )
    fun observeReelApps(fromDay: String, toDay: String): Flow<List<ReelAppTotal>>

    // ── usage archive (schema v4) ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM usage_days WHERE day = :day")
    suspend fun usageDay(day: String): UsageDayEntity?

    @Query("SELECT * FROM usage_days WHERE day BETWEEN :fromDay AND :toDay ORDER BY day ASC")
    fun observeUsageDays(fromDay: String, toDay: String): Flow<List<UsageDayEntity>>

    @Query("SELECT * FROM usage_apps WHERE day = :day ORDER BY foregroundMs DESC")
    fun observeUsageApps(day: String): Flow<List<UsageAppEntity>>

    @Query(
        "SELECT packageName, MAX(label) AS label, SUM(foregroundMs) AS foregroundMs, " +
            "SUM(opens) AS opens FROM usage_apps WHERE day BETWEEN :fromDay AND :toDay " +
            "GROUP BY packageName ORDER BY foregroundMs DESC",
    )
    fun observeUsageAppTotals(fromDay: String, toDay: String): Flow<List<UsageAppTotal>>

    /** The first day anything was recorded, which is how far back History lets the user go. */
    @Query("SELECT MIN(day) FROM (SELECT day FROM usage_days UNION ALL SELECT day FROM reel_counts)")
    suspend fun earliestRecordedDay(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsageDay(entity: UsageDayEntity)

    @Query("DELETE FROM usage_apps WHERE day = :day")
    suspend fun clearUsageApps(day: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageApps(entities: List<UsageAppEntity>)

    // ── reel plan days (schema v5) ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlanDayIfAbsent(entity: PlanDayEntity)

    @Query("UPDATE plan_days SET limitReels = :limit WHERE day = :day")
    suspend fun setPlanDayLimit(day: String, limit: Int)

    @Query("UPDATE plan_days SET tracked = 1 WHERE day = :day")
    suspend fun markPlanDayTracked(day: String)

    @Query(
        "UPDATE plan_days SET limitWalls = limitWalls + :limitWalls, " +
            "quietWalls = quietWalls + :quietWalls WHERE day = :day",
    )
    suspend fun addPlanDayWalls(day: String, limitWalls: Int, quietWalls: Int)

    @Query("SELECT * FROM plan_days WHERE day BETWEEN :fromDay AND :toDay ORDER BY day ASC")
    fun observePlanDays(fromDay: String, toDay: String): Flow<List<PlanDayEntity>>
}
