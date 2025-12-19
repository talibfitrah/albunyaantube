package com.albunyaan.tube.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for local app data.
 *
 * Currently stores:
 * - Favorite videos (local like replacement)
 *
 * Future additions could include:
 * - Watch history
 * - Downloaded video metadata
 * - Offline playlists
 */
@Database(
    entities = [FavoriteVideo::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteVideoDao(): FavoriteVideoDao

    companion object {
        const val DATABASE_NAME = "albunyaan_tube_db"
    }
}
