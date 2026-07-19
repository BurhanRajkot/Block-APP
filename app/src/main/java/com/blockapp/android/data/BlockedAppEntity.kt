package com.blockapp.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedAppEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val blockedAt: Long,
    val blockUntil: Long,
    val active: Boolean = true,
)
