package com.albunyaan.tube.data.sync

import androidx.room.withTransaction
import com.albunyaan.tube.data.local.*
import com.albunyaan.tube.data.sync.dto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan D — owns all sync side-effects:
 *  • bind(uid)       — sign-in / account-switch decision matrix
 *  • runMerge(uid)   — additive merge of anon-era rows with server
 *  • pullAll(uid)    — cursor-based delta pull, per-type
 *  • pushDirty(uid)  — drain dirty=1 rows with exponential backoff
 *  • unbind()        — sign-out: in-memory clear; tables retain user_id
 *
 * Triggers (wired by [SyncManagerLifecycleObserver] etc.):
 *  • SplashRouter sign-in success → bind
 *  • ProcessLifecycleOwner ON_RESUME → pullAll + pushDirty
 *  • Repo write → markDirty + pushDirty (fire-and-forget)
 *  • Connectivity restored → pushDirty
 */
@Singleton
class SyncManager @Inject constructor(
    private val api: SyncApi,
    private val db: AppDatabase,
    private val subs: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
    private val favorites: FavoriteVideoDao,
    private val syncState: SyncStateDao,
    private val binding: AccountBindingDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    private val pushMutex = Mutex()

    suspend fun bind(uid: String) {
        val b = binding.get()
        when {
            b == null -> {
                binding.upsert(AccountBindingEntity(user_id = uid, bound_at = System.currentTimeMillis(), initial_merge_done = false))
                runMerge(uid)
            }
            b.user_id == uid && b.initial_merge_done -> {
                pullAll(uid)
                pushDirty(uid)
            }
            b.user_id == uid && !b.initial_merge_done -> {
                // Prior merge crashed mid-way — re-enter
                runMerge(uid)
            }
            else -> {
                // Account switch: wipe old uid's rows
                subs.wipeForUid(b.user_id)
                playlists.wipeForUid(b.user_id)
                favorites.wipeForUid(b.user_id)
                syncState.clearForUid(b.user_id)
                binding.clear()
                binding.upsert(AccountBindingEntity(uid, System.currentTimeMillis(), false))
                runMerge(uid)
            }
        }
    }

    suspend fun runMerge(uid: String) {
        // Step 1: tag anon rows
        subs.tagAnonRowsToUid(uid)
        playlists.tagAnonRowsToUid(uid)
        favorites.tagAnonRowsToUid(uid)
        // Step 2: pull server — collisions overwrite local, clearing dirty
        pullAll(uid)
        // Step 3: push remaining local-only rows
        pushDirty(uid)
        // Step 4: mark merge done
        binding.markMergeDone(uid)
    }

    suspend fun pullAll(uid: String) = pullMutex.withLock {
        val cursors = mutableMapOf(
            "subscriptions" to (syncState.cursorFor(uid, "subscriptions") ?: 0L),
            "playlists"     to (syncState.cursorFor(uid, "playlists")     ?: 0L),
            "favorites"     to (syncState.cursorFor(uid, "favorites")     ?: 0L),
        )

        var more: Boolean
        do {
            val resp = api.pull(cursors["subscriptions"]!!, cursors["playlists"]!!, cursors["favorites"]!!)
            if (!resp.isSuccessful) return@withLock
            val body = resp.body() ?: return@withLock

            db.withTransaction {
                for (row in body.subscriptions.items) {
                    if (row.deleted) subs.applyTombstone(uid, row.entityId, row.updatedAt)
                    else             subs.upsertFromServer(rowToSub(uid, row))
                }
                for (row in body.playlists.items) {
                    if (row.deleted) playlists.applyTombstone(uid, row.entityId, row.updatedAt)
                    else             playlists.upsertFromServer(rowToPlaylist(uid, row))
                }
                for (row in body.favorites.items) {
                    if (row.deleted) favorites.applyTombstone(uid, row.entityId, row.updatedAt)
                    else             favorites.upsertFromServer(rowToFavorite(uid, row))
                }
                body.subscriptions.items.maxByOrNull { it.updatedAt }?.let {
                    syncState.upsert(SyncStateEntity("subscriptions", uid, it.updatedAt, System.currentTimeMillis()))
                    cursors["subscriptions"] = it.updatedAt
                }
                body.playlists.items.maxByOrNull { it.updatedAt }?.let {
                    syncState.upsert(SyncStateEntity("playlists", uid, it.updatedAt, System.currentTimeMillis()))
                    cursors["playlists"] = it.updatedAt
                }
                body.favorites.items.maxByOrNull { it.updatedAt }?.let {
                    syncState.upsert(SyncStateEntity("favorites", uid, it.updatedAt, System.currentTimeMillis()))
                    cursors["favorites"] = it.updatedAt
                }
            }
            more = (body.subscriptions.nextCursor != null) ||
                   (body.playlists.nextCursor     != null) ||
                   (body.favorites.nextCursor     != null)
        } while (more)
    }

    private fun rowToSub(uid: String, r: SubscriptionSyncDto) =
        SubscribedChannel(
            channelId    = r.entityId,
            channelUrl   = r.channelUrl,
            name         = r.name,
            avatarUrl    = r.avatarUrl,
            subscribedAt = r.subscribedAt,
            user_id      = uid,
            updated_at   = r.updatedAt,
            deleted      = false,
            dirty        = false,
        )

    private fun rowToPlaylist(uid: String, r: PlaylistSyncDto) =
        SavedPlaylist(
            playlistId   = r.entityId,
            playlistUrl  = r.playlistUrl,
            name         = r.name,
            thumbnailUrl = r.thumbnailUrl,
            uploaderName = r.uploaderName,
            savedAt      = r.savedAt,
            user_id      = uid,
            updated_at   = r.updatedAt,
            deleted      = false,
            dirty        = false,
        )

    private fun rowToFavorite(uid: String, r: FavoriteSyncDto) =
        FavoriteVideo(
            videoId         = r.entityId,
            title           = r.title,
            channelName     = r.channelName,
            thumbnailUrl    = r.thumbnailUrl,
            durationSeconds = r.durationSeconds,
            addedAt         = r.addedAt,
            user_id         = uid,
            updated_at      = r.updatedAt,
            deleted         = false,
            dirty           = false,
        )

    suspend fun pushDirty(uid: String) { /* Task 24 */ }
    fun unbind() { /* in-memory clear; nothing persistent to flush */ }

    fun pushDirtyAsync(uid: String) { scope.launch { pushDirty(uid) } }
}
