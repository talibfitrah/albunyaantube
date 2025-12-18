package com.albunyaan.tube.player

import android.util.Log
import androidx.media3.common.Player
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
 * - PREDICTIVE: When buffer is declining and projected to hit critical in < PREDICTIVE_STALL_HORIZON_MS, trigger downshift
 * - EARLY-STALL EXCEPTION: Allow one immediate downshift during grace period if buffer becomes critical
 * - Coordinates with PlaybackRecoveryManager: disabled during recovery states
 *
 * Guardrails:
 * - Grace period after stream start (GRACE_PERIOD_MS) - but bypassed for critical buffer situations
 * - Cooldown between proactive downshifts (DOWNSHIFT_COOLDOWN_MS)
 * - Maximum proactive downshifts per stream (MAX_PROACTIVE_DOWNSHIFTS)
 * - Only active for progressive streams (adaptive streams have ABR)
 */
class BufferHealthMonitor(
    private val scope: CoroutineScope,
    private val callbacks: BufferHealthCallbacks,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    companion object {
        private const val TAG = "BufferHealthMonitor"

        /** Sample buffer every 1 second */
        private const val SAMPLE_INTERVAL_MS = 1_000L

        /** Consider buffer "low" when below 10 seconds (increased from 5s for earlier action) */
        private const val LOW_BUFFER_THRESHOLD_MS = 10_000L

        /** Critical buffer threshold - urgent action needed */
        private const val CRITICAL_BUFFER_THRESHOLD_MS = 3_000L

        /** Number of samples to track for trend detection */
        private const val TREND_WINDOW_SIZE = 5

        /** Trigger downshift after this many consecutive declining samples when buffer is low */
        private const val DECLINING_SAMPLES_THRESHOLD = 3

        /** Cooldown between proactive downshifts */
        private const val DOWNSHIFT_COOLDOWN_MS = 30_000L

        /** Grace period after stream start before monitoring (normal conditions) */
        private const val GRACE_PERIOD_MS = 10_000L

        /** Maximum proactive downshifts per stream */
        const val MAX_PROACTIVE_DOWNSHIFTS = 3

        /** Minimum buffer change to consider as "declining" (avoid noise) */
        private const val MIN_DECLINE_THRESHOLD_MS = 200L

        /**
         * Predictive stall horizon: trigger downshift when projected to hit critical buffer
         * within this many milliseconds. This allows preemptive action before buffer gets low.
         */
        private const val PREDICTIVE_STALL_HORIZON_MS = 30_000L

        /**
         * Minimum consecutive declining samples required for predictive downshift.
         * Higher threshold than low-buffer to avoid false positives.
         */
        private const val PREDICTIVE_DECLINING_THRESHOLD = 10
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

    // Guardrail state (use -1L as sentinel to avoid edge cases when clock starts at 0)
    private var streamStartTimeMs = -1L
    private var lastDownshiftTimeMs = -1L
    private var proactiveDownshiftCount = 0

    // Track if we've used our early-stall exception (one allowed per stream)
    private var earlyStallExceptionUsed = false

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
        streamStartTimeMs = clock()
        proactiveDownshiftCount = 0
        lastDownshiftTimeMs = -1L // Reset per stream - new video shouldn't inherit previous cooldown
        earlyStallExceptionUsed = false

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
        val now = clock()
        val elapsed = now - streamStartTimeMs
        val isInGracePeriod = elapsed < GRACE_PERIOD_MS

        // Check buffer levels
        val isLowBuffer = currentBufferMs < LOW_BUFFER_THRESHOLD_MS
        val isCriticalBuffer = currentBufferMs < CRITICAL_BUFFER_THRESHOLD_MS

        // Check trend
        val isDeclining = isBufferDeclining()
        val depletionRateMs = calculateDepletionRate()

        if (isDeclining) {
            consecutiveDecliningCount++
        } else {
            consecutiveDecliningCount = 0
        }

        // Calculate projected time to critical (if depleting)
        // Clamp to 0 if buffer is already below critical (avoid negative values)
        // Multiply first, then divide to preserve precision in integer arithmetic
        val projectedTimeToCriticalMs = if (depletionRateMs > 0 && currentBufferMs > CRITICAL_BUFFER_THRESHOLD_MS) {
            ((currentBufferMs - CRITICAL_BUFFER_THRESHOLD_MS) * SAMPLE_INTERVAL_MS / depletionRateMs)
        } else if (depletionRateMs > 0) {
            0L // Already at or below critical
        } else {
            Long.MAX_VALUE
        }

        // EARLY-STALL EXCEPTION: During grace period, only act on critical buffer situations
        // where buffer is NOT building (declining or flat). This prevents the "plays for a few
        // seconds then stalls" problem while avoiding false positives on merely-slow-to-build buffers.
        if (isInGracePeriod) {
            // isNotBuilding: buffer is declining OR we have enough samples and it's not increasing
            val isNotBuilding = isDeclining || (bufferSamples.size >= 2 && !isBufferIncreasing())
            if (isCriticalBuffer && isNotBuilding && !earlyStallExceptionUsed && proactiveDownshiftCount < MAX_PROACTIVE_DOWNSHIFTS) {
                Log.w(TAG, "EARLY-STALL EXCEPTION: Critical buffer (${currentBufferMs}ms) and not building during grace period - emergency downshift")
                earlyStallExceptionUsed = true
                requestProactiveDownshift()
            } else if (isCriticalBuffer) {
                Log.v(TAG, "Buffer: ${currentBufferMs}ms CRITICAL but building (grace period: ${GRACE_PERIOD_MS - elapsed}ms remaining)")
            } else {
                Log.v(TAG, "Buffer: ${currentBufferMs}ms (grace period: ${GRACE_PERIOD_MS - elapsed}ms remaining)")
            }
            return
        }

        // Max downshifts check
        if (proactiveDownshiftCount >= MAX_PROACTIVE_DOWNSHIFTS) {
            Log.v(TAG, "Buffer: ${currentBufferMs}ms (max proactive downshifts reached)")
            return
        }

        // Cooldown check (skip if no prior downshift - lastDownshiftTimeMs == -1L sentinel)
        val timeSinceLastDownshift = now - lastDownshiftTimeMs
        if (lastDownshiftTimeMs != -1L && timeSinceLastDownshift < DOWNSHIFT_COOLDOWN_MS) {
            Log.v(TAG, "Buffer: ${currentBufferMs}ms (cooldown: ${DOWNSHIFT_COOLDOWN_MS - timeSinceLastDownshift}ms remaining)")
            return
        }

        Log.d(TAG, "Buffer: ${currentBufferMs}ms, low=$isLowBuffer, critical=$isCriticalBuffer, " +
                "declining=$isDeclining, consecutiveDeclines=$consecutiveDecliningCount, " +
                "projectedToCritical=${if (projectedTimeToCriticalMs < Long.MAX_VALUE) "${projectedTimeToCriticalMs}ms" else "never"}")

        // Decision logic (ordered by urgency):
        // 1. Critical buffer AND declining: immediate action
        // 2. Low buffer AND multiple consecutive declines: proactive action
        // 3. PREDICTIVE: Buffer depleting and projected to hit critical within horizon
        val shouldDownshift = when {
            isCriticalBuffer && isDeclining -> {
                Log.w(TAG, "CRITICAL: Buffer at ${currentBufferMs}ms and declining - requesting immediate downshift")
                true
            }
            isLowBuffer && consecutiveDecliningCount >= DECLINING_SAMPLES_THRESHOLD -> {
                Log.w(TAG, "PROACTIVE: Buffer low (${currentBufferMs}ms) with $consecutiveDecliningCount consecutive declines - requesting downshift")
                true
            }
            // PREDICTIVE: Even if buffer is not "low", if we're consistently depleting and
            // projected to hit critical within horizon, take action early
            consecutiveDecliningCount >= PREDICTIVE_DECLINING_THRESHOLD &&
                    projectedTimeToCriticalMs < PREDICTIVE_STALL_HORIZON_MS -> {
                Log.w(TAG, "PREDICTIVE: Buffer at ${currentBufferMs}ms, declining for $consecutiveDecliningCount samples, " +
                        "projected to hit critical in ${projectedTimeToCriticalMs}ms - requesting preemptive downshift")
                true
            }
            else -> false
        }

        if (shouldDownshift) {
            requestProactiveDownshift()
        }
    }

    /**
     * Calculate the buffer depletion rate in ms per sample interval using linear regression.
     * Positive value means buffer is shrinking (net depletion).
     * Returns 0 if not enough samples or buffer is not depleting overall.
     *
     * Uses simple linear regression: slope = (n*Σxy - Σx*Σy) / (n*Σx² - (Σx)²)
     * where x = sample index, y = buffer value
     * We negate the slope so positive = depletion
     */
    private fun calculateDepletionRate(): Long {
        if (bufferSamples.size < 3) return 0L

        val samples = bufferSamples.toList()
        val n = samples.size

        // Calculate sums for linear regression using Double to preserve precision
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0

        for (i in samples.indices) {
            val x = i.toDouble()
            val y = samples[i].toDouble()
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        // Calculate slope: (n*Σxy - Σx*Σy) / (n*Σx² - (Σx)²)
        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return 0L

        val slope = (n * sumXY - sumX * sumY) / denominator

        // Negate slope: positive slope means buffer increasing, we want positive = depletion
        val depletionRate = -slope

        // Only return positive depletion rates (buffer shrinking), rounded to Long
        return if (depletionRate > 0) depletionRate.toLong() else 0L
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

    /**
     * Check if buffer is actively increasing (building).
     * Used to avoid false positives on merely-slow-to-build buffers.
     */
    private fun isBufferIncreasing(): Boolean {
        if (bufferSamples.size < 2) return false

        val samples = bufferSamples.toList()
        val latest = samples.last()
        val previous = samples[samples.size - 2]

        // Buffer is increasing if it grew by more than threshold
        val increase = latest - previous
        return increase > MIN_DECLINE_THRESHOLD_MS
    }

    private fun requestProactiveDownshift() {
        Log.i(TAG, "Requesting proactive quality downshift (attempt ${proactiveDownshiftCount + 1}/$MAX_PROACTIVE_DOWNSHIFTS)")

        val success = callbacks.onProactiveDownshiftRequested()

        if (success) {
            proactiveDownshiftCount++
            lastDownshiftTimeMs = clock()
            consecutiveDecliningCount = 0
            bufferSamples.clear() // Reset samples after successful downshift
            Log.i(TAG, "Proactive downshift successful (count=$proactiveDownshiftCount)")
        } else {
            Log.w(TAG, "Proactive downshift failed or no lower quality available")
            // Don't count failed attempts against max, but do apply cooldown
            lastDownshiftTimeMs = clock()
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
