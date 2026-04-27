package com.albunyaan.tube.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for local app data.
 *
 * v1: favorite videos
 * v2: subscribed channels, saved playlists, channel video cache, feed refresh state
 * v3: ATOM refresh per-channel ETag + backoff columns (additive)
 * v4: NewPipe deep-paging columns (deepPageUrl, deepPageCookiesJson) (additive)
 * v5: data wipe of stale deep-page tokens captured before SerializedPage
 *     persisted NewPipe Page.body — see [MIGRATION_4_5]
 * v6: data wipe of DEEP_PAGE_EOF_SENTINEL rows falsely marked exhausted by
 *     the now-fixed ATOM refresh path that wiped deep-page state — see
 *     [MIGRATION_5_6]. No schema changes in v5 or v6.
 */
@Database(
    entities = [
        FavoriteVideo::class,
        SubscribedChannel::class,
        SavedPlaylist::class,
        ChannelVideoCache::class,
        ChannelFeedRefreshState::class,
    ],
    version = 6,
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
