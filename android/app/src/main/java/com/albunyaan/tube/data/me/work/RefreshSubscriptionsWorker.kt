package com.albunyaan.tube.data.me.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.albunyaan.tube.data.me.MeFeedRepository
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
 */
@HiltWorker
class RefreshSubscriptionsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MeFeedRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        withTimeout(WORKER_TIMEOUT_MS) {
            val force = inputData.getBoolean(KEY_FORCE, false)
            val budget = if (force) PULL_BUDGET else PERIODIC_BUDGET
            repository.refresh(force = force, perTickBudget = budget)
        }
        Result.success()
    } catch (ce: CancellationException) {
        // CoroutineWorker cancellation is the OS revoking the slot
        // (Doze, app uninstall, manual cancel). Re-throw so WorkManager
        // observes the cancel — never absorb cancellation.
        throw ce
    } catch (t: Throwable) {
        Log.e(TAG, "refresh failed", t)
        Result.retry()
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
    }
}
