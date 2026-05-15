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
 * v7: followed_channels table added for the Shorts player feed (ANDROID-SHORTS-01).
 *     Note: legacy pre-v2 databases had a `followed_channels` table with a
 *     different schema — [MIGRATION_1_2] drops it before creating the v2
 *     subscribed_channels table, so by the time MIGRATION_6_7 runs the
 *     table is gone and can be safely re-created with the new schema.
 * v8: Plan D account sync — adds user_id/updated_at/deleted/dirty columns to
 *     subscribed_channels, saved_playlists, favorite_videos; creates
 *     sync_state and account_binding tables. See [MIGRATION_7_8].
 */
@Database(
    entities = [
        FavoriteVideo::class,
        SubscribedChannel::class,
        SavedPlaylist::class,
        ChannelVideoCache::class,
        ChannelFeedRefreshState::class,
        FollowedChannel::class,
        SyncStateEntity::class,
        AccountBindingEntity::class,
    ],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteVideoDao(): FavoriteVideoDao
    abstract fun subscribedChannelDao(): SubscribedChannelDao
    abstract fun savedPlaylistDao(): SavedPlaylistDao
    abstract fun channelVideoCacheDao(): ChannelVideoCacheDao
    abstract fun channelFeedRefreshStateDao(): ChannelFeedRefreshStateDao
    abstract fun followedChannelDao(): FollowedChannelDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun accountBindingDao(): AccountBindingDao

    companion object {
        const val DATABASE_NAME = "albunyaan_tube_db"
    }
}
