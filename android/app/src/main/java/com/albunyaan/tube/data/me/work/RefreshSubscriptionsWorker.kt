package com.albunyaan.tube.data.me.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.currentUid
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeRefreshTelemetry
import com.albunyaan.tube.data.sync.SyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * ANDROID-PERSONAL-02 / T9: WorkManager-driven refresh of subscribed channel feeds.
 *
 * Replaces the prior `init { refreshFeed() }` in MeViewModel with a single
 * scheduling surface so:
 *  - Backgrounded apps still tick the feed periodically (Doze-aware).
 *  - Cold start does not blast YouTube with 30 fetches simultaneously.
 *  - Pull-to-refresh and the foreground burst share one path.
 *
 * Two modes (driven by [KEY_FORCE]):
 *  - **Periodic / foreground burst** (force=false): processes [PERIODIC_BUDGET]
 *    channels — round-robin oldest-fetch-first per [MeFeedRepository.refresh].
 *    Budget kept small so a 30-channel pool spreads across multiple ticks.
 *  - **Pull-to-refresh** (force=true): processes [PULL_BUDGET] channels and
 *    bypasses the TTL freshness gate AND per-channel backoff so the user
 *    sees results immediately.
 *
 * Failure handling: any unexpected error (Room write failure, OOM, etc.)
 * returns [Result.retry] so WorkManager honours the configured exponential
 * backoff. Per-channel network failures are absorbed by [MeFeedRepository]
 * itself (one channel's 5xx never aborts another channel's fetch).
 *
 * T12 (spec §10 P10): records [MeRefreshTelemetry.Event.MeRefreshStarted]
 * at the top and [MeRefreshTelemetry.Event.MeRefreshFinished] in finally,
 * so panics still surface in the dev-settings telemetry log.
 */
