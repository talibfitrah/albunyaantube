package com.albunyaan.tube.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.albunyaan.tube.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages automatic playback recovery for freeze detection and stall handling.
 *
 * Detects two types of freezes:
 * 1. **Buffering stall**: Player stuck in STATE_BUFFERING for too long
 * 2. **Stuck in READY**: Player in STATE_READY with playWhenReady=true but position not advancing
 *
 * Recovery ladder (executed in order with backoff):
 * 1. Re-prepare (same source)
 * 2. Seek-to-current-position (force buffer reload)
 * 3. Quality downshift (adaptive: rely on ABR, progressive: step down)
 * 4. Refresh stream URLs and resume
 * 5. Rebuild player in-place (last resort)
 */
class PlaybackRecoveryManager(
    private val scope: CoroutineScope,
    private val callbacks: RecoveryCallbacks,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    companion object {
        private const val TAG = "PlaybackRecovery"

        // Stall thresholds for VOD
        private const val BUFFERING_STALL_THRESHOLD_MS = 15_000L
        // Live streams buffer more due to real-time data - use longer threshold
        private const val LIVE_BUFFERING_STALL_THRESHOLD_MS = 45_000L
        private const val STUCK_READY_CHECK_INTERVAL_MS = 3_000L
        private const val STUCK_READY_THRESHOLD_MS = 6_000L // Position not advancing for 6s while READY
        // Live streams can have position stalls during buffering
        private const val LIVE_STUCK_READY_THRESHOLD_MS = 15_000L
        private const val MIN_POSITION_ADVANCE_MS = 500L // Minimum expected progress in check interval

        // Recovery limits
        /** Maximum number of automatic recovery attempts before showing manual retry */
        const val MAX_RECOVERY_ATTEMPTS = 5
        private const val RECOVERY_BACKOFF_BASE_MS = 2_000L

        // Buffer health monitoring for live streams
        /** Minimum buffer growth per check interval to consider stream progressing */
        private const val MIN_HEALTHY_BUFFER_GROWTH_MS = 1000L
        /** Check buffer growth every 5 seconds during live stream buffering */
        private const val BUFFER_CHECK_INTERVAL_MS = 5000L
        /** Minimum buffer duration (ms ahead of playback) to consider stream healthy.
         *  Even if buffer is growing, if we have less than this buffered, we're not healthy yet. */
        private const val MIN_HEALTHY_BUFFER_DURATION_MS = 3000L
        /** Number of consecutive healthy checks required before declaring stream healthy.
         *  Prevents premature "healthy" declaration on marginal networks with brief spikes. */
        private const val SUSTAINED_HEALTHY_CHECKS_REQUIRED = 2
        /** Number of consecutive zero-growth checks before declaring stream dead.
         *  3 checks × 5s = 15s of no growth triggers early recovery (vs waiting full 45s). */
        private const val DEAD_STREAM_ZERO_GROWTH_CHECKS = 3
    }

    // State tracking (use -1L as sentinel to avoid edge cases when clock starts at 0)
    private var bufferingStartTime: Long = -1L
    private var lastKnownPosition: Long = -1L
    private var positionStuckSince: Long = -1L
    private var currentStreamId: String? = null
    private var isAdaptive: Boolean = false
    private var isLive: Boolean = false

    // Recovery tracking (use -1L as sentinel for lastRecoveryTime)
    private val recoveryAttempt = AtomicInteger(0)
    private val lastRecoveryTime = AtomicLong(-1L)
    private var isRecovering = false

    // Jobs
    private var bufferingStallJob: Job? = null
    private var stuckReadyJob: Job? = null

    /**
     * Callbacks for recovery actions that require external handling.
     */
    interface RecoveryCallbacks {
        /** Called when recovery starts - UI should show "Recovering..." state */
        fun onRecoveryStarted(step: RecoveryStep, attempt: Int)

        /** Called when recovery succeeds - UI should hide recovery state */
        fun onRecoverySucceeded()

        /** Called when all recovery attempts exhausted - UI should show error with manual retry */
        fun onRecoveryExhausted()

        /** Step 3: Request quality downshift for progressive streams */
        fun onRequestQualityDownshift(): Boolean

        /** Step 4: Request stream URL refresh and resume at given position */
        fun onRequestStreamRefresh(resumePositionMs: Long)

        /** Step 5: Request player rebuild in-place */
        fun onRequestPlayerRebuild(resumePositionMs: Long)
    }

    /**
     * Recovery steps in order of escalation.
     */
    enum class RecoveryStep {
        RE_PREPARE,
        SEEK_TO_CURRENT,
        QUALITY_DOWNSHIFT,
        REFRESH_URLS,
        REBUILD_PLAYER
    }

    /**
     * Call when a new stream starts playing. Resets all tracking state.
     * @param streamId Unique identifier for the stream
     * @param isAdaptiveStream Whether the stream uses adaptive bitrate (HLS/DASH)
     * @param isLiveStream Whether this is a live stream (uses longer stall thresholds)
     */
    fun onNewStream(streamId: String, isAdaptiveStream: Boolean, isLiveStream: Boolean = false) {
        if (BuildConfig.DEBUG) Log.d(TAG, "New stream: $streamId, isAdaptive=$isAdaptiveStream, isLive=$isLiveStream")
        cancelAllJobs()
        currentStreamId = streamId
        isAdaptive = isAdaptiveStream
        isLive = isLiveStream
        resetTrackingState()
        resetRecoveryStateInternal()
    }

    /**
     * Call when playback state changes.
     */
    fun onPlaybackStateChanged(player: ExoPlayer, playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> {
                cancelAllJobs()
                resetTrackingState()
            }
            Player.STATE_BUFFERING -> {
                stopStuckReadyDetection()
                startBufferingStallDetection(player)
            }
            Player.STATE_READY -> {
                stopBufferingStallDetection()
                if (player.playWhenReady) {
                    startStuckReadyDetection(player)
                }
                // If we were recovering and reached READY, recovery succeeded
                if (isRecovering) {
                    onRecoverySuccess()
                }
            }
            Player.STATE_ENDED -> {
                cancelAllJobs()
                resetTrackingState()
                resetRecoveryStateInternal()
            }
        }
    }

    /**
     * Call when playWhenReady changes.
     */
    fun onPlayWhenReadyChanged(player: ExoPlayer, playWhenReady: Boolean) {
        if (player.playbackState == Player.STATE_READY) {
            if (playWhenReady) {
                startStuckReadyDetection(player)
            } else {
                stopStuckReadyDetection()
            }
        }
    }

    /**
     * Call when isPlaying changes to true - indicates successful playback.
     */
    fun onPlaybackStarted() {
        if (isRecovering) {
            onRecoverySuccess()
        }
        // Reset position tracking to current
        positionStuckSince = -1L
    }

    /**
     * Manual retry requested by user.
     */
    fun requestManualRetry(player: ExoPlayer) {
        Log.i(TAG, "Manual retry requested")
        resetRecoveryState()
        executeRecoveryStep(player, RecoveryStep.RE_PREPARE)
    }

    /**
     * Force refresh - user explicitly requested stream refresh.
     */
    fun requestForceRefresh(player: ExoPlayer) {
        Log.i(TAG, "Force refresh requested")
        val position = player.currentPosition.coerceAtLeast(0L)
        callbacks.onRequestStreamRefresh(position)
    }

    /**
     * Cancel all monitoring and reset state.
     */
    fun release() {
        cancelAllJobs()
        resetTrackingState()
        resetRecoveryStateInternal()
    }

    /**
     * Alias for release() - cancel all operations.
     */
    fun cancel() {
        release()
    }

    /**
     * Public method to reset recovery state (e.g., for manual retry).
     */
    fun resetRecoveryState() {
        resetRecoveryStateInternal()
    }

    // --- Private implementation ---

    // Buffer tracking for smarter live stream stall detection
    // Uses buffer duration (bufferedPosition - currentPosition) for more stable metric
    private var lastBufferDuration: Long = -1L

    private fun startBufferingStallDetection(player: ExoPlayer) {
        if (bufferingStartTime == -1L) {
            bufferingStartTime = clock()
            // Use buffer duration ahead (more stable than absolute bufferedPosition)
            lastBufferDuration = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            if (BuildConfig.DEBUG) Log.d(TAG, "Buffering started (isLive=$isLive, initialBufferDuration=${lastBufferDuration}ms)")
        }

        // Use longer threshold for live streams - they buffer more due to real-time data
        val threshold = if (isLive) LIVE_BUFFERING_STALL_THRESHOLD_MS else BUFFERING_STALL_THRESHOLD_MS

        bufferingStallJob?.cancel()
        bufferingStallJob = scope.launch {
            // For live streams, use iterative monitoring loop to check buffer growth
            // This avoids job-inside-job complexity and is more robust
            if (isLive) {
                monitorLiveBuffering(player, threshold)
            } else {
                // VOD streams: simple one-shot check after threshold
                monitorVodBuffering(player, threshold)
            }
        }
    }

    /**
     * Monitor VOD stream buffering - simple one-shot check after threshold.
     */
    private suspend fun monitorVodBuffering(player: ExoPlayer, threshold: Long) {
        delay(threshold)

        // Re-check state after delay - player might have transitioned out of buffering
        if (player.playbackState != Player.STATE_BUFFERING) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Player no longer buffering after threshold wait - skipping recovery")
            return
        }

        // CRITICAL: If player.isPlaying is true, playback is active despite buffering state
        if (player.isPlaying) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Player is playing despite buffering state - skipping recovery")
            stopBufferingStallDetection()
            return
        }

        val duration = clock() - bufferingStartTime
        Log.w(TAG, "Buffering stall detected: ${duration}ms (threshold=${threshold}ms)")
        initiateRecovery(player, "buffering stall (${duration}ms)")
    }

    /**
     * Monitor live stream buffering with iterative buffer duration checks.
     * Uses buffer duration ahead (bufferedPosition - currentPosition) for more stable metric.
     * Uses a single loop instead of nested jobs for robustness.
     *
     * Requires BOTH:
     * 1. Sustained buffer growth (SUSTAINED_HEALTHY_CHECKS_REQUIRED consecutive healthy checks)
     * 2. Minimum buffer duration floor (MIN_HEALTHY_BUFFER_DURATION_MS)
     * This prevents false "healthy" declarations on marginal networks.
     *
     * Early exit for dead streams:
     * If buffer shows zero growth for DEAD_STREAM_ZERO_GROWTH_CHECKS consecutive intervals,
     * triggers recovery early (15s) instead of waiting the full threshold (45s).
     */
    private suspend fun monitorLiveBuffering(player: ExoPlayer, threshold: Long) {
        var stallCheckCount = 0
        var consecutiveHealthyChecks = 0
        var consecutiveZeroGrowthChecks = 0
        val maxStallChecks = (threshold / BUFFER_CHECK_INTERVAL_MS).toInt().coerceAtLeast(1)

        while (stallCheckCount < maxStallChecks) {
            delay(BUFFER_CHECK_INTERVAL_MS)
            stallCheckCount++

            // Exit conditions: no longer buffering or actively playing
            if (player.playbackState != Player.STATE_BUFFERING) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Player exited buffering state during monitoring")
                return
            }
            if (player.isPlaying) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Player is playing despite buffering state - stream is healthy")
                stopBufferingStallDetection()
                return
            }

            // Check buffer duration growth (more stable than absolute bufferedPosition for live)
            // Buffer duration = how much we have buffered ahead of current position
            val currentBufferDuration = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            val bufferGrowth = currentBufferDuration - lastBufferDuration

            // Check if this interval is healthy: buffer is growing AND we have enough buffered
            val isHealthyInterval = bufferGrowth > MIN_HEALTHY_BUFFER_GROWTH_MS &&
                                    currentBufferDuration >= MIN_HEALTHY_BUFFER_DURATION_MS

            // Track zero-growth for early dead stream detection
            val isZeroGrowth = bufferGrowth <= 0

            if (isHealthyInterval) {
                consecutiveHealthyChecks++
                consecutiveZeroGrowthChecks = 0
                if (BuildConfig.DEBUG) Log.d(TAG, "Live buffer healthy: duration=${currentBufferDuration}ms (+${bufferGrowth}ms), " +
                    "consecutiveHealthy=$consecutiveHealthyChecks/$SUSTAINED_HEALTHY_CHECKS_REQUIRED")

                // Only declare truly healthy after sustained growth AND sufficient buffer
                if (consecutiveHealthyChecks >= SUSTAINED_HEALTHY_CHECKS_REQUIRED) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Live stream sustained healthy - resetting stall tracking")
                    lastBufferDuration = currentBufferDuration
                    bufferingStartTime = clock()
                    stallCheckCount = 0
                    consecutiveHealthyChecks = 0
                }
            } else {
                // Not healthy - reset consecutive counter but don't reset stall tracking
                if (consecutiveHealthyChecks > 0) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Live buffer health interrupted: duration=${currentBufferDuration}ms (+${bufferGrowth}ms)")
                }
                consecutiveHealthyChecks = 0

                // Track zero-growth for early exit on dead streams
                if (isZeroGrowth) {
                    consecutiveZeroGrowthChecks++
                    if (BuildConfig.DEBUG) Log.d(TAG, "Live buffer zero growth: duration=${currentBufferDuration}ms, " +
                        "consecutiveZero=$consecutiveZeroGrowthChecks/$DEAD_STREAM_ZERO_GROWTH_CHECKS")

                    // Early exit: stream is likely dead if no growth for multiple intervals
                    if (consecutiveZeroGrowthChecks >= DEAD_STREAM_ZERO_GROWTH_CHECKS) {
                        Log.w(TAG, "Live stream dead (zero growth for ${consecutiveZeroGrowthChecks * BUFFER_CHECK_INTERVAL_MS}ms)")
                        initiateRecovery(player, "dead stream (no buffer growth)")
                        return
                    }
                } else {
                    // Some growth but not healthy - reset zero counter
                    consecutiveZeroGrowthChecks = 0
                    if (BuildConfig.DEBUG) Log.d(TAG, "Live buffer stagnant: duration=${currentBufferDuration}ms (+${bufferGrowth}ms) " +
                        "(stall $stallCheckCount/$maxStallChecks)")
                }
            }

            // Always update last buffer duration for next comparison
            lastBufferDuration = currentBufferDuration
        }

        // Exhausted all stall checks - buffer hasn't sustained healthy growth, trigger recovery
        val duration = clock() - bufferingStartTime
        val currentBufferDuration = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)

        Log.w(TAG, "Live stream buffering stall detected: ${duration}ms, bufferDuration=${currentBufferDuration}ms (threshold=${threshold}ms)")
        initiateRecovery(player, "buffering stall (buffer stagnant)")
    }

    private fun stopBufferingStallDetection() {
        if (bufferingStartTime != -1L) {
            val duration = clock() - bufferingStartTime
            if (BuildConfig.DEBUG) Log.d(TAG, "Buffering ended after ${duration}ms")
        }
        bufferingStartTime = -1L
        lastBufferDuration = -1L
        bufferingStallJob?.cancel()
        bufferingStallJob = null
    }

    private fun startStuckReadyDetection(player: ExoPlayer) {
        lastKnownPosition = player.currentPosition
        positionStuckSince = -1L

        stuckReadyJob?.cancel()
        stuckReadyJob = scope.launch {
            while (true) {
                delay(STUCK_READY_CHECK_INTERVAL_MS)

                if (player.playbackState != Player.STATE_READY || !player.playWhenReady) {
                    break
                }

                // CRITICAL: If player.isPlaying is true, playback is healthy - don't trigger recovery.
                // isPlaying being true means audio/video is actively rendering.
                // This prevents false positives for live streams where position might not advance
                // exactly as expected but playback is actually working fine.
                if (player.isPlaying) {
                    // Reset stuck tracking - playback is healthy
                    if (positionStuckSince != -1L) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Player is playing - resetting stuck detection")
                    }
                    positionStuckSince = -1L
                    lastKnownPosition = player.currentPosition
                    continue
                }

                val currentPosition = player.currentPosition
                val expectedMinAdvance = MIN_POSITION_ADVANCE_MS

                if (currentPosition <= lastKnownPosition + expectedMinAdvance) {
                    // Position not advancing AND player reports not playing
                    if (positionStuckSince == -1L) {
                        positionStuckSince = clock()
                        if (BuildConfig.DEBUG) Log.d(TAG, "Position appears stuck at ${currentPosition}ms (isLive=$isLive, isPlaying=false)")
                    } else {
                        val stuckDuration = clock() - positionStuckSince
                        // Use longer threshold for live streams
                        val threshold = if (isLive) LIVE_STUCK_READY_THRESHOLD_MS else STUCK_READY_THRESHOLD_MS
                        if (stuckDuration >= threshold) {
                            Log.w(TAG, "Stuck in READY detected: position=${currentPosition}ms, stuck for ${stuckDuration}ms (threshold=${threshold}ms, isLive=$isLive)")
                            initiateRecovery(player, "stuck in READY (position not advancing)")
                            break
                        }
                    }
                } else {
                    // Position is advancing - reset stuck tracking
                    if (positionStuckSince != -1L) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Position advancing again: $lastKnownPosition -> $currentPosition")
                    }
                    positionStuckSince = -1L
                    lastKnownPosition = currentPosition
                }
            }
        }
    }

    private fun stopStuckReadyDetection() {
        stuckReadyJob?.cancel()
        stuckReadyJob = null
        positionStuckSince = -1L
    }

    private var pendingRecoveryJob: Job? = null

    private fun initiateRecovery(player: ExoPlayer, reason: String) {
        // Check if we've already exhausted attempts (without incrementing)
        val currentAttempt = recoveryAttempt.get()
        if (currentAttempt >= MAX_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Recovery exhausted after $MAX_RECOVERY_ATTEMPTS attempts")
            isRecovering = false
            callbacks.onRecoveryExhausted()
            return
        }

        // Backoff check BEFORE incrementing attempt
        val now = clock()
        val lastRecovery = lastRecoveryTime.get()
        val nextAttempt = currentAttempt + 1
        val requiredBackoff = RECOVERY_BACKOFF_BASE_MS * nextAttempt

        // Skip backoff check if no prior recovery (lastRecoveryTime == -1L sentinel)
        val timeSinceLastRecovery = now - lastRecovery
        if (lastRecovery != -1L && timeSinceLastRecovery < requiredBackoff) {
            val delayMs = requiredBackoff - timeSinceLastRecovery
            if (BuildConfig.DEBUG) Log.d(TAG, "Backoff not elapsed - scheduling recovery in ${delayMs}ms")

            // Schedule delayed recovery instead of just returning
            pendingRecoveryJob?.cancel()
            pendingRecoveryJob = scope.launch {
                delay(delayMs)
                // Re-check state after delay - player might have recovered on its own
                if (player.playbackState == Player.STATE_BUFFERING ||
                    (player.playbackState == Player.STATE_READY && !player.isPlaying && player.playWhenReady)) {
                    initiateRecovery(player, "$reason (after backoff)")
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Playback recovered during backoff - skipping scheduled recovery")
                }
            }
            return
        }

        // Now increment the attempt counter
        val attempt = recoveryAttempt.incrementAndGet()
        lastRecoveryTime.set(now)

        Log.i(TAG, "Initiating recovery attempt $attempt: $reason")
        isRecovering = true

        // Determine recovery step based on attempt number and stream type
        val step = determineRecoveryStep(attempt)
        executeRecoveryStep(player, step)
    }

    private fun determineRecoveryStep(attempt: Int): RecoveryStep {
        return when (attempt) {
            1 -> RecoveryStep.RE_PREPARE
            2 -> RecoveryStep.SEEK_TO_CURRENT
            3 -> RecoveryStep.QUALITY_DOWNSHIFT
            4 -> RecoveryStep.REFRESH_URLS
            else -> RecoveryStep.REBUILD_PLAYER
        }
    }

    private fun executeRecoveryStep(player: ExoPlayer, step: RecoveryStep) {
        val attempt = recoveryAttempt.get()
        Log.i(TAG, "Executing recovery step: $step (attempt $attempt)")
        callbacks.onRecoveryStarted(step, attempt)

        val resumePosition = player.currentPosition.coerceAtLeast(0L)

        when (step) {
            RecoveryStep.RE_PREPARE -> {
                // Simply re-prepare the same source
                player.prepare()
                player.playWhenReady = true
            }
            RecoveryStep.SEEK_TO_CURRENT -> {
                // Seek to current position to force buffer reload
                player.seekTo(resumePosition)
                player.prepare()
                player.playWhenReady = true
            }
            RecoveryStep.QUALITY_DOWNSHIFT -> {
                if (isAdaptive) {
                    // For adaptive, ABR should handle this - just re-prepare
                    if (BuildConfig.DEBUG) Log.d(TAG, "Adaptive stream - re-preparing (ABR handles quality)")
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    // For progressive, request quality step-down
                    val stepped = callbacks.onRequestQualityDownshift()
                    if (!stepped) {
                        // No lower quality available - escalate to next step
                        if (BuildConfig.DEBUG) Log.d(TAG, "No lower quality available - escalating to URL refresh")
                        executeRecoveryStep(player, RecoveryStep.REFRESH_URLS)
                    }
                }
            }
            RecoveryStep.REFRESH_URLS -> {
                callbacks.onRequestStreamRefresh(resumePosition)
            }
            RecoveryStep.REBUILD_PLAYER -> {
                callbacks.onRequestPlayerRebuild(resumePosition)
            }
        }
    }

    private fun onRecoverySuccess() {
        Log.i(TAG, "Recovery succeeded at attempt ${recoveryAttempt.get()}")
        isRecovering = false
        callbacks.onRecoverySucceeded()
        // Don't reset attempt count immediately - use backoff for subsequent issues
    }

    private fun resetTrackingState() {
        bufferingStartTime = -1L
        lastKnownPosition = -1L
        positionStuckSince = -1L
    }

    private fun resetRecoveryStateInternal() {
        recoveryAttempt.set(0)
        lastRecoveryTime.set(-1L)
        isRecovering = false
    }

    private fun cancelAllJobs() {
        bufferingStallJob?.cancel()
        bufferingStallJob = null
        stuckReadyJob?.cancel()
        stuckReadyJob = null
        pendingRecoveryJob?.cancel()
        pendingRecoveryJob = null
    }
}
