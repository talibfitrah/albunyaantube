package com.albunyaan.tube.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4: Per-Video Refresh Budgets and Degradation State Machine
 *
 * Manages graceful degradation when URL refresh attempts are exhausted.
 * Each video has a finite "refresh budget" that gets consumed by:
 * - 403 errors requiring URL refresh
 * - Manual refresh requests
 * - Preemptive refresh operations
 *
 * State Machine:
 * ```
 * HEALTHY ──(refresh consumed)──> DEGRADED ──(budget exhausted)──> EXHAUSTED
 *    ↑                                |                                  |
 *    └────────(success)───────────────┘                                  |
 *    └────────────────────(new stream)───────────────────────────────────┘
 * ```
 *
 * When EXHAUSTED:
 * 1. Force quality step-down (if higher qualities exist)
 * 2. Force switch to muxed stream (if currently on video-only)
 * 3. Switch to alternate stream type (HLS ↔ DASH/progressive)
 * 4. Finally, show permanent error to user
 *
 * Thread-safety: All public methods are thread-safe via ConcurrentHashMap
 * and atomic operations on VideoState fields.
 */
@Singleton
class PlaybackDegradationManager @Inject constructor() {

    companion object {
        private const val TAG = "DegradationManager"

        /**
         * Maximum refresh attempts per video before entering EXHAUSTED state.
         * This is separate from rate limiter budgets - this is the absolute ceiling.
         */
        const val MAX_REFRESH_BUDGET = 5

        /**
         * Threshold for entering DEGRADED state (warns user, prepares fallback).
         */
        const val DEGRADED_THRESHOLD = 3

        /**
         * After this many quality step-downs, stop trying lower qualities.
         */
        const val MAX_QUALITY_STEPDOWNS = 3

        /**
         * Time window for budget recovery. If no refreshes needed for this long,
         * one budget slot is recovered.
         */
        const val BUDGET_RECOVERY_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

        /**
         * Maximum number of video states to keep in memory.
         * Prevents unbounded memory growth from playlist navigation.
         */
        private const val MAX_VIDEO_STATES = 20
    }

    /**
     * Degradation state for playback.
     */
    enum class DegradationState {
        /** Normal operation - no degradation needed */
        HEALTHY,
        /** Some refresh budget consumed - may need fallback soon */
        DEGRADED,
        /** Refresh budget exhausted - must take degradation action */
        EXHAUSTED
    }

    /**
     * Actions that can be taken when degradation is required.
     */
    sealed class DegradationAction {
        /** No action needed - continue normal playback */
        object None : DegradationAction()

        /** Step down to lower quality */
        data class QualityStepDown(val targetHeight: Int?) : DegradationAction()

        /** Switch from video-only to muxed stream */
        object SwitchToMuxed : DegradationAction()

        /**
         * Switch to alternate stream type.
         * If currently HLS → poison HLS to force DASH/progressive.
         * If currently DASH/synthetic/progressive → clear HLS poison to try HLS.
         */
        object ForceHlsFallback : DegradationAction()

        /** All degradation options exhausted - show error */
        object ShowError : DegradationAction()
    }

    /**
     * Reason for refresh attempt.
     */
    enum class RefreshReason {
        /** 403 error during playback */
        HTTP_403,
        /** URL TTL expired */
        TTL_EXPIRED,
        /** Manual user refresh */
        MANUAL,
        /** Preemptive refresh before expiry */
        PREEMPTIVE,
        /** Recovery from playback error */
        RECOVERY
    }

