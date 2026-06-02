package com.albunyaan.tube.data.subscriptions

import androidx.room.withTransaction
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.currentUid
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.local.PlaylistVideoLinkDao
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SavedPlaylistDao
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import com.albunyaan.tube.data.sync.SyncManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
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
    private val playlistLinks: PlaylistVideoLinkDao,
) {
    // Cubic R5 P0 #5 — flow factories must rescope on accountState changes.
    //
    // Pre-fix: `channels.observeAll(uid = accountRepository.currentUid())`
    // evaluated `currentUid()` ONCE when the Flow was constructed. Sign-in
    // (or sign-out) during observation did not re-scope the Flow — after
    // cold-start sign-in the UI kept showing anon rows (uid=""); after
    // sign-out it kept the previous user's rows.
    //
    // Fix: re-derive the uid from the live `accountState` StateFlow and
    // restart the underlying DAO flow on every change. `flatMapLatest`
    // cancels the previous downstream collection before subscribing to the
    // new one, so we never multiplex rows across uid boundaries.

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSubscribedChannels(): Flow<List<SubscribedChannel>> =
        accountRepository.accountState.flatMapLatest { state ->
            channels.observeAll(uid = uidOf(state))
        }

    /** B3: APPROVED-only variant used by feed composition. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeApprovedSubscribedChannels(): Flow<List<SubscribedChannel>> =
        accountRepository.accountState.flatMapLatest { state ->
            channels.observeApprovedChannels(uid = uidOf(state))
        }

    /** B3: AWAITING variant used by the awaiting-imports surface. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAwaitingSubscribedChannels(): Flow<List<SubscribedChannel>> =
        accountRepository.accountState.flatMapLatest { state ->
            channels.observeAwaitingChannels(uid = uidOf(state))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSavedPlaylists(): Flow<List<SavedPlaylist>> =
        accountRepository.accountState.flatMapLatest { state ->
            playlists.observeAll(uid = uidOf(state))
        }

    /** B3: APPROVED-only variant used by feed composition. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeApprovedSavedPlaylists(): Flow<List<SavedPlaylist>> =
        accountRepository.accountState.flatMapLatest { state ->
            playlists.observeApprovedPlaylists(uid = uidOf(state))
        }

    /** B3: AWAITING variant used by the awaiting-imports surface. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAwaitingSavedPlaylists(): Flow<List<SavedPlaylist>> =
        accountRepository.accountState.flatMapLatest { state ->
            playlists.observeAwaitingPlaylists(uid = uidOf(state))
        }

    // One-shot read — uid is captured at call time, no Flow rescoping needed.
    suspend fun getSubscribedChannels(): List<SubscribedChannel> =
        channels.getAll(uid = accountRepository.currentUid())

    /** B3: APPROVED-only one-shot read used by feed composition. */
    suspend fun getApprovedSubscribedChannels(): List<SubscribedChannel> =
        channels.getApprovedSubscribedChannels(uid = accountRepository.currentUid())

    // One-shot read of saved playlists for the current account. Used by
    // [MeFeedRepository.refreshPlaylistVideos] which iterates playlists
    // and fetches each one's content via NewPipe — a snapshot is enough
    // since the worker re-runs on schedule and picks up new playlists on
    // the next tick.
    suspend fun getSavedPlaylists(): List<SavedPlaylist> =
        playlists.getAll(uid = accountRepository.currentUid())

    /** B3: APPROVED-only one-shot read used by feed composition. */
    suspend fun getApprovedSavedPlaylists(): List<SavedPlaylist> =
        playlists.getApprovedSavedPlaylists(uid = accountRepository.currentUid())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun isChannelSubscribed(id: String): Flow<Boolean> =
        accountRepository.accountState.flatMapLatest { state ->
            channels.observeIsSubscribed(uid = uidOf(state), id = id)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun isPlaylistSaved(id: String): Flow<Boolean> =
        accountRepository.accountState.flatMapLatest { state ->
            playlists.observeIsSaved(uid = uidOf(state), id = id)
        }

    private fun uidOf(state: AccountState): String =
        (state as? AccountState.Loaded)?.uid ?: ""

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
        db.withTransaction {
            playlists.softDelete(uid = uid, id = playlistId)
            // Drop the playlist's video links so the Me-feed union query
            // immediately stops returning its videos. Without this, the
            // links linger as orphans pointing at a soft-deleted playlist
            // and the read paths' deleted=0 filter on saved_playlists is
            // the only thing keeping them invisible.
            playlistLinks.deleteForPlaylist(playlistId)
        }
        syncManager.pushDirtyAsync(uid)
    }
}