@HiltWorker
class RefreshSubscriptionsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MeFeedRepository,
    private val telemetry: MeRefreshTelemetry,
    private val channelVideoCacheDao: ChannelVideoCacheDao,
    private val syncManager: SyncManager,
    private val accountRepository: AccountRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val mode = if (force) MeRefreshTelemetry.Mode.PULL else MeRefreshTelemetry.Mode.PERIODIC
        val budget = if (force) PULL_BUDGET else PERIODIC_BUDGET

        // T12: candidatesCount is reported as the per-tick budget upper
        // bound rather than the actual size of the round-robin slice. The
        // repository owns the slice computation (refresh-state DAO query +
        // perTickBudget cap) and exposing the post-cap count would mean
        // either a new return type or a separate DAO query here. The budget
        // is the upper bound on what this tick *could* refresh and is
        // sufficient for operator-grade visibility.
        val startedAt = System.currentTimeMillis()
        val startNs = System.nanoTime()
        telemetry.emit(
            MeRefreshTelemetry.Event.MeRefreshStarted(
                timestampMs = startedAt,
                mode = mode,
                candidatesCount = budget,
            )
        )

        var success = false
        var error: String? = null
        try {
            return try {
                withTimeout(WORKER_TIMEOUT_MS) {
                    pullAccountSync()
                    // Cache hygiene first: drop rows for channels the user
                    // has unsubscribed from. Running this BEFORE the refresh
                    // means the refresh path operates on a clean baseline —
                    // refreshing a channel only to immediately prune its
                    // rows is wasted I/O. Wrapped in try/catch so a hygiene
                    // failure (Room session abort, etc.) never demotes a
                    // successful refresh to Result.retry. The DAO call uses
                    // a single parameterised DELETE — atomic per SQLite
                    // statement.
                    //
                    // Time-based prune intentionally NOT called here. A 90-
                    // day cutoff would silently wipe cache rows for channels
                    // that have hit deep-page EOF; MeFeedRepository.fillWeek-
                    // IfNeeded cannot backfill those (deepPageUrl=null), so
                    // the user would see weeks vanish on scroll-back. Table
                    // size is bounded in practice by
                    // subscribed_channels × deep-paged-depth — a few
                    // thousand rows for typical use.
                    try {
                        channelVideoCacheDao.pruneUnsubscribed()
                    } catch (ce: CancellationException) {
                        // Re-throw cancellation — required for structured
                        // concurrency. If withTimeout fired while the prune
                        // was running, swallowing this would let refresh
                        // proceed past the worker's deadline.
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "pruneUnsubscribed failed", t)
                    }
                    repository.refresh(force = force, perTickBudget = budget)
                    // Me-tab playlist videos: fetched after the channel
                    // refresh so the combined NewPipe load stays bounded
                    // by the shared semaphore inside MeFeedRepository and
                    // the network spike happens in a single visible
                    // burst rather than spread across two parallel jobs.
                    // No-op when the playlist deps weren't injected
                    // (test fixtures only).
                    repository.refreshPlaylistVideos()
                }
                success = true
                Result.success()
            } catch (ce: CancellationException) {
                // CoroutineWorker cancellation is the OS revoking the slot
                // (Doze, app uninstall, manual cancel). Re-throw so WorkManager
                // observes the cancel — never absorb cancellation. Telemetry
                // reflects this via `success=false, error="cancelled"` in
                // the finally block before the throw propagates.
                error = "cancelled"
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "refresh failed", t)
                error = t.message ?: t::class.java.simpleName
                Result.retry()
            }
        } finally {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000L
            telemetry.emit(
                MeRefreshTelemetry.Event.MeRefreshFinished(
                    timestampMs = System.currentTimeMillis(),
                    mode = mode,
                    success = success,
                    durationMs = durationMs,
                    error = error,
                )
            )
        }
    }

    /**
     * Pull the server's account state (subscriptions, playlists, favourites) before the feed
     * refresh.
     *
     * An admin's approve/reject decision reaches the device only through this pull — it is what
     * flips an imported row out of AWAITING. Every entry point the user thinks of as "refresh"
     * (pull-to-refresh, the Me-screen foreground burst, the hourly tick) lands in this worker, so
     * doing it here is what makes those gestures able to clear a stale "pending". Running it
     * before the feed refresh means the graduated rows render in the same cycle.
     *
     * A signed-out device has nothing to sync. A failure is logged and swallowed: the user asked
     * for a feed refresh, and losing it to a sync hiccup would be a worse trade than showing one
     * more cycle of stale approval state.
     */
    private suspend fun pullAccountSync() {
        val uid = accountRepository.currentUid()
        if (uid.isEmpty()) return
        try {
            // Its own budget, inside the worker's. SyncManager serialises on a mutex the
            // process-level foreground sync also takes, so without this a contended sync could
            // consume the whole worker deadline and cancel the feed refresh — the one cost this
            // was explicitly not supposed to impose.
            withTimeout(SYNC_BUDGET_MS) {
                syncManager.pullAll(uid)
                // Paired deliberately: pullAll skips any row still marked dirty and defers it to
                // the push. Pulling alone would leave an offline change unsynced and its
                // server-side counterpart skipped on every subsequent pull.
                syncManager.pushDirty(uid)
            }
        } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
            // The sync's own budget ran out. Not the worker's — the feed refresh still gets its turn.
            Log.w(TAG, "account sync exceeded its budget", te)
        } catch (ce: CancellationException) {
            // The worker itself is being cancelled (Doze, the outer deadline, WorkManager stopping
            // us). Swallowing it here would run the feed refresh past the point we were told to
            // stop; structured concurrency requires letting it through.
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "account sync failed", t)
        }
    }

    companion object {
        const val KEY_FORCE = "force"

        /** Budget for the hourly periodic worker — small to spread load. */
        const val PERIODIC_BUDGET = 5

        /** Budget for pull-to-refresh / foreground burst — covers the cap. */
        const val PULL_BUDGET = 30

        const val TAG = "RefreshSubsWorker"

        /**
         * Hard cap on a single tick. With 30 channels at ~250 ms stagger and
         * up to 15 s per fetch (PER_CHANNEL_TIMEOUT_MS), the worst case is
         * ~15 s + 30*250 ms ≈ 22.5 s — 8 minutes is generous headroom for
         * slow networks without ever burning OS-level wakelocks.
         */
        val WORKER_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(8L)

        /** Slice of [WORKER_TIMEOUT_MS] the account sync may take before the feed refresh runs. */
        val SYNC_BUDGET_MS = TimeUnit.MINUTES.toMillis(2L)
    }
}
