package com.albunyaan.tube.analytics

import android.os.SystemClock
import android.util.Log
import com.albunyaan.tube.player.MediaSourceResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 Playback Metrics Collector
 *
 * Tracks key playback metrics for baseline measurement and regression detection:
 * - start_success: Whether playback reached isPlaying=true
 * - ttff_ms: Time to first frame (tap -> first frame)
 * - rebuffer_count_per_min: Rebuffering events per minute of playback
 * - source_type_used: HLS/DASH/SYNTH_ADAPTIVE/SYNTH_SINGLE/PROGRESSIVE
 * - media_source_rebuild_count: Rebuilds during quality changes
 * - 403_rate: HTTP 403 error classification and rate
 *
 * All metrics are logged with a consistent tag for easy filtering:
 * `adb logcat -s PlaybackMetrics`
 */
@Singleton
class PlaybackMetricsCollector @Inject constructor() {

    companion object {
        private const val TAG = "PlaybackMetrics"

        // Source type constants matching MediaSourceResult.AdaptiveType
        const val SOURCE_HLS = "HLS"
        const val SOURCE_DASH = "DASH"
        const val SOURCE_SYNTHETIC_DASH = "SYNTHETIC_DASH"
        const val SOURCE_SYNTH_ADAPTIVE = "SYNTH_ADAPTIVE"
        const val SOURCE_PROGRESSIVE = "PROGRESSIVE"
        const val SOURCE_AUDIO_ONLY = "AUDIO_ONLY"

        // 403 classification constants
        const val ERROR_403_UNKNOWN = "UNKNOWN_403"
        const val ERROR_403_EXPIRED = "URL_EXPIRED"
        const val ERROR_403_GEO = "GEO_RESTRICTED"
        const val ERROR_403_RATE_LIMIT = "RATE_LIMITED"

        /**
         * Maximum number of active sessions to keep in memory.
         * Prevents unbounded memory growth from playlist navigation.
         */
        private const val MAX_ACTIVE_SESSIONS = 20
    }

    /**
     * Per-session playback metrics for a single video.
     *
     * Thread safety: Uses @Volatile for simple fields and CopyOnWriteArrayList
     * for the classifications list to handle concurrent access from different callbacks.
     */
    data class PlaybackSession(
        val videoId: String,
        val sessionId: String = "${videoId}_${SystemClock.elapsedRealtime()}",
        @Volatile var tapTimestampMs: Long = 0L,
        @Volatile var firstFrameTimestampMs: Long = 0L,
        @Volatile var playbackStartTimestampMs: Long = 0L,
        @Volatile var sourceType: String? = null,
        val startSuccess: AtomicBoolean = AtomicBoolean(false),
        val rebufferCount: AtomicInteger = AtomicInteger(0),
        val mediaSourceRebuildCount: AtomicInteger = AtomicInteger(0),
        val error403Count: AtomicInteger = AtomicInteger(0),
        val error403Classifications: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList(),
        val totalPlaybackDurationMs: AtomicLong = AtomicLong(0L),
        @Volatile var lastPlaybackResumeMs: Long = 0L,
        @Volatile var isPlaying: Boolean = false
    ) {
        val ttffMs: Long
            get() = if (firstFrameTimestampMs > 0 && tapTimestampMs > 0) {
                firstFrameTimestampMs - tapTimestampMs
            } else -1L

        val rebufferPerMin: Float
            get() {
                val durationMin = totalPlaybackDurationMs.get() / 60_000f
                return if (durationMin > 0) rebufferCount.get() / durationMin else 0f
            }

        val error403Rate: Float
            get() {
                val durationMin = totalPlaybackDurationMs.get() / 60_000f
                return if (durationMin > 0) error403Count.get() / durationMin else 0f
            }

        fun toLogString(): String {
            val ttff = if (ttffMs >= 0) "${ttffMs}ms" else "N/A"
            return """
                |=== Playback Session Metrics ===
                |Session: $sessionId
                |Video: $videoId
                |Start Success: ${startSuccess.get()}
                |TTFF: $ttff
                |Source Type: ${sourceType ?: "UNKNOWN"}
                |Rebuffer Count: ${rebufferCount.get()}
                |Rebuffer/min: %.2f
                |MediaSource Rebuilds: ${mediaSourceRebuildCount.get()}
                |403 Errors: ${error403Count.get()}
                |403 Rate/min: %.2f
                |403 Classifications: ${error403Classifications.joinToString(", ")}
                |Total Playback: ${totalPlaybackDurationMs.get() / 1000}s
                |================================
            """.trimMargin().format(rebufferPerMin, error403Rate)
        }
    }