    /**
     * Per-video state tracking.
     */
    data class VideoState(
        val videoId: String,
        /** Current degradation state */
        @Volatile var state: DegradationState = DegradationState.HEALTHY,
        /** Number of refresh attempts consumed */
        @Volatile var refreshesConsumed: Int = 0,
        /** Timestamps of refresh attempts for budget recovery.
         *  Thread-safe: Uses Collections.synchronizedList for concurrent access. */
        val refreshTimestamps: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf()),
        /** Number of quality step-downs already applied */
        @Volatile var qualityStepDowns: Int = 0,
        /** Whether we've switched to muxed stream */
        @Volatile var switchedToMuxed: Boolean = false,
        /** Whether we've forced HLS fallback */
        @Volatile var forcedHlsFallback: Boolean = false,
        /** Current quality height (for step-down tracking) */
        @Volatile var currentQualityHeight: Int = 0,
        /** Last successful playback timestamp */
        @Volatile var lastSuccessMs: Long = 0L
    ) {
        /**
         * Remaining refresh budget.
         */
        val remainingBudget: Int
            get() = (MAX_REFRESH_BUDGET - refreshesConsumed).coerceAtLeast(0)

        /**
         * Whether any degradation actions remain.
         */
        val hasDegradationOptions: Boolean
            get() = qualityStepDowns < MAX_QUALITY_STEPDOWNS ||
                    !switchedToMuxed ||
                    !forcedHlsFallback
    }

    /**
     * Callback interface for degradation actions.
     */
    interface DegradationCallback {
        /** Called when state changes */
        fun onStateChanged(videoId: String, oldState: DegradationState, newState: DegradationState)

        /** Called when a degradation action should be taken */
        fun onDegradationRequired(videoId: String, action: DegradationAction)

        /** Called when remaining budget is low (for UI warning) */
        fun onBudgetLow(videoId: String, remaining: Int)
    }

    private val videoStates = ConcurrentHashMap<String, VideoState>()
    /** Insertion order for FIFO eviction (ConcurrentHashMap doesn't maintain order) */
    private val insertionOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Lock for atomic operations on both videoStates and insertionOrder */
    private val stateLock = Any()
    @Volatile
    private var callback: DegradationCallback? = null

    // Clock for testing - uses monotonic time (elapsedRealtime) to avoid NTP/user clock issues
    @Volatile
    private var clock: () -> Long = { SystemClock.elapsedRealtime() }

    @VisibleForTesting
    fun setTestClock(testClock: () -> Long) {
        clock = testClock
    }

    /**
     * Set the callback for degradation events.
     */
    fun setCallback(callback: DegradationCallback?) {
        this.callback = callback
    }

    /**
     * Initialize state for a new video.
     * Call when starting playback of a video.
     *
     * Bounded cleanup: Evicts oldest entries if at capacity.
     */
    fun initVideo(videoId: String, initialQualityHeight: Int = 0) {
        // Check for existing state first, outside stateLock to avoid nested locking
        val existing = videoStates[videoId]
        if (existing != null) {
            synchronized(existing) {
                existing.currentQualityHeight = initialQualityHeight
                existing.lastSuccessMs = clock()
            }
            Log.d(TAG, "Updated existing video $videoId at quality ${initialQualityHeight}p")
            return
        }

        synchronized(stateLock) {
            // Double-check after acquiring lock (another thread may have created it)
            videoStates[videoId]?.let { alreadyCreated ->
                synchronized(alreadyCreated) {
                    alreadyCreated.currentQualityHeight = initialQualityHeight
                    alreadyCreated.lastSuccessMs = clock()
                }
                Log.d(TAG, "Updated existing video $videoId at quality ${initialQualityHeight}p (after lock)")
                return
            }

            // Evict oldest entries if at capacity
            while (videoStates.size >= MAX_VIDEO_STATES) {
                val oldest = insertionOrder.poll()
                oldest?.let {
                    videoStates.remove(it)
                    Log.d(TAG, "Evicted oldest state: $it")
                } ?: break // Queue empty, exit loop
            }

            val state = VideoState(
                videoId = videoId,
                currentQualityHeight = initialQualityHeight,
                lastSuccessMs = clock()
            )
            videoStates[videoId] = state
            insertionOrder.offer(videoId)
            Log.d(TAG, "Initialized video $videoId at quality ${initialQualityHeight}p (total=${videoStates.size})")
        }
    }

    /**
     * Record a refresh attempt being consumed.
     *
     * @param videoId Video ID
     * @param reason Why refresh was needed
     * @return The action that should be taken (if any)
     */
    fun consumeRefresh(videoId: String, reason: RefreshReason): DegradationAction {
        val state = getOrCreateState(videoId)
        val now = clock()

        // Store callback data to invoke outside synchronized block to prevent deadlock
        var stateTransition: Pair<DegradationState, DegradationState>? = null
        var lowBudgetRemaining: Int? = null
        val action: DegradationAction

        synchronized(state) {
            // Try budget recovery first
            recoverBudgetIfEligible(state, now)

            // Record the refresh
            state.refreshesConsumed++
            state.refreshTimestamps.add(now)

            // Clean old timestamps
            state.refreshTimestamps.removeAll { now - it > BUDGET_RECOVERY_WINDOW_MS * 2 }

            Log.d(TAG, "Refresh consumed for $videoId (reason=$reason): " +
                    "${state.refreshesConsumed}/$MAX_REFRESH_BUDGET used, remaining=${state.remainingBudget}")

            // Check for state transitions
            val oldState = state.state
            val newState = when {
                state.refreshesConsumed >= MAX_REFRESH_BUDGET -> DegradationState.EXHAUSTED
                state.refreshesConsumed >= DEGRADED_THRESHOLD -> DegradationState.DEGRADED
                else -> DegradationState.HEALTHY
            }

            if (newState != oldState) {
                state.state = newState
                Log.i(TAG, "State transition for $videoId: $oldState -> $newState")
                stateTransition = oldState to newState
            }

            // Warn about low budget
            if (state.remainingBudget <= 2 && state.remainingBudget > 0) {
                lowBudgetRemaining = state.remainingBudget
            }

            // Determine action if exhausted
            action = if (state.state == DegradationState.EXHAUSTED) {
                determineDegradationAction(state)
            } else {
                DegradationAction.None
            }
        }

        // Invoke callbacks outside synchronized block to prevent deadlock
        stateTransition?.let { (oldState, newState) ->
            try {
                callback?.onStateChanged(videoId, oldState, newState)
            } catch (e: Exception) {
                Log.e(TAG, "Callback exception in onStateChanged", e)
            }
        }
        lowBudgetRemaining?.let { remaining ->
            try {
                callback?.onBudgetLow(videoId, remaining)
            } catch (e: Exception) {
                Log.e(TAG, "Callback exception in onBudgetLow", e)
            }
        }

        return action
    }

    /**
     * Record successful playback - may recover state.
     */
    fun onPlaybackSuccess(videoId: String) {
        // Get state atomically under stateLock to prevent TOCTOU race with resetVideo/clear
        val state = synchronized(stateLock) {
            videoStates[videoId] ?: return
        }

        // Store callback data to invoke outside synchronized block to prevent deadlock
        var stateTransition: Pair<DegradationState, DegradationState>? = null

        synchronized(state) {
            val now = clock()
            state.lastSuccessMs = now

            // If DEGRADED and playback is working, consider recovering to HEALTHY
            // after sustained success
            if (state.state == DegradationState.DEGRADED) {
                val timeSinceLastRefresh = state.refreshTimestamps.maxOrNull()?.let { now - it } ?: Long.MAX_VALUE
                if (timeSinceLastRefresh > BUDGET_RECOVERY_WINDOW_MS) {
                    recoverBudgetIfEligible(state, now)
                    if (state.refreshesConsumed < DEGRADED_THRESHOLD) {
                        val oldState = state.state
                        state.state = DegradationState.HEALTHY
                        Log.i(TAG, "Recovered to HEALTHY for $videoId after sustained success")
                        stateTransition = oldState to DegradationState.HEALTHY
                    }
                }
            }
        }

        // Invoke callback outside synchronized block to prevent deadlock
        stateTransition?.let { (oldState, newState) ->
            try {
                callback?.onStateChanged(videoId, oldState, newState)
            } catch (e: Exception) {
                Log.e(TAG, "Callback exception in onStateChanged", e)
            }
        }
    }

    /**
     * Record that a degradation action was applied.
     */
    fun onDegradationApplied(videoId: String, action: DegradationAction) {
        // Get state atomically under stateLock to prevent TOCTOU race with resetVideo/clear
        val state = synchronized(stateLock) {
            videoStates[videoId] ?: return
        }

        // Store callback data to invoke outside synchronized block to prevent deadlock
        var stateTransition: Pair<DegradationState, DegradationState>? = null

        synchronized(state) {
            when (action) {
                is DegradationAction.QualityStepDown -> {
                    state.qualityStepDowns++
                    if (action.targetHeight != null) {
                        state.currentQualityHeight = action.targetHeight
                    }
                    Log.d(TAG, "Quality step-down applied for $videoId: " +
                            "step ${state.qualityStepDowns}/$MAX_QUALITY_STEPDOWNS, " +
                            "now at ${state.currentQualityHeight}p")
                }
                is DegradationAction.SwitchToMuxed -> {
                    state.switchedToMuxed = true
                    Log.d(TAG, "Switched to muxed for $videoId")
                }
                is DegradationAction.ForceHlsFallback -> {
                    state.forcedHlsFallback = true
                    Log.d(TAG, "Forced HLS fallback for $videoId")
                }
                is DegradationAction.ShowError -> {
                    Log.e(TAG, "Permanent error for $videoId - all options exhausted")
                }
                is DegradationAction.None -> { /* No tracking needed */ }
            }

            // After applying degradation, reset budget partially to allow retries
            // at the degraded quality level. Only recover if we've consumed at least 2
            // to prevent infinite loops (consume 1 → degrade → recover 1 → repeat)
            if (action !is DegradationAction.ShowError && action !is DegradationAction.None) {
                val recovered = if (state.refreshesConsumed >= 2) {
                    state.refreshesConsumed / 2
                } else {
                    0 // Don't recover for single refresh to prevent infinite loops
                }
                state.refreshesConsumed = (state.refreshesConsumed - recovered).coerceAtLeast(0)
                val oldState = state.state
                state.state = if (state.refreshesConsumed >= DEGRADED_THRESHOLD) {
                    DegradationState.DEGRADED
                } else {
                    DegradationState.HEALTHY
                }
                val newState = state.state
                if (newState != oldState) {
                    stateTransition = oldState to newState
                }
                Log.d(TAG, "Partial budget recovery after degradation: " +
                        "${state.refreshesConsumed}/$MAX_REFRESH_BUDGET used")
            }
        }

        // Invoke callback outside synchronized block to prevent deadlock
        stateTransition?.let { (oldState, newState) ->
            try {
                callback?.onStateChanged(videoId, oldState, newState)
            } catch (e: Exception) {
                Log.e(TAG, "Callback exception in onStateChanged", e)
            }
        }
    }

    /**
     * Get current state for a video.
     */
    fun getState(videoId: String): DegradationState {
        return videoStates[videoId]?.state ?: DegradationState.HEALTHY
    }

    /**
     * Get remaining refresh budget for a video.
     */
    fun getRemainingBudget(videoId: String): Int {
        return videoStates[videoId]?.remainingBudget ?: MAX_REFRESH_BUDGET
    }

    /**
     * Check if more degradation options are available.
     */
    fun hasDegradationOptions(videoId: String): Boolean {
        return videoStates[videoId]?.hasDegradationOptions ?: true
    }

    /**
     * Reset state for a video (e.g., when switching videos).
     */
    fun resetVideo(videoId: String) {
        synchronized(stateLock) {
            videoStates.remove(videoId)
            insertionOrder.remove(videoId)
        }
        Log.d(TAG, "Reset state for $videoId")
    }

    /**
     * Clear all state.
     */
    fun clear() {
        synchronized(stateLock) {
            videoStates.clear()
            insertionOrder.clear()
        }
        Log.d(TAG, "Cleared all degradation state")
    }

    // --- Private implementation ---

    private fun getOrCreateState(videoId: String): VideoState {
        // Fast path: check if already exists without locking
        videoStates[videoId]?.let { return it }

        // Slow path: synchronized creation and eviction
        synchronized(stateLock) {
            // Double-check after acquiring lock
            videoStates[videoId]?.let { return it }

            // Evict oldest if at capacity
            while (videoStates.size >= MAX_VIDEO_STATES) {
                val oldest = insertionOrder.poll()
                oldest?.let { videoStates.remove(it) } ?: break
            }

            // Create new state and track insertion order
            val newState = VideoState(videoId = videoId)
            videoStates[videoId] = newState
            insertionOrder.offer(videoId)
            return newState
        }
    }

    private fun recoverBudgetIfEligible(state: VideoState, now: Long) {
        if (state.refreshesConsumed == 0) return

        // Find timestamps older than recovery window
        val oldTimestamps = state.refreshTimestamps.filter { now - it > BUDGET_RECOVERY_WINDOW_MS }
        if (oldTimestamps.isNotEmpty()) {
            val toRecover = oldTimestamps.size.coerceAtMost(state.refreshesConsumed)
            state.refreshesConsumed -= toRecover
            // Remove recovered timestamps one by one to handle duplicates correctly
            // (removeAll(toSet()) would remove all occurrences of duplicate timestamps)
            var removed = 0
            val iterator = state.refreshTimestamps.iterator()
            while (iterator.hasNext() && removed < toRecover) {
                val timestamp = iterator.next()
                if (now - timestamp > BUDGET_RECOVERY_WINDOW_MS) {
                    iterator.remove()
                    removed++
                }
            }
            Log.d(TAG, "Budget recovery for ${state.videoId}: recovered $toRecover slots, " +
                    "now ${state.refreshesConsumed}/$MAX_REFRESH_BUDGET used")
        }
    }

    private fun determineDegradationAction(state: VideoState): DegradationAction {
        // Priority order of degradation actions:
        // 1. Quality step-down (if not at minimum and haven't exceeded step-down limit)
        // 2. Switch to muxed (if on video-only)
        // 3. Force HLS fallback (if on DASH/progressive)
        // 4. Show error (last resort)

        // Note: currentQualityHeight == 0 means uninitialized; skip quality step-down in that case
        // as we can't determine the appropriate target height
        return when {
            state.qualityStepDowns < MAX_QUALITY_STEPDOWNS && state.currentQualityHeight > 360 -> {
                val targetHeight = when {
                    state.currentQualityHeight > 1440 -> 1440
                    state.currentQualityHeight > 1080 -> 1080
                    state.currentQualityHeight > 720 -> 720
                    state.currentQualityHeight > 480 -> 480
                    else -> 360
                }
                Log.d(TAG, "Degradation action: step down from ${state.currentQualityHeight}p to ${targetHeight}p")
                DegradationAction.QualityStepDown(targetHeight)
            }
            !state.switchedToMuxed -> {
                Log.d(TAG, "Degradation action: switch to muxed stream")
                DegradationAction.SwitchToMuxed
            }
            !state.forcedHlsFallback -> {
                Log.d(TAG, "Degradation action: force HLS fallback")
                DegradationAction.ForceHlsFallback
            }
            else -> {
                Log.e(TAG, "Degradation action: show error (no options remaining)")
                DegradationAction.ShowError
            }
        }
    }
}
