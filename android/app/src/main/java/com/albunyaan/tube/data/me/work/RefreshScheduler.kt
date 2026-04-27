package com.albunyaan.tube.data.me.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/**
 * ANDROID-PERSONAL-02 / T9: single scheduling surface for the Me-tab
 * refresh worker.
 *
 * Three entry points:
 *  - [enqueuePeriodic]: idempotent cold-start hook (KEEP semantics) that
 *    arms the hourly tick. Called once from [com.albunyaan.tube.AlBunyaanApplication.onCreate].
 *  - [enqueueForegroundBurstIfStale]: called from `MeFragment.onResume`.
 *    Fires a one-shot only when the newest cached fetch is older than
 *    [staleThresholdMs] — avoids hammering YouTube every time the user
 *    swipes away and back. Uses ExistingWorkPolicy.KEEP so a still-running
 *    burst is not duplicated.
 *  - [enqueuePullToRefresh]: called from the SwipeRefreshLayout listener.
 *    Force=true bypasses TTL + backoff; ExistingWorkPolicy.REPLACE so a
 *    user re-pull cancels a stale prior burst and starts over.
 */
@Singleton
class RefreshScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val refreshStateDao: ChannelFeedRefreshStateDao,
) {
    /**
     * Idempotently arm the hourly periodic worker. Safe to call from
     * Application.onCreate — KEEP semantics mean a re-install or process
     * restart does not collapse an already-armed schedule.
     */
    fun enqueuePeriodic() {
        val request = PeriodicWorkRequestBuilder<RefreshSubscriptionsWorker>(
            PERIODIC_INTERVAL_MIN, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5L, TimeUnit.MINUTES)
            .setInitialDelay(30L, TimeUnit.SECONDS)
            .addTag(TAG_REFRESH)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Foreground burst: only fire a one-shot when the newest successful
     * fetch across all channels is older than [staleThresholdMs]. The
     * default of 30 minutes matches [com.albunyaan.tube.data.me.MeFeedRepository.CACHE_TTL_MS]
     * so the burst is exactly what the TTL gate would have allowed anyway.
     */
    suspend fun enqueueForegroundBurstIfStale(staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS) {
        val newest = refreshStateDao.maxLastSuccessfulFetchAt() ?: 0L
        if (System.currentTimeMillis() - newest < staleThresholdMs) return
        val request = OneTimeWorkRequestBuilder<RefreshSubscriptionsWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_REFRESH)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            UNIQUE_ONESHOT_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Pull-to-refresh: force=true bypasses TTL + backoff, REPLACE semantics
     * so user-initiated retries cancel the prior in-flight burst.
     */
    fun enqueuePullToRefresh() {
        val request = OneTimeWorkRequestBuilder<RefreshSubscriptionsWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(RefreshSubscriptionsWorker.KEY_FORCE to true))
            .addTag(TAG_REFRESH)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            UNIQUE_ONESHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "me_refresh_periodic"
        const val UNIQUE_ONESHOT_NAME = "me_refresh_oneshot"
        const val TAG_REFRESH = "com.albunyaan.tube.me.refresh"

        /**
         * Periodic interval. WorkManager rounds anything < 15 minutes up to
         * 15 minutes; we use 60 minutes so the periodic tick aligns with
         * the spec §5 "hourly background refresh" requirement.
         */
        const val PERIODIC_INTERVAL_MIN = 60L

        /**
         * Default foreground-burst stale threshold (30 min). Chosen to
         * match MeFeedRepository.CACHE_TTL_MS — burst-fires only when
         * the TTL gate would have let a refresh through anyway.
         */
        const val DEFAULT_STALE_THRESHOLD_MS = 30L * 60L * 1_000L
    }
}
