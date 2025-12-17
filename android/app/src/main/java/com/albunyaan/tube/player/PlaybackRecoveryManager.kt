package com.albunyaan.tube.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

        // Stall thresholds
        private const val BUFFERING_STALL_THRESHOLD_MS = 15_000L
        private const val STUCK_READY_CHECK_INTERVAL_MS = 3_000L
        private const val STUCK_READY_THRESHOLD_MS = 6_000L // Position not advancing for 6s while READY
        private const val MIN_POSITION_ADVANCE_MS = 500L // Minimum expected progress in check interval

        // Recovery limits
        /** Maximum number of automatic recovery attempts before showing manual retry */
        const val MAX_RECOVERY_ATTEMPTS = 5
        private const val RECOVERY_BACKOFF_BASE_MS = 2_000L
    }

    // State tracking (use -1L as sentinel to avoid edge cases when clock starts at 0)
    private var bufferingStartTime: Long = -1L
    private var lastKnownPosition: Long = -1L
    private var positionStuckSince: Long = -1L
    private var currentStreamId: String? = null
    private var isAdaptive: Boolean = false

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
     */
    fun onNewStream(streamId: String, isAdaptiveStream: Boolean) {
        Log.d(TAG, "New stream: $streamId, isAdaptive=$isAdaptiveStream")
        cancelAllJobs()
        currentStreamId = streamId
        isAdaptive = isAdaptiveStream
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

    private fun startBufferingStallDetection(player: ExoPlayer) {
        if (bufferingStartTime == -1L) {
            bufferingStartTime = clock()
            Log.d(TAG, "Buffering started")
        }

        bufferingStallJob?.cancel()
        bufferingStallJob = scope.launch {
            delay(BUFFERING_STALL_THRESHOLD_MS)
            if (player.playbackState == Player.STATE_BUFFERING) {
                val duration = clock() - bufferingStartTime
                Log.w(TAG, "Buffering stall detected: ${duration}ms")
                initiateRecovery(player, "buffering stall (${duration}ms)")
            }
        }
    }

    private fun stopBufferingStallDetection() {
        if (bufferingStartTime != -1L) {
            val duration = clock() - bufferingStartTime
            Log.d(TAG, "Buffering ended after ${duration}ms")
        }
        bufferingStartTime = -1L
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

                val currentPosition = player.currentPosition
                val expectedMinAdvance = MIN_POSITION_ADVANCE_MS

                if (currentPosition <= lastKnownPosition + expectedMinAdvance) {
                    // Position not advancing
                    if (positionStuckSince == -1L) {
                        positionStuckSince = clock()
                        Log.d(TAG, "Position appears stuck at ${currentPosition}ms")
                    } else {
                        val stuckDuration = clock() - positionStuckSince
                        if (stuckDuration >= STUCK_READY_THRESHOLD_MS) {
                            Log.w(TAG, "Stuck in READY detected: position=${currentPosition}ms, stuck for ${stuckDuration}ms")
                            initiateRecovery(player, "stuck in READY (position not advancing)")
                            break
                        }
                    }
                } else {
                    // Position is advancing - reset stuck tracking
                    if (positionStuckSince != -1L) {
                        Log.d(TAG, "Position advancing again: $lastKnownPosition -> $currentPosition")
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
            Log.d(TAG, "Backoff not elapsed - scheduling recovery in ${delayMs}ms")

            // Schedule delayed recovery instead of just returning
            pendingRecoveryJob?.cancel()
            pendingRecoveryJob = scope.launch {
                delay(delayMs)
                // Re-check state after delay - player might have recovered on its own
                if (player.playbackState == Player.STATE_BUFFERING ||
                    (player.playbackState == Player.STATE_READY && !player.isPlaying && player.playWhenReady)) {
                    initiateRecovery(player, "$reason (after backoff)")
                } else {
                    Log.d(TAG, "Playback recovered during backoff - skipping scheduled recovery")
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
                    Log.d(TAG, "Adaptive stream - re-preparing (ABR handles quality)")
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    // For progressive, request quality step-down
                    val stepped = callbacks.onRequestQualityDownshift()
                    if (!stepped) {
                        // No lower quality available - escalate to next step
                        Log.d(TAG, "No lower quality available - escalating to URL refresh")
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