    // Active sessions by videoId
    private val activeSessions = ConcurrentHashMap<String, PlaybackSession>()
    /** Insertion order for FIFO eviction */
    private val sessionInsertionOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Lock for atomic operations on both activeSessions and sessionInsertionOrder */
    private val sessionLock = Any()

    // Global counters for aggregate metrics
    private val totalStartAttempts = AtomicInteger(0)
    private val totalStartSuccesses = AtomicInteger(0)
    private val total403Errors = AtomicInteger(0)
    private val totalRebuffers = AtomicInteger(0)
    private val totalMediaSourceRebuilds = AtomicInteger(0)

    // Source type distribution
    private val sourceTypeCounts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Called when user taps to play a video. Marks the start of TTFF measurement.
     */
    fun onPlaybackRequested(videoId: String) {
        val session = getOrCreateSession(videoId)
        session.tapTimestampMs = SystemClock.elapsedRealtime()
        totalStartAttempts.incrementAndGet()
        Log.d(TAG, "playback_requested videoId=$videoId sessionId=${session.sessionId}")
    }

    /**
     * Called when media source is created. Records the source type.
     */
    fun onMediaSourceCreated(videoId: String, adaptiveType: MediaSourceResult.AdaptiveType, isAudioOnly: Boolean) {
        val session = getOrCreateSession(videoId)
        val sourceType = when {
            isAudioOnly -> SOURCE_AUDIO_ONLY
            else -> when (adaptiveType) {
                MediaSourceResult.AdaptiveType.HLS -> SOURCE_HLS
                MediaSourceResult.AdaptiveType.DASH -> SOURCE_DASH
                MediaSourceResult.AdaptiveType.SYNTHETIC_DASH -> SOURCE_SYNTHETIC_DASH
                MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE -> SOURCE_SYNTH_ADAPTIVE
                MediaSourceResult.AdaptiveType.NONE -> SOURCE_PROGRESSIVE
            }
        }
        session.sourceType = sourceType
        sourceTypeCounts.getOrPut(sourceType) { AtomicInteger(0) }.incrementAndGet()
        Log.d(TAG, "media_source_created videoId=$videoId sourceType=$sourceType")
    }

