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
                //
                // Cubic R-final5 P1 — tag any user_id='' rows to b.user_id BEFORE
                // wiping. MIGRATION_7_8 stamped every pre-v8 row with user_id=''
                // (the column didn't exist before v8). Pre-fix the switch branch
                // wipeForUid(A) skipped those rows (their user_id was '' not A),
                // then runMergeLocked → tagAnonRowsToUid(B) re-tagged them to the
                // new user, effectively transferring A's local data to B's account
                // and pushing it as B's data. Tagging '' → A here unifies the
                // anon-era rows with the previous owner so wipeForUid(A) catches
                // them on the same line.
                subs.tagAnonRowsToUid(b.user_id)
                playlists.tagAnonRowsToUid(b.user_id)
                favorites.tagAnonRowsToUid(b.user_id)
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
        // Compound-cursor tiebreakers (cubic R3/R4 P1), persisted in Room v9
        // (SYNC-CURSOR-PERSIST-01 + MIGRATION_8_9 add last_doc_id to
        // sync_state). The (cursor_ts, last_doc_id) pair survives process
        // death, eliminating the post-restart row-drop window for rows tied
        // on the same millisecond at the previous page boundary.
        val lastIds = mutableMapOf<String, String?>(
            "subscriptions" to syncState.cursorIdFor(uid, "subscriptions"),
            "playlists"     to syncState.cursorIdFor(uid, "playlists"),
            "favorites"     to syncState.cursorIdFor(uid, "favorites"),
        )

        var more: Boolean
        do {
            // Cubic R-final5 P1 — bounded retry on transient 5xx mid-pull.
            //
            // Pre-fix a single 5xx aborted the loop and we returned silently.
            // Prior page iterations had already persisted last_doc_id / cursor,
            // so the partial advance left the local view ahead of confirmed
            // pulled rows — pushDirty then drained against a stale view.
            // Three attempts with 200ms / 400ms backoff matches the push-side
            // transient-failure handling. A persistent 4xx still aborts (the
            // server is telling us the request is bad, not stalled); only
            // 5xx and IOExceptions retry.
            var resp: retrofit2.Response<com.albunyaan.tube.data.sync.dto.SyncResponseDto>? = null
            for (attempt in 1..3) {
                val attemptResp = try {
                    api.pull(
                        cursors["subscriptions"]!!,
                        cursors["playlists"]!!,
                        cursors["favorites"]!!,
                        lastIds["subscriptions"],
                        lastIds["playlists"],
                        lastIds["favorites"],
                    )
                } catch (e: java.io.IOException) {
                    if (attempt < 3) { kotlinx.coroutines.delay(200L * attempt); continue }
                    return
                }
                if (attemptResp.isSuccessful) { resp = attemptResp; break }
                val code = attemptResp.code()
                if (code in 500..599 && attempt < 3) {
                    kotlinx.coroutines.delay(200L * attempt); continue
                }
                resp = attemptResp; break
            }
            if (resp == null || !resp.isSuccessful) return
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
                        // Cubic R-final P2: dirty=1 alone is the conflict signal.
                        if (local != null && local.dirty) {
                            // Local edit unsynced; defer to push.
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
                        // Cubic R-final P2: drop the `local.updated_at > row.updatedAt` clause.
                        // Local writes never bumped updated_at (the column is
                        // server-stamped on push success via clearDirty), so the
                        // timestamp comparison was vacuous. The `dirty=1` flag is
                        // sufficient: it already means "local has an unsynced
                        // edit"; the upcoming push will resolve the conflict.
                        if (local != null && local.dirty) continue
                        playlists.upsertFromServer(rowToPlaylist(uid, row))
                    }
                }
                for (row in body.favorites.items) {
                    if (row.deleted) {
                        favorites.applyTombstone(uid, row.entityId, row.updatedAt)
                    } else {
                        val local = favorites.getByIdAny(uid, row.entityId)
                        // Cubic R-final P2: drop the `local.updated_at > row.updatedAt` clause.
                        // Local writes never bumped updated_at (the column is
                        // server-stamped on push success via clearDirty), so the
                        // timestamp comparison was vacuous. The `dirty=1` flag is
                        // sufficient: it already means "local has an unsynced
                        // edit"; the upcoming push will resolve the conflict.
                        if (local != null && local.dirty) continue
                        favorites.upsertFromServer(rowToFavorite(uid, row))
                    }
                }
                // Use the server's (nextCursor, nextCursorId) pair to advance
                // — never the client-side max(items.updatedAt). The previous
                // client-max approach (cubic R5 P0) produced a mismatched
                // compound cursor on next request: ts from client-max, id from
                // server-page-end → overlapping fetches or dropped rows on
                // ties, defeating the R3/R4 compound-cursor fix.
                // SYNC-CURSOR-PERSIST-01 — persist (cursor_ts, last_doc_id)
                // atomically. Both round-trip in the same Room write so the
                // post-restart resume sees a consistent compound cursor.
                body.subscriptions.nextCursor?.let {
                    syncState.upsert(SyncStateEntity(
                        entityType   = "subscriptions",
                        user_id      = uid,
                        last_cursor  = it,
                        last_doc_id  = body.subscriptions.nextCursorId,
                        last_sync_at = System.currentTimeMillis()))
                    cursors["subscriptions"] = it
                }
                body.playlists.nextCursor?.let {
                    syncState.upsert(SyncStateEntity(
                        entityType   = "playlists",
                        user_id      = uid,
                        last_cursor  = it,
                        last_doc_id  = body.playlists.nextCursorId,
                        last_sync_at = System.currentTimeMillis()))
                    cursors["playlists"] = it
                }
                body.favorites.nextCursor?.let {
                    syncState.upsert(SyncStateEntity(
                        entityType   = "favorites",
                        user_id      = uid,
                        last_cursor  = it,
                        last_doc_id  = body.favorites.nextCursorId,
                        last_sync_at = System.currentTimeMillis()))
                    cursors["favorites"] = it
                }
                // Cubic R-final2 P2 — only overwrite lastIds for the types
                // that advanced this iteration. Pre-fix lastIds was set to
                // body.X.nextCursorId unconditionally; when a type was
                // exhausted mid-loop (returned nextCursor=null and
                // nextCursorId=null) while another type still had pages,
                // the next iteration sent (cursor=T_old, lastDocId=null) for
                // the exhausted type. A new row written by another device
                // with updated_at=T_old could be silently dropped by the
                // server's strict-> branch (no tiebreaker). Pairing the
                // update with the cursor update keeps (cursor_ts, doc_id)
                // consistent across iterations.
                body.subscriptions.nextCursor?.let {
                    lastIds["subscriptions"] = body.subscriptions.nextCursorId
                }
                body.playlists.nextCursor?.let {
                    lastIds["playlists"] = body.playlists.nextCursorId
                }
                body.favorites.nextCursor?.let {
                    lastIds["favorites"] = body.favorites.nextCursorId
                }
            }
            // Cubic R-final P3 — known bounded waste: when one type is
            // exhausted (returned null) but another still has more pages,
            // each subsequent iteration re-sends the exhausted type's
            // cursor and the server returns zero rows for that type. The
            // cost is at most PAGE_SIZE wasted Firestore reads per type
            // per pull cycle, deemed acceptable for the simpler client
            // loop. Per-type exhaustion tracking would require restructuring
            // the request DTO (per-type "stop sending this" flag).
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
                    onSuccess = { resp ->
                        // SYNC-ECHO-01 — archive-echo: if server projection
                        // says this row is virtually tombstoned (parent is
                        // archived), apply the tombstone locally instead of
                        // clearing dirty. Pre-fix the row stayed alive
                        // locally until the next pull cycle.
                        if (resp.deleted) {
                            subs.applyTombstone(uid, row.channelId, resp.updatedAt)
                        } else {
                            subs.clearDirty(uid, row.channelId, resp.updatedAt)
                        }
                    },
                    on404    = { /* PUT 404 shouldn't happen; surface failure */ })
            }
            when (outcome) {
                PushOutcome.AUTH_FAILED -> { authFailed = true; break }
                PushOutcome.TRANSIENT_FAILURE -> hadTransientFailure = true
                PushOutcome.PERMANENT_FAILURE -> {
                    // Cubic R-final5 P2 — drop dirty so we don't loop forever
                    // on a malformed/conflicting row.
                    subs.clearDirty(uid, row.channelId, System.currentTimeMillis())
                }
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
                    onSuccess = { resp ->
                        if (resp.deleted) {
                            playlists.applyTombstone(uid, row.playlistId, resp.updatedAt)
                        } else {
                            playlists.clearDirty(uid, row.playlistId, resp.updatedAt)
                        }
                    },
                    on404    = { /* PUT 404 shouldn't happen */ })
            }
            when (outcome) {
                PushOutcome.AUTH_FAILED -> { authFailed = true; break }
                PushOutcome.TRANSIENT_FAILURE -> hadTransientFailure = true
                PushOutcome.PERMANENT_FAILURE -> {
                    playlists.clearDirty(uid, row.playlistId, System.currentTimeMillis())
                }
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
                    onSuccess = { resp ->
                        if (resp.deleted) {
                            favorites.applyTombstone(uid, row.videoId, resp.updatedAt)
                        } else {
                            favorites.clearDirty(uid, row.videoId, resp.updatedAt)
                        }
                    },
                    on404    = { /* PUT 404 shouldn't happen */ })
            }
            when (outcome) {
                PushOutcome.AUTH_FAILED -> { authFailed = true; break }
                PushOutcome.TRANSIENT_FAILURE -> hadTransientFailure = true
                PushOutcome.PERMANENT_FAILURE -> {
                    favorites.clearDirty(uid, row.videoId, System.currentTimeMillis())
                }
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

    private enum class PushOutcome { OK, AUTH_FAILED, PERMANENT_FAILURE, TRANSIENT_FAILURE }

    /**
     * Classify an HTTP response so the drain loop can decide whether to keep
     * going, retry the cycle later, or short-circuit.
     * - 2xx → onSuccess(body), OK
     * - 404 → on404(), OK (idempotent DELETE; nothing to do)
     * - 401/403 → AUTH_FAILED (AccountStatusInterceptor signs out)
     * - 400/409/422 → PERMANENT_FAILURE — server says the payload is bad,
     *   retrying with the same payload will keep failing. Cubic R-final5 P2:
     *   drop the dirty flag with a local-WARN so subsequent pulls don't
     *   block on a forever-failing push.
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
            resp.code() == 400 || resp.code() == 409 || resp.code() == 422 -> {
                android.util.Log.w("SyncManager",
                    "Push permanent-fail code=${resp.code()} — dropping dirty flag to unblock pull")
                PushOutcome.PERMANENT_FAILURE
            }
            else -> PushOutcome.TRANSIENT_FAILURE
        }
    }

    suspend fun unbind() = syncMutex.withLock {
        // Cubic R7 P2 — cancel any queued push retry. See `pendingRetry` doc.
        // Cubic R8 P2 — must acquire `syncMutex` first. Pre-R8 unbind ran
        // without the lock, racing with `pushDirtyLocked`'s
        // `pendingRetry?.cancel(); pendingRetry = scope.launch{...}`
        // assignment. If unbind interleaved between cancel and assign — or
        // simply observed the field before pushDirtyLocked published its new
        // Job — the newly scheduled retry survived sign-out and fired
        // `pushDirty(uid)` with a stale uid (the exact bug R7 P2 closed).
        // `@Volatile` provides visibility but not check-then-set atomicity;
        // the mutex closes that gap by serialising unbind with pushDirty.
        pendingRetry?.cancel()
        pendingRetry = null
        pushBackoff.reset()
    }

    fun pushDirtyAsync(uid: String) { scope.launch { pushDirty(uid) } }
}
