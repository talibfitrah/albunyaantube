package com.albunyaan.tube.data.subscriptions

import androidx.room.withTransaction
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SavedPlaylistDao
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SubscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val channels: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
    private val cache: ChannelVideoCacheDao,
    private val refreshState: ChannelFeedRefreshStateDao,
) {
    fun observeSubscribedChannels(): Flow<List<SubscribedChannel>> = channels.observeAll()

    fun observeSavedPlaylists(): Flow<List<SavedPlaylist>> = playlists.observeAll()

    suspend fun getSubscribedChannels(): List<SubscribedChannel> = channels.getAll()

    fun isChannelSubscribed(id: String): Flow<Boolean> = channels.observeIsSubscribed(id)

    fun isPlaylistSaved(id: String): Flow<Boolean> = playlists.observeIsSaved(id)

    suspend fun subscribe(channel: SubscribedChannel) = channels.upsert(channel)

    /**
     * Remove a subscription atomically — the channel row, its cached videos,
     * and its refresh-state marker all go in the same transaction. Without
     * this, unsubscribed channels' rows lingered in `channel_video_cache`
     * (leaking into the feed until the 14-day window expired) and in
     * `channel_feed_refresh_state` (blocking re-subscribe from refreshing
     * for up to 30 min under the TTL gate). [F2 from review.]
     */
    suspend fun unsubscribe(channelId: String) {
        db.withTransaction {
            channels.delete(channelId)
            cache.deleteForChannel(channelId)
            refreshState.delete(channelId)
        }
    }

    suspend fun savePlaylist(playlist: SavedPlaylist) = playlists.upsert(playlist)

    suspend fun unsavePlaylist(playlistId: String) = playlists.delete(playlistId)
}
