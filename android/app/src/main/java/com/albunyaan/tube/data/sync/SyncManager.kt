package com.albunyaan.tube.data.sync

import androidx.room.withTransaction
import com.albunyaan.tube.data.local.*
import com.albunyaan.tube.data.sync.dto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    // Cubic R5 P1 — single global mutex serialising all of bind/pull/push.
    //
    // Pre-fix this class had two independent mutexes (`pullMutex` and
    // `pushMutex`), so a pull and a push could interleave. That created
    // read-modify-write hazards: pull reads server `updatedAt`, push runs
    // concurrently, server writes a new row, pull writes the older row's
    // cursor with a stale tail. A single mutex makes the read/write cycle
    // strictly sequential.
    //
    // `bind()` also acquires the same mutex (cubic R5 P1 #21) so a pull
    // or push in flight when the user account-switches can't slip writes
    // tagged with the wrong uid into the freshly-bound user's tables.
    private val syncMutex = Mutex()

    // Cubic R5 P1 #20 — wire the existing SyncBackoff. Per-instance, holds
    // the current schedule for transient push retries. Reset on a clean
    // drain; advanced on transient failure (5xx/429/network).
    private val pushBackoff = SyncBackoff()

    // Cubic R7 P2 — track the in-flight retry Job so unbind() can cancel it.
    //
    // Pre-fix the retry-after-transient-failure path called `scope.launch {
    // delay(wait); pushDirty(uid) }` from `pushDirtyLocked`. The handle was
    // discarded, so a retry scheduled while the user was still signed in
    // would still fire after a subsequent sign-out → `pushDirty(uid)` with
    // a now-stale uid → request signed with the new user's token (or
    // anonymous) writing the previous user's dirty rows. Cancelling on
    // unbind() makes the retry chain strictly bound to the active session.
    @Volatile private var pendingRetry: kotlinx.coroutines.Job? = null

    suspend fun bind(uid: String) = syncMutex.withLock { bindLocked(uid) }

    private suspend fun bindLocked(uid: String) {
        val b = binding.get()
        when {
            b == null -> {
                binding.upsert(AccountBindingEntity(user_id = uid, bound_at = System.currentTimeMillis(), initial_merge_done = false))
                runMerge(uid)
            }
            b.user_id == uid && b.initial_merge_done -> {
                pullAllLocked(uid)
                pushDirtyLocked(uid)
            }
            b.user_id == uid && !b.initial_merge_done -> {
                // Prior merge crashed mid-way — re-enter
                runMergeLocked(uid)
            }
            else -> {
                // Account switch: wipe old uid's rows. Wrap the whole sequence in a
                // single transaction so a process kill / OOM / OS termination between
                // `binding.clear()` and `binding.upsert(...)` can't leave the device
                // with no account binding *and* freshly-wiped tables — a re-launch
                // would then hit the `b == null` branch and re-merge as a fresh user
                // while the old uid's rows are already gone (Plan D account-switch).
                db.withTransaction {
                    subs.wipeForUid(b.user_id)
                    playlists.wipeForUid(b.user_id)
                    favorites.wipeForUid(b.user_id)
                    syncState.clearForUid(b.user_id)
                    binding.clear()
                    binding.upsert(AccountBindingEntity(uid, System.currentTimeMillis(), false))
                }
                runMergeLocked(uid)
            }
        }
    }

    suspend fun runMerge(uid: String) = syncMutex.withLock { runMergeLocked(uid) }

    private suspend fun runMergeLocked(uid: String) {
        // Step 1: tag anon rows
        subs.tagAnonRowsToUid(uid)
        playlists.tagAnonRowsToUid(uid)
        favorites.tagAnonRowsToUid(uid)
        // Step 2: pull server — collisions overwrite local, clearing dirty
        pullAllLocked(uid)
        // Step 3: push remaining local-only rows
        pushDirtyLocked(uid)
        // Step 4: mark merge done
        binding.markMergeDone(uid)
    }

    suspend fun pullAll(uid: String) = syncMutex.withLock { pullAllLocked(uid) }

    private suspend fun pullAllLocked(uid: String) {
        val cursors = mutableMapOf(
            "subscriptions" to (syncState.cursorFor(uid, "subscriptions") ?: 0L),
            "playlists"     to (syncState.cursorFor(uid, "playlists")     ?: 0L),
            "favorites"     to (syncState.cursorFor(uid, "favorites")     ?: 0L),
        )
        // Compound-cursor tiebreakers (cubic R3/R4 P1) — only persisted for
        // the duration of the pullAll loop. After process death the first
        // pull falls back to the legacy whereGreaterThan(ts) behaviour
        // (which can drop rows tied on the same millisecond at the previous
        // page boundary). Persisting across process death would need a Room
        // migration on sync_state; deferred until that DAO is touched
        // anyway.
        val lastIds = mutableMapOf<String, String?>(
            "subscriptions" to null,
            "playlists"     to null,
            "favorites"     to null,
        )

        var more: Boolean
        do {
            val resp = api.pull(
                cursors["subscriptions"]!!,
                cursors["playlists"]!!,
                cursors["favorites"]!!,
                lastIds["subscriptions"],
                lastIds["playlists"],
                lastIds["favorites"],
            )
            if (!resp.isSuccessful) return
            val body = resp.body() ?: return

            db.withTransaction {
                // Cubic R7 P1 — anon-merge timestamp guard.
                //
                // Pre-fix upsertFromServer (OnConflictStrategy.REPLACE)
                // discarded ANY local row on entityId collision, regardless
                // of which side had the newer write. The motivating scenario:
                //   1. anon-era user creates favorite F at t=100, dirty=1
                //   2. sign-in triggers bind() → tagAnonRowsToUid → F now
                //      user_id=uid, still dirty=1, updated_at=100
                //   3. pullAll fetches server rows; server returns F' with
                //      updated_at=80 (an old write from a different device)
                //   4. REPLACE clobbers the t=100 local row with the t=80
                //      server row — locally-tagged addition silently lost.
                //
                // Guard: before upsertFromServer, check if a local row exists
                // with a fresher updated_at AND dirty=1 (unsynced local edit).
                // If so, skip the server row — pushDirty will resolve it on
                // the next round. applyTombstone keeps its monotonicity
                // guard (R7 P1 in *Dao.applyTombstone).
                for (row in body.subscriptions.items) {
                    if (row.deleted) {
                        subs.applyTombstone(uid, row.entityId, row.updatedAt)
                    } else {
                        val local = subs.getByIdAny(uid, row.entityId)
                        if (local != null && local.dirty && local.updated_at > row.updatedAt) {
                            // Local edit is newer + unsynced; defer to push.
                            continue
                        }
                        subs.upsertFromServer(rowToSub(uid, row))
                    }
                }
                for (row in body.playlists.items) {
                    if (row.deleted) {
                        playlists.applyTombstone(uid, row.entityId, row.updatedAt)
                    } else {
                        val local = playlists.getByIdAny(uid, row.entityId)
                        if (local != null && local.dirty && local.updated_at > row.updatedAt) continue
                        playlists.upsertFromServer(rowToPlaylist(uid, row))
                    }
                }
                for (row in body.favorites.items) {
                    if (row.deleted) {
                        favorites.applyTombstone(uid, row.entityId, row.updatedAt)
                    } else {
                        val local = favorites.getById(uid, row.entityId)
                        if (local != null && local.dirty && local.updated_at > row.updatedAt) continue
                        favorites.upsertFromServer(rowToFavorite(uid, row))
                    }
                }
                // Use the server's (nextCursor, nextCursorId) pair to advance
                // — never the client-side max(items.updatedAt). The previous
                // client-max approach (cubic R5 P0) produced a mismatched
                // compound cursor on next request: ts from client-max, id from
                // server-page-end → overlapping fetches or dropped rows on
                // ties, defeating the R3/R4 compound-cursor fix.
                body.subscriptions.nextCursor?.let {
                    syncState.upsert(SyncStateEntity("subscriptions", uid, it, System.currentTimeMillis()))
                    cursors["subscriptions"] = it
                }
                body.playlists.nextCursor?.let {
                    syncState.upsert(SyncStateEntity("playlists", uid, it, System.currentTimeMillis()))
                    cursors["playlists"] = it
                }
                body.favorites.nextCursor?.let {
                    syncState.upsert(SyncStateEntity("favorites", uid, it, System.currentTimeMillis()))
                    cursors["favorites"] = it
                }
                lastIds["subscriptions"] = body.subscriptions.nextCursorId
                lastIds["playlists"]     = body.playlists.nextCursorId
                lastIds["favorites"]     = body.favorites.nextCursorId
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

    suspend fun pushDirty(uid: String) = syncMutex.withLock { pushDirtyLocked(uid) }

    private suspend fun pushDirtyLocked(uid: String) {
        // Cubic R5 P1 #19 — drain across types is *resilient*, not all-or-nothing.
        //
        // Pre-fix the first transient failure aborted the whole drain: one
        // 5xx on a single subscription row left every other dirty row (in any
        // type) stuck waiting for the next trigger. Now we record the failure
        // and keep going; the row that failed remains `dirty=1` for next cycle,
        // and the rest of the queue makes forward progress.
        //
        // Auth failures (401/403) DO short-circuit — they are not transient
        // and the AccountStatusInterceptor will sign the user out anyway.
        var hadTransientFailure = false
        var authFailed = false

        // Subscriptions
        for (row in subs.selectDirty(uid)) {
            val outcome = if (row.deleted) {
                push({ api.deleteSubscription(row.channelId) },
                    onSuccess = { resp -> subs.clearDirty(uid, row.channelId, resp.updatedAt) },
                    on404    = { subs.clearDirty(uid, row.channelId, System.currentTimeMillis()) })
            } else {
                push({ api.putSubscription(row.channelId, PutSubscriptionRequest(
                    channelUrl = row.channelUrl, name = row.name,
                    avatarUrl  = row.avatarUrl,  subscribedAt = row.subscribedAt)) },
                    onSuccess = { resp -> subs.clearDirty(uid, row.channelId, resp.updatedAt) },
                    on404    = { /* PUT 404 shouldn't happen; surface failure */ })
            }
            when (outcome) {
                PushOutcome.AUTH_FAILED -> { authFailed = true; break }
                PushOutcome.TRANSIENT_FAILURE -> hadTransientFailure = true
                PushOutcome.OK -> { /* keep going */ }
            }
        }
        // Playlists
        if (!authFailed) for (row in playlists.selectDirty(uid)) {
            val outcome = if (row.deleted) {
                push({ api.deletePlaylist(row.playlistId) },
                    onSuccess = { resp -> playlists.clearDirty(uid, row.playlistId, resp.updatedAt) },
                    on404    = { playlists.clearDirty(uid, row.playlistId, System.currentTimeMillis()) })
            } else {
                push({ api.putPlaylist(row.playlistId, PutPlaylistRequest(
                    playlistUrl  = row.playlistUrl,  name         = row.name,
                    thumbnailUrl = row.thumbnailUrl, uploaderName = row.uploaderName,
                    savedAt      = row.savedAt)) },
                    onSuccess = { resp -> playlists.clearDirty(uid, row.playlistId, resp.updatedAt) },
                    on404    = { /* PUT 404 shouldn't happen */ })
            }
            when (outcome) {
                PushOutcome.AUTH_FAILED -> { authFailed = true; break }
                PushOutcome.TRANSIENT_FAILURE -> hadTransientFailure = true
                PushOutcome.OK -> { /* keep going */ }
            }
        }
        // Favorites
        if (!authFailed) for (row in favorites.selectDirty(uid)) {
            val outcome = if (row.deleted) {
                push({ api.deleteFavorite(row.videoId) },
                    onSuccess = { resp -> favorites.clearDirty(uid, row.videoId, resp.updatedAt) },
                    on404    = { favorites.clearDirty(uid, row.videoId, System.currentTimeMillis()) })
            } else {
                push({ api.putFavorite(row.videoId, PutFavoriteRequest(
                    title           = row.title,           channelName     = row.channelName,
                    thumbnailUrl    = row.thumbnailUrl,    durationSeconds = row.durationSeconds,
                    addedAt         = row.addedAt)) },
                    onSuccess = { resp -> favorites.clearDirty(uid, row.videoId, resp.updatedAt) },
                    on404    = { /* PUT 404 shouldn't happen */ })
            }
            when (outcome) {
                PushOutcome.AUTH_FAILED -> { authFailed = true; break }
                PushOutcome.TRANSIENT_FAILURE -> hadTransientFailure = true
                PushOutcome.OK -> { /* keep going */ }
            }
        }

        // Cubic R5 P1 #20 — wire SyncBackoff.
        //
        // On a clean drain reset the schedule (next transient failure starts
        // back at 1s). On any transient failure, advance the schedule and
        // schedule a one-shot retry from the application scope; the syncMutex
        // unlocks first, so the retry waits cleanly behind whatever is next.
        // Auth failure does not retry — AccountStatusInterceptor signs out.
        if (authFailed) {
            pushBackoff.reset()
        } else if (hadTransientFailure) {
            val wait = pushBackoff.next()
            // Cubic R7 P2 — replace any previously-queued retry with this
            // one. The handle is stored so unbind() can cancel it on
            // sign-out; without this an orphan retry can fire post-signout
            // and push rows under the wrong identity.
            pendingRetry?.cancel()
            pendingRetry = scope.launch {
                delay(wait)
                pushDirty(uid)
            }
        } else {
            pushBackoff.reset()
        }
    }

    private enum class PushOutcome { OK, AUTH_FAILED, TRANSIENT_FAILURE }

    /**
     * Classify an HTTP response so the drain loop can decide whether to keep
     * going, retry the cycle later, or short-circuit.
     * - 2xx → onSuccess(body), OK
     * - 404 → on404(), OK (idempotent DELETE; nothing to do)
     * - 401/403 → AUTH_FAILED (AccountStatusInterceptor signs out)
     * - 5xx/429/network/unknown → TRANSIENT_FAILURE (row stays dirty, retry)
     */
    private suspend inline fun <T> push(
        crossinline op: suspend () -> retrofit2.Response<T>,
        crossinline onSuccess: suspend (T) -> Unit,
        crossinline on404: suspend () -> Unit,
    ): PushOutcome {
        val resp = op()
        return when {
            resp.isSuccessful -> { resp.body()?.let { onSuccess(it) }; PushOutcome.OK }
            resp.code() == 404 -> { on404(); PushOutcome.OK }
            resp.code() == 401 || resp.code() == 403 -> PushOutcome.AUTH_FAILED
            else -> PushOutcome.TRANSIENT_FAILURE
        }
    }

    fun unbind() {
        // Cubic R7 P2 — cancel any queued push retry. See `pendingRetry` doc.
        pendingRetry?.cancel()
        pendingRetry = null
        pushBackoff.reset()
    }

    fun pushDirtyAsync(uid: String) { scope.launch { pushDirty(uid) } }
}
