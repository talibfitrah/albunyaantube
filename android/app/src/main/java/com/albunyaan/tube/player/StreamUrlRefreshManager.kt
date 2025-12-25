package com.albunyaan.tube.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages preemptive stream URL refresh to prevent mid-playback 403 errors.
 *
 * YouTube stream URLs typically expire after ~6 hours. This manager:
 * - Tracks when URLs were obtained for each video
 * - Schedules preemptive refresh before URLs expire
 * - Provides TTL information for decision making
 *
 * **Design Decision: Preemptive vs Reactive Refresh**
 *
 * Reactive (current): Wait for 403, then refresh and resume.
 * - PRO: Simple, no wasted refreshes
 * - CON: User sees playback interruption
 *
 * Preemptive (this manager): Refresh URLs before expiry.
 * - PRO: Seamless experience, no interruption
 * - CON: May refresh URLs that won't be used (user pauses, seeks away)
 *
 * This implementation uses a hybrid approach:
 * 1. Track stream age passively
 * 2. Check TTL before critical operations (seek, quality change)
 * 3. Schedule refresh when TTL drops below threshold during active playback
 * 4. Caller decides when to actually perform refresh (avoids wasted network calls)
 */
@Singleton
class StreamUrlRefreshManager @Inject constructor(
    private val telemetry: StreamRequestTelemetry,
    private val rateLimiter: ExtractionRateLimiter
) {
    companion object {
        private const val TAG = "StreamUrlRefresh"

        /**
         * Estimated YouTube stream URL TTL.
         * Conservative estimate: actual may be 6-8 hours.
         */
        const val ESTIMATED_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

        /**
         * Safety threshold for preemptive refresh.
         * Refresh URLs when TTL remaining is less than this value.
         */
        const val PREEMPTIVE_REFRESH_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes

        /**
         * Critical threshold - URLs may fail soon.
         * Used for urgent refresh decisions.
         */
        const val CRITICAL_TTL_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes

        /**
         * Minimum interval between refresh checks for same video.
         */
        private const val MIN_CHECK_INTERVAL_MS = 60 * 1000L // 1 minute
    }

    /**
     * Stream URL metadata for TTL tracking.
     *
     * **Thread Safety**: This class is immutable. Updates are performed by replacing
     * the entire object in the ConcurrentHashMap using atomic compute operations.
     * This ensures thread-safe read-modify-write cycles.
     */
    data class StreamUrlInfo(
        val videoId: String,
        val resolvedAtMs: Long,
        val estimatedExpiryMs: Long = resolvedAtMs + ESTIMATED_TTL_MS,
        val lastCheckMs: Long = 0L,
        val refreshScheduled: Boolean = false
    ) {
        val ageMs: Long
            get() = System.currentTimeMillis() - resolvedAtMs

        val ttlRemainingMs: Long
            get() = (estimatedExpiryMs - System.currentTimeMillis()).coerceAtLeast(0)

        val isExpired: Boolean
            get() = ttlRemainingMs <= 0

        val needsPreemptiveRefresh: Boolean
            get() = ttlRemainingMs < PREEMPTIVE_REFRESH_THRESHOLD_MS

        val isCritical: Boolean
            get() = ttlRemainingMs < CRITICAL_TTL_THRESHOLD_MS

        /** Create updated copy with new lastCheckMs */
        fun withLastCheck(checkMs: Long): StreamUrlInfo = copy(lastCheckMs = checkMs)

        /** Create updated copy with new refreshScheduled flag */
        fun withRefreshScheduled(scheduled: Boolean): StreamUrlInfo = copy(refreshScheduled = scheduled)
    }

    /**
     * Callback for when a preemptive refresh is needed.
     */
    interface RefreshCallback {
        /**
         * Called when URLs should be refreshed.
         * @param videoId The video that needs URL refresh
         * @param isCritical True if TTL is critically low
         * @return True if refresh was initiated, false if skipped
         */
        fun onRefreshNeeded(videoId: String, isCritical: Boolean): Boolean
    }

    private val urlInfoMap = ConcurrentHashMap<String, StreamUrlInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var refreshCallback: RefreshCallback? = null
    private var currentlyPlayingVideoId: String? = null
    private val scheduledRefreshes = ConcurrentHashMap<String, Runnable>()

    /**
     * Get the currently playing video ID.
     * Primarily for testing - production code should track this externally.
     */
    fun getCurrentlyPlaying(): String? = currentlyPlayingVideoId

    /**
     * Register callback for refresh notifications.
     */
    fun setRefreshCallback(callback: RefreshCallback?) {
        refreshCallback = callback
    }

    /**
     * Called when stream URLs are resolved for a video.
     * Records the resolution time for TTL tracking.
     */
    fun onStreamResolved(videoId: String) {
        val now = System.currentTimeMillis()
        val info = StreamUrlInfo(
            videoId = videoId,
            resolvedAtMs = now
        )
        urlInfoMap[videoId] = info
        telemetry.onStreamResolved(videoId)

        Log.d(TAG, "Stream resolved for $videoId, TTL ~${ESTIMATED_TTL_MS / 60_000}m")

        // Cancel any pending refresh for this video (new URLs obtained)
        cancelScheduledRefresh(videoId)
    }

    /**
     * Called when stream URLs are refreshed (forced refresh or preemptive).
     * Updates the resolution time.
     */
    fun onStreamRefreshed(videoId: String) {
        onStreamResolved(videoId)
    }

    /**
     * Set the currently playing video for proactive monitoring.
     */
    fun setCurrentlyPlaying(videoId: String?) {
        val previousVideoId = currentlyPlayingVideoId
        currentlyPlayingVideoId = videoId

        // Cancel monitoring for previous video
        if (previousVideoId != null && previousVideoId != videoId) {
            cancelScheduledRefresh(previousVideoId)
        }

        // Start monitoring new video if needed
        if (videoId != null) {
            checkAndScheduleRefresh(videoId)
        }
    }

    /**
     * Check if URLs for a video need refresh.
     *
     * @param videoId Video ID
     * @return StreamUrlInfo if known, null if not tracked
     */
    fun getUrlInfo(videoId: String): StreamUrlInfo? = urlInfoMap[videoId]

    /**
     * Check if URLs should be refreshed before an operation.
     * Use this before seek, quality change, or other operations that rely on valid URLs.
     *
     * @param videoId Video ID
     * @return True if refresh is recommended
     */
    fun shouldRefreshBeforeOperation(videoId: String): Boolean {
        val info = urlInfoMap[videoId] ?: return false
        return info.needsPreemptiveRefresh
    }

    /**
     * Check TTL and determine if playback can continue safely.
     *
     * @param videoId Video ID
     * @return Remaining TTL in milliseconds, or null if unknown
     */
    fun getTtlRemainingMs(videoId: String): Long? {
        return urlInfoMap[videoId]?.ttlRemainingMs
    }

    /**
     * Check if URLs are in critical state (about to expire).
     */
    fun isUrlCritical(videoId: String): Boolean {
        return urlInfoMap[videoId]?.isCritical == true
    }

    /**
     * Manually trigger a refresh check for a video.
     * Returns true if refresh is needed.
     *
     * Thread-safe: Uses atomic compute to update lastCheckMs.
     */
    fun checkRefreshNeeded(videoId: String): Boolean {
        val now = System.currentTimeMillis()
        var needsRefresh = false

        // Atomic read-check-write: update lastCheckMs only if throttle interval elapsed
        urlInfoMap.compute(videoId) { _, existing ->
            if (existing == null) {
                needsRefresh = false
                null // No info, nothing to update
            } else if (now - existing.lastCheckMs < MIN_CHECK_INTERVAL_MS) {
                // Throttled - return cached result without updating
                needsRefresh = existing.needsPreemptiveRefresh
                existing // Keep existing, no update needed
            } else {
                // Update lastCheckMs and return refresh status
                needsRefresh = existing.needsPreemptiveRefresh
                existing.withLastCheck(now)
            }
        }

        return needsRefresh
    }

    /**
     * Schedule a preemptive refresh if needed.
     *
     * Thread-safe: Reads refreshScheduled atomically via snapshot.
     */
    private fun checkAndScheduleRefresh(videoId: String) {
        // Get a consistent snapshot of the info
        val info = urlInfoMap[videoId] ?: return

        // Already scheduled - check via snapshot (racing schedules are harmless)
        if (info.refreshScheduled) return

        if (info.needsPreemptiveRefresh) {
            // Needs refresh now
            triggerRefresh(videoId, info.isCritical)
        } else {
            // Schedule for when TTL drops below threshold
            val delayMs = info.ttlRemainingMs - PREEMPTIVE_REFRESH_THRESHOLD_MS
            if (delayMs > 0) {
                scheduleRefresh(videoId, delayMs)
            }
        }
    }

    /**
     * Thread-safe: Uses atomic compute to update refreshScheduled flag.
     */
    private fun scheduleRefresh(videoId: String, delayMs: Long) {
        // Cancel existing schedule first
        cancelScheduledRefresh(videoId)

        // Atomically set refreshScheduled = true
        val updated = urlInfoMap.compute(videoId) { _, existing ->
            existing?.withRefreshScheduled(true)
        }
        if (updated == null) return

        val runnable = Runnable {
            // Atomically set refreshScheduled = false when runnable executes
            urlInfoMap.computeIfPresent(videoId) { _, existing ->
                existing.withRefreshScheduled(false)
            }
            if (currentlyPlayingVideoId == videoId) {
                triggerRefresh(videoId, isUrlCritical(videoId))
            }
        }
        scheduledRefreshes[videoId] = runnable
        handler.postDelayed(runnable, delayMs)

        Log.d(TAG, "Scheduled preemptive refresh for $videoId in ${delayMs / 60_000}m")
    }

    /**
     * Thread-safe: Uses atomic compute to clear refreshScheduled flag.
     */
    private fun cancelScheduledRefresh(videoId: String) {
        scheduledRefreshes.remove(videoId)?.let { runnable ->
            handler.removeCallbacks(runnable)
        }
        // Atomically set refreshScheduled = false
        urlInfoMap.computeIfPresent(videoId) { _, existing ->
            existing.withRefreshScheduled(false)
        }
    }

    private fun triggerRefresh(videoId: String, isCritical: Boolean) {
        val callback = refreshCallback
        if (callback == null) {
            Log.w(TAG, "Refresh needed for $videoId but no callback registered")
            return
        }

        // Check rate limiter before refreshing
        val rateLimitResult = rateLimiter.acquire(videoId, ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)
        when (rateLimitResult) {
            is ExtractionRateLimiter.RateLimitResult.Blocked -> {
                Log.w(TAG, "Preemptive refresh blocked by rate limiter: ${rateLimitResult.reason}")
                return
            }
            is ExtractionRateLimiter.RateLimitResult.Delayed -> {
                // Schedule retry after delay
                scheduleRefresh(videoId, rateLimitResult.delayMs)
                return
            }
            is ExtractionRateLimiter.RateLimitResult.Allowed -> {
                // Continue with refresh
            }
        }

        scope.launch {
            try {
                val initiated = callback.onRefreshNeeded(videoId, isCritical)
                if (initiated) {
                    Log.d(TAG, "Preemptive refresh initiated for $videoId (critical=$isCritical)")
                } else {
                    Log.d(TAG, "Preemptive refresh skipped for $videoId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering refresh for $videoId", e)
            }
        }
    }

    /**
     * Clear tracking for a video (e.g., when it's no longer in queue).
     */
    fun clearVideo(videoId: String) {
        cancelScheduledRefresh(videoId)
        urlInfoMap.remove(videoId)
        telemetry.clearResolutionTime(videoId)
    }

    /**
     * Clear all tracking data.
     */
    fun clear() {
        scheduledRefreshes.keys.toList().forEach { cancelScheduledRefresh(it) }
        urlInfoMap.clear()
        currentlyPlayingVideoId = null
    }

    /**
     * Release resources.
     */
    fun release() {
        clear()
        scope.cancel()
    }
}