    /**
     * Called when first video frame is rendered. Completes TTFF measurement.
     */
    fun onFirstFrameRendered(videoId: String) {
        val session = getOrCreateSession(videoId)
        if (session.firstFrameTimestampMs == 0L) {
            session.firstFrameTimestampMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "first_frame_rendered videoId=$videoId ttff_ms=${session.ttffMs}")
        }
    }

    /**
     * Called when playback starts (isPlaying=true). Marks start success.
     */
    fun onPlaybackStarted(videoId: String) {
        val session = getOrCreateSession(videoId)
        // Use compareAndSet to atomically check and set startSuccess
        if (session.startSuccess.compareAndSet(false, true)) {
            session.playbackStartTimestampMs = SystemClock.elapsedRealtime()
            totalStartSuccesses.incrementAndGet()
        }
        session.isPlaying = true
        session.lastPlaybackResumeMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "playback_started videoId=$videoId success=true ttff_ms=${session.ttffMs} source=${session.sourceType}")
    }

    /**
     * Called when playback pauses or stops.
     */
    fun onPlaybackPaused(videoId: String) {
        val session = activeSessions[videoId] ?: return
        // Synchronize to atomically check isPlaying and read lastPlaybackResumeMs
        synchronized(session) {
            if (session.isPlaying && session.lastPlaybackResumeMs > 0) {
                val playedDuration = SystemClock.elapsedRealtime() - session.lastPlaybackResumeMs
                session.totalPlaybackDurationMs.addAndGet(playedDuration)
            }
            session.isPlaying = false
        }
        Log.d(TAG, "playback_paused videoId=$videoId totalDuration_ms=${session.totalPlaybackDurationMs.get()}")
    }

    /**
     * Called when player enters buffering state (rebuffering).
     */
    fun onRebufferingStarted(videoId: String) {
        val session = getOrCreateSession(videoId)
        // Only count rebuffers after initial playback has started
        if (session.startSuccess.get()) {
            session.rebufferCount.incrementAndGet()
            totalRebuffers.incrementAndGet()
            Log.d(TAG, "rebuffer_started videoId=$videoId count=${session.rebufferCount.get()}")
        }
    }

    /**
     * Called when MediaSource is rebuilt (e.g., on quality change for progressive streams).
     */
    fun onMediaSourceRebuilt(videoId: String, reason: String) {
        val session = getOrCreateSession(videoId)
        session.mediaSourceRebuildCount.incrementAndGet()
        totalMediaSourceRebuilds.incrementAndGet()
        Log.d(TAG, "media_source_rebuilt videoId=$videoId reason=$reason count=${session.mediaSourceRebuildCount.get()}")
    }

    /**
     * Called when HTTP 403 error occurs.
     */
    fun on403Error(videoId: String, classification: String) {
        val session = getOrCreateSession(videoId)
        session.error403Count.incrementAndGet()
        session.error403Classifications.add(classification)
        total403Errors.incrementAndGet()
        Log.w(TAG, "error_403 videoId=$videoId classification=$classification count=${session.error403Count.get()}")
    }

    /**
     * Called when playback session ends. Logs final metrics and cleans up.
     */
    fun onSessionEnded(videoId: String) {
        val session = synchronized(sessionLock) {
            val s = activeSessions.remove(videoId) ?: return
            sessionInsertionOrder.remove(videoId)
            s
        }
        // Finalize playback duration if still playing (synchronized to prevent TOCTOU race)
        synchronized(session) {
            if (session.isPlaying && session.lastPlaybackResumeMs > 0) {
                val playedDuration = SystemClock.elapsedRealtime() - session.lastPlaybackResumeMs
                session.totalPlaybackDurationMs.addAndGet(playedDuration)
            }
        }
        Log.i(TAG, session.toLogString())
    }

    /**
     * Called when playback fails to start.
     * Note: Does not reset startSuccess if playback had already started successfully,
     * as mid-playback failures are different from startup failures.
     */
    fun onPlaybackFailed(videoId: String, reason: String) {
        val session = getOrCreateSession(videoId)
        // Only log failure - don't reset startSuccess if it was already set to true
        // (mid-playback failures are tracked separately from startup failures)
        Log.e(TAG, "playback_failed videoId=$videoId reason=$reason startSuccess=${session.startSuccess.get()}")
    }

    /**
     * Get aggregate metrics summary for logging/debugging.
     */
    fun getAggregateSummary(): String {
        val startSuccessRate = if (totalStartAttempts.get() > 0) {
            totalStartSuccesses.get().toFloat() / totalStartAttempts.get() * 100
        } else 0f

        val sourceDistribution = sourceTypeCounts.entries.joinToString(", ") { "${it.key}:${it.value.get()}" }

        return """
            |=== Aggregate Playback Metrics ===
            |Start Attempts: ${totalStartAttempts.get()}
            |Start Successes: ${totalStartSuccesses.get()}
            |Start Success Rate: %.1f%%
            |Total Rebuffers: ${totalRebuffers.get()}
            |Total 403 Errors: ${total403Errors.get()}
            |Total MediaSource Rebuilds: ${totalMediaSourceRebuilds.get()}
            |Source Type Distribution: $sourceDistribution
            |Active Sessions: ${activeSessions.size}
            |==================================
        """.trimMargin().format(startSuccessRate)
    }

    /**
     * Log aggregate metrics summary.
     */
    fun logAggregateSummary() {
        Log.i(TAG, getAggregateSummary())
    }

    /**
     * Reset all metrics (for testing or new baseline capture).
     */
    fun reset() {
        synchronized(sessionLock) {
            activeSessions.clear()
            sessionInsertionOrder.clear()
        }
        totalStartAttempts.set(0)
        totalStartSuccesses.set(0)
        total403Errors.set(0)
        totalRebuffers.set(0)
        totalMediaSourceRebuilds.set(0)
        sourceTypeCounts.clear()
        Log.d(TAG, "metrics_reset")
    }

    /**
     * Get current session for a video (for detailed debugging).
     */
    fun getSession(videoId: String): PlaybackSession? = activeSessions[videoId]

    private fun getOrCreateSession(videoId: String): PlaybackSession {
        // Fast path: check if already exists without locking
        activeSessions[videoId]?.let { return it }

        // Slow path: synchronized creation and eviction
        synchronized(sessionLock) {
            // Double-check after acquiring lock
            activeSessions[videoId]?.let { return it }

            // Evict oldest if at capacity (log final metrics before eviction)
            while (activeSessions.size >= MAX_ACTIVE_SESSIONS) {
                val oldest = sessionInsertionOrder.poll()
                oldest?.let { evictedId ->
                    activeSessions.remove(evictedId)?.let { session ->
                        // Finalize and log metrics for evicted session (synchronized to prevent TOCTOU race)
                        synchronized(session) {
                            if (session.isPlaying && session.lastPlaybackResumeMs > 0) {
                                val playedDuration = SystemClock.elapsedRealtime() - session.lastPlaybackResumeMs
                                session.totalPlaybackDurationMs.addAndGet(playedDuration)
                            }
                        }
                        // Log full metrics for evicted sessions, same as onSessionEnded
                        Log.i(TAG, "EVICTED (capacity): " + session.toLogString())
                    }
                } ?: break
            }

            // Create new session and track insertion order
            val newSession = PlaybackSession(videoId)
            activeSessions[videoId] = newSession
            sessionInsertionOrder.offer(videoId)
            return newSession
        }
    }
}
