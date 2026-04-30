package com.albunyaan.tube.data.me.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeRefreshTelemetry
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
                    repository.refresh(force = force, perTickBudget = budget)
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
