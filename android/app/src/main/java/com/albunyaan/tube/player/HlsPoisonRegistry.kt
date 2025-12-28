package com.albunyaan.tube.player

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1B: HLS Poison Registry
 *
 * Tracks videos whose HLS streams have failed (typically with 403) and should be
 * avoided for a cooling-off period. This prevents repeatedly attempting HLS when
 * it's known to fail for specific videos.
 *
 * **Problem this solves:**
 * Some videos' HLS streams consistently return 403 errors (possibly due to geo-restrictions,
 * account requirements, or YouTube-side issues). Without this registry, we'd keep trying
 * HLS first, failing, then falling back to progressive - wasting time and API requests.
 *
 * **Proactive gate (before selection):**
 * Before choosing HLS for a video, check `isHlsPoisoned(videoId)`. If true, skip HLS
 * and use DASH or progressive directly.
 *
 * **Reactive poison (on early 403):**
 * If HLS returns 403 within the first ~5-10 seconds of playback, call `poisonHls(videoId)`
 * to prevent future HLS attempts for this video (until TTL expires).
 *
 * **TTL-based expiry:**
 * Poison entries expire after a configurable TTL (default 30 minutes). This allows
 * retry after potential transient issues resolve, while avoiding repeated failures
 * during active sessions.
 *
 * Visibility: All operations logged with "HlsPoison" tag for debugging.
 */
@Singleton
class HlsPoisonRegistry @Inject constructor() {

    companion object {
        private const val TAG = "HlsPoison"

        /**
         * Default time-to-live for poison entries (30 minutes).
         * After this duration, the video will be eligible for HLS again.
         */
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L // 30 minutes

        /**
         * Early 403 detection window. If HLS returns 403 within this time after
         * playback starts, it's considered a definitive failure worth poisoning.
         */
        const val EARLY_403_WINDOW_MS = 10_000L // 10 seconds

        /**
         * Maximum number of poison entries to keep. Prevents unbounded memory growth.
         * When exceeded, oldest entries are evicted.
         */
        private const val MAX_ENTRIES = 100
    }

    /**
     * Poison entry with expiration time.
     */
    private data class PoisonEntry(
        val videoId: String,
        val poisonedAtMs: Long,
        val expiresAtMs: Long,
        val reason: String
    ) {
        fun isExpired(): Boolean = SystemClock.elapsedRealtime() > expiresAtMs
    }

    // Poisoned videos by videoId
    private val poisonedVideos = ConcurrentHashMap<String, PoisonEntry>()

    /** Lock for atomic eviction operations */
    private val evictionLock = Any()

    /**
     * Check if HLS is poisoned for this video.
     *
     * Call this BEFORE attempting to use HLS. If true, skip HLS and use DASH/progressive.
     *
     * @param videoId The video ID to check
     * @return true if HLS should be avoided for this video
     */
    fun isHlsPoisoned(videoId: String): Boolean {
        val entry = poisonedVideos[videoId] ?: return false

        if (entry.isExpired()) {
            // Clean up expired entry
            poisonedVideos.remove(videoId)
            Log.d(TAG, "HLS poison expired for $videoId, allowing HLS retry")
            return false
        }

        val remainingMs = entry.expiresAtMs - SystemClock.elapsedRealtime()
        Log.d(TAG, "HLS poisoned for $videoId (${remainingMs / 1000}s remaining): ${entry.reason}")
        return true
    }

    /**
     * Mark HLS as poisoned for this video.
     *
     * Call this when HLS fails with 403 (or similar definitive failure) early in playback.
     * The video will be excluded from HLS selection until the TTL expires.
     *
     * @param videoId The video ID to poison
     * @param reason Human-readable reason for the poison (for logging)
     * @param ttlMs Time-to-live in milliseconds (default: 30 minutes)
     */
    fun poisonHls(
        videoId: String,
        reason: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ) {
        val now = SystemClock.elapsedRealtime()
        val entry = PoisonEntry(
            videoId = videoId,
            poisonedAtMs = now,
            expiresAtMs = now + ttlMs,
            reason = reason
        )

        // Synchronize insertion and eviction to prevent exceeding MAX_ENTRIES
        synchronized(evictionLock) {
            poisonedVideos[videoId] = entry
            Log.w(TAG, "HLS poisoned for $videoId (TTL: ${ttlMs / 1000}s): $reason")

            if (poisonedVideos.size > MAX_ENTRIES) {
                evictOldestEntries()
            }
        }
    }

    /**
     * Remove poison for a video. Call if HLS succeeds after being poisoned
     * (shouldn't normally happen due to TTL, but allows manual clearing).
     */
    fun clearPoison(videoId: String) {
        poisonedVideos.remove(videoId)?.let {
            Log.d(TAG, "HLS poison cleared for $videoId")
        }
    }

    /**
     * Clear all poison entries. Call on app restart or user-initiated reset.
     */
    fun clearAll() {
        poisonedVideos.clear()
        Log.d(TAG, "All HLS poison entries cleared")
    }

    /**
     * Get count of currently poisoned videos (for debugging/metrics).
     */
    fun getPoisonedCount(): Int {
        // Clean up expired entries while counting - synchronized for consistent count
        synchronized(evictionLock) {
            val now = SystemClock.elapsedRealtime()
            val expiredKeys = poisonedVideos.entries
                .filter { it.value.expiresAtMs < now }
                .map { it.key }
            expiredKeys.forEach { key -> poisonedVideos.remove(key) }
            return poisonedVideos.size
        }
    }

    /**
     * Check if a 403 error is "early" enough to warrant poisoning.
     *
     * @param playbackStartMs The SystemClock.elapsedRealtime() when playback started
     * @return true if the error occurred within the early detection window
     */
    fun isEarly403(playbackStartMs: Long): Boolean {
        val elapsed = SystemClock.elapsedRealtime() - playbackStartMs
        return elapsed < EARLY_403_WINDOW_MS
    }

    /**
     * Evict oldest entries to make room. Should be called within synchronized(evictionLock).
     */
    private fun evictOldestEntries() {
        // Remove entries that would leave us at max capacity
        val toRemove = poisonedVideos.size - MAX_ENTRIES + 10 // Remove 10 extra for headroom
        if (toRemove <= 0) return

        // Take a snapshot, sort, and remove (all within the synchronized block)
        val keysToRemove = poisonedVideos.entries
            .sortedBy { it.value.poisonedAtMs }
            .take(toRemove)
            .map { it.key }

        keysToRemove.forEach { poisonedVideos.remove(it) }

        Log.d(TAG, "Evicted ${keysToRemove.size} oldest HLS poison entries")
    }
}
