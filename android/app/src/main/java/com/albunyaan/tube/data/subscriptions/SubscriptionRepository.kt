package com.albunyaan.tube.data.subscriptions

import androidx.room.withTransaction
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.currentUid
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SavedPlaylistDao
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import com.albunyaan.tube.data.sync.SyncManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val channels: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
    private val cache: ChannelVideoCacheDao,
    private val refreshState: ChannelFeedRefreshStateDao,
    private val accountRepository: AccountRepository,
    private val syncManager: SyncManager,
) {
    fun observeSubscribedChannels(): Flow<List<SubscribedChannel>> =
        channels.observeAll(uid = accountRepository.currentUid())

    fun observeSavedPlaylists(): Flow<List<SavedPlaylist>> =
        playlists.observeAll(uid = accountRepository.currentUid())

    suspend fun getSubscribedChannels(): List<SubscribedChannel> =
        channels.getAll(uid = accountRepository.currentUid())

    fun isChannelSubscribed(id: String): Flow<Boolean> =
        channels.observeIsSubscribed(uid = accountRepository.currentUid(), id = id)

    fun isPlaylistSaved(id: String): Flow<Boolean> =
        playlists.observeIsSaved(uid = accountRepository.currentUid(), id = id)

    /**
     * Direct DAO upsert. **Bypasses the 30-channel cap** enforced by
     * [SubscriptionLimitGuard.trySubscribe]. New callers MUST go through the
     * guard; this method is kept public only so existing test fixtures continue
     * to compile. If you find yourself wanting to call this from production
     * code, you almost certainly want the guard instead.
     */
    suspend fun subscribe(channel: SubscribedChannel) {
        val uid = accountRepository.currentUid()
        channels.upsert(channel.copy(user_id = uid, dirty = true, deleted = false))
        syncManager.pushDirtyAsync(uid)
    }

    /**
     * Remove a subscription atomically — the channel row, its cached videos,
     * and its refresh-state marker all go in the same transaction. Without
     * this, unsubscribed channels' rows lingered in `channel_video_cache`
     * (leaking into the feed until the 14-day window expired) and in
     * `channel_feed_refresh_state` (blocking re-subscribe from refreshing
     * for up to 30 min under the TTL gate). [F2 from review.]
     */
    suspend fun unsubscribe(channelId: String) {
        val uid = accountRepository.currentUid()
        db.withTransaction {
            channels.softDelete(uid = uid, id = channelId)
            cache.deleteForChannel(channelId)
            refreshState.delete(channelId)
        }
        syncManager.pushDirtyAsync(uid)
    }

    suspend fun savePlaylist(playlist: SavedPlaylist) {
        val uid = accountRepository.currentUid()
        playlists.upsert(playlist.copy(user_id = uid, dirty = true, deleted = false))
        syncManager.pushDirtyAsync(uid)
    }

    suspend fun unsavePlaylist(playlistId: String) {
        val uid = accountRepository.currentUid()
        playlists.softDelete(uid = uid, id = playlistId)
        syncManager.pushDirtyAsync(uid)
    }
}
