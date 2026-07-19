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
}
