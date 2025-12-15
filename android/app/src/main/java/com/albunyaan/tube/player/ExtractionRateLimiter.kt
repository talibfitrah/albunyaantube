package com.albunyaan.tube.player

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiter for YouTube stream extraction/refresh operations.
 *
 * Prevents excessive extraction calls that could trigger YouTube rate limiting or
 * cause unnecessary network usage during recovery loops.
 *
 * Key design: Records attempts BEFORE extraction (not after success) to prevent
 * request storms on failures/timeouts/retries.
 *
 * Strategy:
 * - Tracks extraction attempts per video ID with timestamps
 * - Enforces minimum interval between extractions for the same video
 * - Enforces global extraction rate across all videos
 * - Different ceilings for different request kinds (manual, auto-recovery, prefetch)
 * - Applies exponential backoff for repeated force refreshes
 *
 * Guardrails:
 * - Per-video: Max 3 attempts per 5 minutes
 * - Global: Max 10 attempts per minute across all videos
 * - Auto-recovery gets 2 reserved attempts even if manual budget exhausted
 * - Backoff: Exponential delay after repeated attempts (2s, 4s, 8s, 16s, 32s, capped at 60s)
 */
class ExtractionRateLimiter(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    companion object {
        private const val TAG = "ExtractionRateLimiter"

        /** Minimum interval between extractions for the same video */
        private const val MIN_EXTRACTION_INTERVAL_MS = 30_000L // 30 seconds

        /** Time window for per-video rate limiting */
        private const val PER_VIDEO_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

        /** Maximum attempts per video within the window (all kinds combined) */
        private const val MAX_ATTEMPTS_PER_VIDEO = 3

        /** Time window for global rate limiting */
        private const val GLOBAL_WINDOW_MS = 60_000L // 1 minute

        /** Maximum global attempts within the window */
        private const val MAX_GLOBAL_ATTEMPTS = 10

        /** Reserved budget for auto-recovery (guaranteed attempts even when manual exhausted) */
        private const val AUTO_RECOVERY_RESERVED_ATTEMPTS = 2

        /** Base backoff delay for exponential backoff */
        private const val BACKOFF_BASE_MS = 2_000L

        /** Maximum backoff delay */
        private const val MAX_BACKOFF_MS = 60_000L // 1 minute max

        /** Cleanup interval - remove stale entries */
        private const val CLEANUP_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
    }

    /**
     * Kind of extraction request - different kinds have different rate limit budgets.
     */
    enum class RequestKind {
        /** User manually triggered refresh (strict limits, spam protection) */
        MANUAL,
        /** Automatic recovery during playback failure (more lenient, must not block recovery) */
        AUTO_RECOVERY,
        /** Background prefetch for next items (lowest priority, can be skipped) */
        PREFETCH
    }

    /** Tracks extraction history per video ID */
    private data class ExtractionRecord(
        val attemptTimestamps: MutableList<Long> = mutableListOf(),
        var consecutiveAttempts: Int = 0,
        var lastAttemptTime: Long = 0L,
        var autoRecoveryAttemptsInWindow: Int = 0
    )

    private val perVideoRecords = ConcurrentHashMap<String, ExtractionRecord>()
    private val globalAttemptTimestamps = mutableListOf<Long>()
    @Volatile
    private var lastCleanupTime = 0L

    /**
     * Result of a rate limit check.
     */
    sealed class RateLimitResult {
        /** Extraction is allowed to proceed */
        object Allowed : RateLimitResult()

        /** Extraction should wait - returns recommended delay in milliseconds */
        data class Delayed(val delayMs: Long, val reason: String) : RateLimitResult()

        /** Extraction is blocked - too many attempts */
        data class Blocked(val reason: String, val retryAfterMs: Long) : RateLimitResult()
    }

    /**
     * Acquire a permit for extraction. This records the attempt IMMEDIATELY
     * (before the extraction call) to prevent request storms on failures.
     *
     * @param videoId The video ID to extract
     * @param kind The kind of request (affects rate limit budget)
     * @return RateLimitResult indicating whether extraction can proceed
     */
    fun acquire(videoId: String, kind: RequestKind): RateLimitResult {
        val now = clock()
        maybeCleanup(now)

        // Check global rate limit first
        val globalResult = checkGlobalLimit(now)
        if (globalResult !is RateLimitResult.Allowed) {
            Log.w(TAG, "Global rate limit hit for $videoId ($kind): $globalResult")
            return globalResult
        }

        // Check per-video rate limit
        val record = perVideoRecords.getOrPut(videoId) { ExtractionRecord() }
        synchronized(record) {
            // Clean old timestamps
            record.attemptTimestamps.removeAll { now - it > PER_VIDEO_WINDOW_MS }

            // Reset auto-recovery counter if window expired
            if (record.attemptTimestamps.isEmpty()) {
                record.autoRecoveryAttemptsInWindow = 0
            }

            // Check minimum interval (applies to all kinds)
            if (record.lastAttemptTime > 0) {
                val timeSinceLastAttempt = now - record.lastAttemptTime
                if (timeSinceLastAttempt < MIN_EXTRACTION_INTERVAL_MS) {
                    // Auto-recovery gets a pass on minimum interval for first attempt
                    val isFirstAutoRecovery = kind == RequestKind.AUTO_RECOVERY &&
                            record.autoRecoveryAttemptsInWindow == 0
                    if (!isFirstAutoRecovery) {
                        val delayMs = MIN_EXTRACTION_INTERVAL_MS - timeSinceLastAttempt
                        Log.d(TAG, "Min interval not elapsed for $videoId ($kind): ${delayMs}ms remaining")
                        return RateLimitResult.Delayed(delayMs, "minimum interval")
                    }
                }
            }

            // Check per-video limit with kind-specific handling
            val attemptsInWindow = record.attemptTimestamps.size

            when (kind) {
                RequestKind.AUTO_RECOVERY -> {
                    // Auto-recovery has reserved budget - can proceed even if manual exhausted
                    if (record.autoRecoveryAttemptsInWindow >= AUTO_RECOVERY_RESERVED_ATTEMPTS) {
                        // Check if we're also over total budget
                        if (attemptsInWindow >= MAX_ATTEMPTS_PER_VIDEO) {
                            val oldestInWindow = record.attemptTimestamps.minOrNull() ?: now
                            val retryAfter = oldestInWindow + PER_VIDEO_WINDOW_MS - now
                            Log.w(TAG, "Auto-recovery budget exhausted for $videoId")
                            return RateLimitResult.Blocked(
                                "auto-recovery limit ($AUTO_RECOVERY_RESERVED_ATTEMPTS per window)",
                                retryAfter.coerceAtLeast(0L)
                            )
                        }
                    }
                }
                RequestKind.MANUAL -> {
                    // Manual has strict limits
                    if (attemptsInWindow >= MAX_ATTEMPTS_PER_VIDEO) {
                        val oldestInWindow = record.attemptTimestamps.minOrNull() ?: now
                        val retryAfter = oldestInWindow + PER_VIDEO_WINDOW_MS - now
                        Log.w(TAG, "Per-video limit reached for $videoId ($kind): $attemptsInWindow attempts in window")
                        return RateLimitResult.Blocked(
                            "per-video limit ($MAX_ATTEMPTS_PER_VIDEO in ${PER_VIDEO_WINDOW_MS / 60000}min)",
                            retryAfter.coerceAtLeast(0L)
                        )
                    }

                    // Apply exponential backoff for manual force refreshes
                    if (record.consecutiveAttempts > 0) {
                        val backoffMs = calculateBackoff(record.consecutiveAttempts)
                        val timeSinceLastAttempt = now - record.lastAttemptTime
                        if (timeSinceLastAttempt < backoffMs) {
                            val delayMs = backoffMs - timeSinceLastAttempt
                            Log.d(TAG, "Backoff for $videoId: attempt=${record.consecutiveAttempts}, delay=${delayMs}ms")
                            return RateLimitResult.Delayed(delayMs, "exponential backoff")
                        }
                    }
                }
                RequestKind.PREFETCH -> {
                    // Prefetch is lowest priority - blocked if any budget pressure
                    if (attemptsInWindow >= MAX_ATTEMPTS_PER_VIDEO - 1) {
                        Log.d(TAG, "Prefetch blocked for $videoId: preserving budget for manual/recovery")
                        return RateLimitResult.Blocked(
                            "prefetch blocked (budget reserved)",
                            PER_VIDEO_WINDOW_MS
                        )
                    }
                }
            }

            // RECORD THE ATTEMPT NOW (before extraction call)
            record.attemptTimestamps.add(now)
            record.lastAttemptTime = now
            record.consecutiveAttempts++
            if (kind == RequestKind.AUTO_RECOVERY) {
                record.autoRecoveryAttemptsInWindow++
            }
        }

        // Record global attempt
        synchronized(globalAttemptTimestamps) {
            globalAttemptTimestamps.add(now)
        }

        Log.d(TAG, "Permit acquired for $videoId ($kind)")
        return RateLimitResult.Allowed
    }

    /**
     * Call when extraction succeeds to reset backoff state.
     * This does NOT affect the attempt count - attempts are already recorded in acquire().
     */
    fun onExtractionSuccess(videoId: String) {
        perVideoRecords[videoId]?.let { record ->
            synchronized(record) {
                record.consecutiveAttempts = 0
            }
        }
        Log.d(TAG, "Extraction success for $videoId - backoff reset")
    }

    /**
     * Reset the rate limit state for a specific video.
     * Call this when a video plays successfully to clear backoff state.
     */
    fun resetForVideo(videoId: String) {
        perVideoRecords[videoId]?.let { record ->
            synchronized(record) {
                record.consecutiveAttempts = 0
            }
        }
        Log.d(TAG, "Reset rate limit state for $videoId")
    }

    /**
     * Clear all rate limit state.
     */
    fun clear() {
        perVideoRecords.clear()
        synchronized(globalAttemptTimestamps) {
            globalAttemptTimestamps.clear()
        }
        Log.d(TAG, "Cleared all rate limit state")
    }

    /**
     * Get current attempt count for a video in the current window.
     */
    fun getAttemptCount(videoId: String): Int {
        val now = clock()
        val record = perVideoRecords[videoId] ?: return 0
        synchronized(record) {
            return record.attemptTimestamps.count { now - it <= PER_VIDEO_WINDOW_MS }
        }
    }

    /**
     * Get global attempt count in the current window.
     */
    fun getGlobalAttemptCount(): Int {
        val now = clock()
        synchronized(globalAttemptTimestamps) {
            return globalAttemptTimestamps.count { now - it <= GLOBAL_WINDOW_MS }
        }
    }

    private fun checkGlobalLimit(now: Long): RateLimitResult {
        synchronized(globalAttemptTimestamps) {
            // Clean old timestamps
            globalAttemptTimestamps.removeAll { now - it > GLOBAL_WINDOW_MS }

            if (globalAttemptTimestamps.size >= MAX_GLOBAL_ATTEMPTS) {
                val oldestInWindow = globalAttemptTimestamps.minOrNull() ?: now
                val retryAfter = oldestInWindow + GLOBAL_WINDOW_MS - now
                return RateLimitResult.Blocked(
                    "global limit ($MAX_GLOBAL_ATTEMPTS per minute)",
                    retryAfter.coerceAtLeast(0L)
                )
            }
        }
        return RateLimitResult.Allowed
    }

    private fun calculateBackoff(attempts: Int): Long {
        // Exponential backoff: 2s, 4s, 8s, 16s, 32s, capped at 60s
        val backoff = BACKOFF_BASE_MS * (1L shl (attempts - 1).coerceAtMost(5))
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun maybeCleanup(now: Long) {
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) return
        lastCleanupTime = now

        // Remove stale per-video records
        val staleThreshold = now - PER_VIDEO_WINDOW_MS * 2
        perVideoRecords.entries.removeIf { (_, record) ->
            synchronized(record) {
                record.attemptTimestamps.all { it < staleThreshold }
            }
        }

        Log.d(TAG, "Cleanup complete: ${perVideoRecords.size} video records remaining")
    }
}
