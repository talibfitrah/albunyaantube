package com.albunyaan.tube.player

import android.os.SystemClock
import android.util.Log
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitors buffer health for progressive streams and triggers proactive quality downshift
 * before buffering stalls occur.
 *
 * Strategy:
 * - Samples buffer (bufferedPosition - currentPosition) every SAMPLE_INTERVAL_MS
 * - Tracks buffer trend over the last TREND_WINDOW_SIZE samples
 * - When buffer is LOW and DECLINING for consecutive samples, triggers proactive downshift
 * - Coordinates with PlaybackRecoveryManager: disabled during recovery states
 *
 * Guardrails:
 * - Grace period after stream start (GRACE_PERIOD_MS)
 * - Cooldown between proactive downshifts (DOWNSHIFT_COOLDOWN_MS)
 * - Maximum proactive downshifts per stream (MAX_PROACTIVE_DOWNSHIFTS)
 * - Only active for progressive streams (adaptive streams have ABR)
 */
class BufferHealthMonitor(
    private val scope: CoroutineScope,
    private val callbacks: BufferHealthCallbacks
) {
    companion object {
        private const val TAG = "BufferHealthMonitor"

        /** Sample buffer every 1 second */
        private const val SAMPLE_INTERVAL_MS = 1_000L

        /** Consider buffer "low" when below 5 seconds */
        private const val LOW_BUFFER_THRESHOLD_MS = 5_000L

        /** Critical buffer threshold - urgent action needed */
        private const val CRITICAL_BUFFER_THRESHOLD_MS = 2_000L

        /** Number of samples to track for trend detection */
        private const val TREND_WINDOW_SIZE = 5

        /** Trigger downshift after this many consecutive declining samples when buffer is low */
        private const val DECLINING_SAMPLES_THRESHOLD = 3

        /** Cooldown between proactive downshifts */
        private const val DOWNSHIFT_COOLDOWN_MS = 30_000L

        /** Grace period after stream start before monitoring */
        private const val GRACE_PERIOD_MS = 10_000L

        /** Maximum proactive downshifts per stream */
        const val MAX_PROACTIVE_DOWNSHIFTS = 3

        /** Minimum buffer change to consider as "declining" (avoid noise) */
        private const val MIN_DECLINE_THRESHOLD_MS = 200L
    }

    interface BufferHealthCallbacks {
        /**
         * Called when proactive downshift is recommended.
         * @return true if downshift was successful, false otherwise
         */
        fun onProactiveDownshiftRequested(): Boolean

        /**
         * Check if player is currently in recovery state.
         * Proactive monitoring is disabled during recovery.
         */
        fun isInRecoveryState(): Boolean
    }

    // Monitoring state
    private var monitoringJob: Job? = null
    private var isProgressiveStream = false
    private var currentStreamId: String? = null

    // Buffer samples for trend detection
    private val bufferSamples = ArrayDeque<Long>(TREND_WINDOW_SIZE + 1)
    private var consecutiveDecliningCount = 0

    // Guardrail state
    private var streamStartTimeMs = 0L
    private var lastDownshiftTimeMs = 0L
    private var proactiveDownshiftCount = 0

    /**
     * Call when a new stream starts playing.
     * Resets all tracking state and starts monitoring if progressive.
     */
    fun onNewStream(streamId: String, isAdaptive: Boolean) {
        Log.d(TAG, "New stream: $streamId, isAdaptive=$isAdaptive")

        // Stop any existing monitoring
        stopMonitoring()

        // Reset state - all tracking is per-stream
        currentStreamId = streamId
        isProgressiveStream = !isAdaptive
        bufferSamples.clear()
        consecutiveDecliningCount = 0
        streamStartTimeMs = SystemClock.elapsedRealtime()
        proactiveDownshiftCount = 0
        lastDownshiftTimeMs = 0L // Reset per stream - new video shouldn't inherit previous cooldown

        // Only monitor progressive streams (adaptive has ABR)
        if (isProgressiveStream) {
            Log.d(TAG, "Starting buffer health monitoring for progressive stream")
        } else {
            Log.d(TAG, "Skipping monitoring - adaptive stream has ABR")
        }
    }

    /**
     * Call when playback starts (state becomes PLAYING).
     * Begins active monitoring for progressive streams.
     */
    fun onPlaybackStarted(player: Player) {
        if (!isProgressiveStream) return

        stopMonitoring()
        monitoringJob = scope.launch {
            monitorBufferHealth(player)
        }
    }

    /**
     * Call when playback pauses or stops.
     * Pauses monitoring to save resources.
     */
    fun onPlaybackPaused() {
        stopMonitoring()
    }

    /**
     * Call when stream ends or player is released.
     */
    fun release() {
        stopMonitoring()
        currentStreamId = null
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    private suspend fun monitorBufferHealth(player: Player) {
        Log.d(TAG, "Buffer health monitoring started")

        while (scope.isActive) {
            delay(SAMPLE_INTERVAL_MS)

            // Skip if in recovery state
            if (callbacks.isInRecoveryState()) {
                Log.v(TAG, "Skipping sample - in recovery state")
                bufferSamples.clear()
                consecutiveDecliningCount = 0
                continue
            }

            // Skip if player is not playing
            if (!player.isPlaying) {
                continue
            }

            // Sample current buffer (clamp to >= 0 for discontinuities/seeks)
            val currentPosition = player.currentPosition
            val bufferedPosition = player.bufferedPosition
            val bufferMs = (bufferedPosition - currentPosition).coerceAtLeast(0L)

            // Add sample
            bufferSamples.addLast(bufferMs)
            if (bufferSamples.size > TREND_WINDOW_SIZE) {
                bufferSamples.removeFirst()
            }

            // Check if we should consider proactive downshift
            evaluateBufferHealth(bufferMs)
        }

        Log.d(TAG, "Buffer health monitoring stopped")
    }

    private fun evaluateBufferHealth(currentBufferMs: Long) {
        val now = SystemClock.elapsedRealtime()

        // Grace period check
        val elapsed = now - streamStartTimeMs
        if (elapsed < GRACE_PERIOD_MS) {
            Log.v(TAG, "Buffer: ${currentBufferMs}ms (grace period: ${GRACE_PERIOD_MS - elapsed}ms remaining)")
            return
        }

        // Max downshifts check
        if (proactiveDownshiftCount >= MAX_PROACTIVE_DOWNSHIFTS) {
            Log.v(TAG, "Buffer: ${currentBufferMs}ms (max proactive downshifts reached)")
            return
        }

        // Cooldown check
        val timeSinceLastDownshift = now - lastDownshiftTimeMs
        if (lastDownshiftTimeMs > 0 && timeSinceLastDownshift < DOWNSHIFT_COOLDOWN_MS) {
            Log.v(TAG, "Buffer: ${currentBufferMs}ms (cooldown: ${DOWNSHIFT_COOLDOWN_MS - timeSinceLastDownshift}ms remaining)")
            return
        }

        // Check buffer level
        val isLowBuffer = currentBufferMs < LOW_BUFFER_THRESHOLD_MS
        val isCriticalBuffer = currentBufferMs < CRITICAL_BUFFER_THRESHOLD_MS

        // Check trend
        val isDeclining = isBufferDeclining()

        if (isDeclining) {
            consecutiveDecliningCount++
        } else {
            consecutiveDecliningCount = 0
        }

        Log.d(TAG, "Buffer: ${currentBufferMs}ms, low=$isLowBuffer, critical=$isCriticalBuffer, " +
                "declining=$isDeclining, consecutiveDeclines=$consecutiveDecliningCount")

        // Decision logic:
        // - Critical buffer AND declining: immediate action
        // - Low buffer AND multiple consecutive declines: proactive action
        val shouldDownshift = when {
            isCriticalBuffer && isDeclining -> {
                Log.w(TAG, "CRITICAL: Buffer at ${currentBufferMs}ms and declining - requesting immediate downshift")
                true
            }
            isLowBuffer && consecutiveDecliningCount >= DECLINING_SAMPLES_THRESHOLD -> {
                Log.w(TAG, "PROACTIVE: Buffer low (${currentBufferMs}ms) with $consecutiveDecliningCount consecutive declines - requesting downshift")
                true
            }
            else -> false
        }

        if (shouldDownshift) {
            requestProactiveDownshift()
        }
    }

    /**
     * Check if buffer is declining based on recent samples.
     * Compares the last two samples to detect immediate declining trend.
     */
    private fun isBufferDeclining(): Boolean {
        if (bufferSamples.size < 3) return false

        // Simple approach: compare latest sample to the one before
        val samples = bufferSamples.toList()
        val latest = samples.last()
        val previous = samples[samples.size - 2]

        // Buffer is declining if it dropped by more than threshold
        val decline = previous - latest
        return decline > MIN_DECLINE_THRESHOLD_MS
    }

    private fun requestProactiveDownshift() {
        Log.i(TAG, "Requesting proactive quality downshift (attempt ${proactiveDownshiftCount + 1}/$MAX_PROACTIVE_DOWNSHIFTS)")

        val success = callbacks.onProactiveDownshiftRequested()

        if (success) {
            proactiveDownshiftCount++
            lastDownshiftTimeMs = SystemClock.elapsedRealtime()
            consecutiveDecliningCount = 0
            bufferSamples.clear() // Reset samples after successful downshift
            Log.i(TAG, "Proactive downshift successful (count=$proactiveDownshiftCount)")
        } else {
            Log.w(TAG, "Proactive downshift failed or no lower quality available")
            // Don't count failed attempts against max, but do apply cooldown
            lastDownshiftTimeMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Get current proactive downshift count for this stream.
     */
    fun getProactiveDownshiftCount(): Int = proactiveDownshiftCount

    /**
     * Check if proactive downshift is available (not at max).
     */
    fun canProactiveDownshift(): Boolean = proactiveDownshiftCount < MAX_PROACTIVE_DOWNSHIFTS
}
