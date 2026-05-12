package com.albunyaan.tube.data.sync

import com.albunyaan.tube.data.local.*
import com.albunyaan.tube.data.sync.dto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

    suspend fun pullAll(uid: String) { /* Task 23 */ }
    suspend fun pushDirty(uid: String) { /* Task 24 */ }
    fun unbind() { /* in-memory clear; nothing persistent to flush */ }

    fun pushDirtyAsync(uid: String) { scope.launch { pushDirty(uid) } }
}
