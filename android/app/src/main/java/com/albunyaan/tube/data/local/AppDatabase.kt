package com.albunyaan.tube.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

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
    }
}
