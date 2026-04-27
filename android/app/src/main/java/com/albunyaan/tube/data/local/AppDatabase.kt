package com.albunyaan.tube.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for local app data.
 *
 * v1: favorite videos
 * v2: subscribed channels, saved playlists, channel video cache, feed refresh state
 * v3: ATOM refresh per-channel ETag + backoff columns (additive)
 */
@Database(
    entities = [
        FavoriteVideo::class,
        SubscribedChannel::class,
        SavedPlaylist::class,
        ChannelVideoCache::class,
        ChannelFeedRefreshState::class,
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteVideoDao(): FavoriteVideoDao
    abstract fun subscribedChannelDao(): SubscribedChannelDao
    abstract fun savedPlaylistDao(): SavedPlaylistDao
    abstract fun channelVideoCacheDao(): ChannelVideoCacheDao
    abstract fun channelFeedRefreshStateDao(): ChannelFeedRefreshStateDao

    companion object {
        const val DATABASE_NAME = "albunyaan_tube_db"
    }
}
