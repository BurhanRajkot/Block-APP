package com.blockapp.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "used_nonces")
data class UsedNonceEntity(
    @PrimaryKey val nonce: String,
    val usedAt: Long,
)
