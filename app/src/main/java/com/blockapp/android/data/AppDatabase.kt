package com.blockapp.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BlockedAppEntity::class, UsedNonceEntity::class, ReelCountEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockDao(): BlockDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "blockapp.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        /**
         * v2 adds reel_counts. The SQL must match what Room generates for [ReelCountEntity]
         * column for column (types, NOT NULL, primary-key order) — Room validates the table
         * after migrating and throws at open on any mismatch, and a DB that won't open means
         * activeLocks never populates and lock enforcement silently stops (CLAUDE.md
         * invariant 7). Copied from createAllTables in the generated AppDatabase_Impl.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reel_counts` (`day` TEXT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, `reels` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`day`, `packageName`))",
                )
            }
        }
    }
}
