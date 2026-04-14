package com.albunyaan.tube.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for local app data.
 *
 * Currently stores:
 * - Favorite videos (local like replacement)
 * - Followed channels (local subscribe replacement)
 *
 * Future additions could include:
 * - Watch history
 * - Downloaded video metadata
 * - Offline playlists
 */
@Database(
    entities = [FavoriteVideo::class, FollowedChannel::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteVideoDao(): FavoriteVideoDao
    abstract fun followedChannelDao(): FollowedChannelDao

    companion object {
        const val DATABASE_NAME = "albunyaan_tube_db"

        /**
         * Migration from schema v1 to v2: adds the `followed_channels` table.
         *
         * Must be registered in [com.albunyaan.tube.di.DatabaseModule] so
         * release builds can upgrade an existing v1 install (e.g. previous
         * beta) without crashing on `IllegalStateException: Migration … not
         * found`. Debug builds additionally fall back to destructive migration
         * as a developer convenience.
         *
         * The SQL mirrors the exported schema at
         * `app/schemas/com.albunyaan.tube.data.local.AppDatabase/2.json` for
         * the `followed_channels` entity so Room's post-migration validation
         * succeeds.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `followed_channels` " +
                        "(`channelId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`avatarUrl` TEXT, `followedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`channelId`))"
                )
            }
        }
    }
}
