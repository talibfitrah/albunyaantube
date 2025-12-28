package com.albunyaan.tube.player

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PlaybackDegradationManager.
 *
 * Tests the Phase 4 per-video refresh budget system and degradation state machine.
 */
class PlaybackDegradationManagerTest {

    private lateinit var manager: PlaybackDegradationManager
    private var currentTimeMs = 0L

    @Before
    fun setUp() {
        manager = PlaybackDegradationManager()
        currentTimeMs = 0L
        manager.setTestClock { currentTimeMs }
    }

    // --- Basic State Tests ---

    @Test
    fun `new video starts in HEALTHY state`() {
        manager.initVideo("video1", initialQualityHeight = 1080)

        assertEquals(PlaybackDegradationManager.DegradationState.HEALTHY, manager.getState("video1"))
        assertEquals(PlaybackDegradationManager.MAX_REFRESH_BUDGET, manager.getRemainingBudget("video1"))
    }

    @Test
    fun `unknown video returns HEALTHY state`() {
        assertEquals(PlaybackDegradationManager.DegradationState.HEALTHY, manager.getState("unknown"))
        assertEquals(PlaybackDegradationManager.MAX_REFRESH_BUDGET, manager.getRemainingBudget("unknown"))
    }

    // --- Refresh Consumption Tests ---

    @Test
    fun `consuming refresh decrements budget`() {
        manager.initVideo("video1", initialQualityHeight = 720)

        val action = manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.HTTP_403)

        assertTrue(action is PlaybackDegradationManager.DegradationAction.None)
        assertEquals(PlaybackDegradationManager.MAX_REFRESH_BUDGET - 1, manager.getRemainingBudget("video1"))
    }

    @Test
    fun `state transitions to DEGRADED after threshold`() {
        manager.initVideo("video1", initialQualityHeight = 720)

        // Consume refreshes up to DEGRADED_THRESHOLD
        repeat(PlaybackDegradationManager.DEGRADED_THRESHOLD) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }

        assertEquals(PlaybackDegradationManager.DegradationState.DEGRADED, manager.getState("video1"))
    }

    @Test
    fun `state transitions to EXHAUSTED when budget depleted`() {
        manager.initVideo("video1", initialQualityHeight = 720)

        // Consume all budget minus one
        repeat(PlaybackDegradationManager.MAX_REFRESH_BUDGET - 1) {
            val action = manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.TTL_EXPIRED)
            assertTrue(action is PlaybackDegradationManager.DegradationAction.None)
        }

        // Last refresh should trigger EXHAUSTED and return a degradation action
        val action = manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.HTTP_403)
        assertTrue(action !is PlaybackDegradationManager.DegradationAction.None)
        assertEquals(PlaybackDegradationManager.DegradationState.EXHAUSTED, manager.getState("video1"))
    }

    // --- Degradation Action Tests ---

    @Test
    fun `quality step-down action returned first when exhausted`() {
        manager.initVideo("video1", initialQualityHeight = 1080)

        // Exhaust budget
        repeat(PlaybackDegradationManager.MAX_REFRESH_BUDGET) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }

        // Should get quality step-down action
        val state = manager.getState("video1")
        assertEquals(PlaybackDegradationManager.DegradationState.EXHAUSTED, state)

        // Verify action type
        manager.initVideo("video2", initialQualityHeight = 1080)
        repeat(PlaybackDegradationManager.MAX_REFRESH_BUDGET - 1) {
            manager.consumeRefresh("video2", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }
        val action = manager.consumeRefresh("video2", PlaybackDegradationManager.RefreshReason.RECOVERY)

        assertTrue(action is PlaybackDegradationManager.DegradationAction.QualityStepDown)
    }

    @Test
    fun `applying degradation resets budget partially`() {
        manager.initVideo("video1", initialQualityHeight = 720)

        // Exhaust budget
        repeat(PlaybackDegradationManager.MAX_REFRESH_BUDGET) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }

        val initialBudget = manager.getRemainingBudget("video1")
        assertEquals(0, initialBudget)

        // Apply degradation
        manager.onDegradationApplied(
            "video1",
            PlaybackDegradationManager.DegradationAction.QualityStepDown(480)
        )

        // Budget should be partially recovered
        assertTrue(manager.getRemainingBudget("video1") > initialBudget)
    }

    @Test
    fun `switch to muxed is tracked`() {
        manager.initVideo("video1", initialQualityHeight = 720)

        assertTrue(manager.hasDegradationOptions("video1"))

        // Exhaust quality step-downs first
        repeat(PlaybackDegradationManager.MAX_QUALITY_STEPDOWNS) {
            manager.onDegradationApplied(
                "video1",
                PlaybackDegradationManager.DegradationAction.QualityStepDown(480)
            )
        }

        // Apply muxed switch
        manager.onDegradationApplied(
            "video1",
            PlaybackDegradationManager.DegradationAction.SwitchToMuxed
        )

        // Still has HLS fallback option
        assertTrue(manager.hasDegradationOptions("video1"))

        // Apply HLS fallback
        manager.onDegradationApplied(
            "video1",
            PlaybackDegradationManager.DegradationAction.ForceHlsFallback
        )

        // No more options
        assertFalse(manager.hasDegradationOptions("video1"))
    }

    // --- Playback Success Tests ---

    @Test
    fun `playback success updates last success time`() {
        manager.initVideo("video1", initialQualityHeight = 720)

        currentTimeMs = 1000L
        manager.onPlaybackSuccess("video1")

        // No assertion on internal state, but should not crash
        assertEquals(PlaybackDegradationManager.DegradationState.HEALTHY, manager.getState("video1"))
    }

    @Test
    fun `playback success can recover from DEGRADED after sustained success`() {
        manager.initVideo("video1", initialQualityHeight = 720)

        // Enter DEGRADED state
        repeat(PlaybackDegradationManager.DEGRADED_THRESHOLD) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }
        assertEquals(PlaybackDegradationManager.DegradationState.DEGRADED, manager.getState("video1"))

        // Simulate time passing beyond recovery window
        currentTimeMs = PlaybackDegradationManager.BUDGET_RECOVERY_WINDOW_MS * 2

        // Playback success should trigger recovery check
        manager.onPlaybackSuccess("video1")

        // State should recover
        assertEquals(PlaybackDegradationManager.DegradationState.HEALTHY, manager.getState("video1"))
    }

    // --- Reset Tests ---

    @Test
    fun `reset clears video state`() {
        manager.initVideo("video1", initialQualityHeight = 1080)
        repeat(3) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }

        manager.resetVideo("video1")

        // After reset, returns default state
        assertEquals(PlaybackDegradationManager.DegradationState.HEALTHY, manager.getState("video1"))
        assertEquals(PlaybackDegradationManager.MAX_REFRESH_BUDGET, manager.getRemainingBudget("video1"))
    }

    @Test
    fun `clear removes all video states`() {
        manager.initVideo("video1", initialQualityHeight = 720)
        manager.initVideo("video2", initialQualityHeight = 1080)
        manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        manager.consumeRefresh("video2", PlaybackDegradationManager.RefreshReason.RECOVERY)

        manager.clear()

        assertEquals(PlaybackDegradationManager.MAX_REFRESH_BUDGET, manager.getRemainingBudget("video1"))
        assertEquals(PlaybackDegradationManager.MAX_REFRESH_BUDGET, manager.getRemainingBudget("video2"))
    }

    // --- Multiple Videos Tests ---

    @Test
    fun `different videos have independent budgets`() {
        manager.initVideo("video1", initialQualityHeight = 720)
        manager.initVideo("video2", initialQualityHeight = 1080)

        // Exhaust video1 budget
        repeat(PlaybackDegradationManager.MAX_REFRESH_BUDGET) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }

        // video2 should still be healthy
        assertEquals(PlaybackDegradationManager.DegradationState.EXHAUSTED, manager.getState("video1"))
        assertEquals(PlaybackDegradationManager.DegradationState.HEALTHY, manager.getState("video2"))
        assertEquals(PlaybackDegradationManager.MAX_REFRESH_BUDGET, manager.getRemainingBudget("video2"))
    }

    // --- Callback Tests ---

    @Test
    fun `callback receives state change notifications`() {
        var stateChanges = mutableListOf<Triple<String, PlaybackDegradationManager.DegradationState, PlaybackDegradationManager.DegradationState>>()

        manager.setCallback(object : PlaybackDegradationManager.DegradationCallback {
            override fun onStateChanged(
                videoId: String,
                oldState: PlaybackDegradationManager.DegradationState,
                newState: PlaybackDegradationManager.DegradationState
            ) {
                stateChanges.add(Triple(videoId, oldState, newState))
            }

            override fun onDegradationRequired(
                videoId: String,
                action: PlaybackDegradationManager.DegradationAction
            ) {}

            override fun onBudgetLow(videoId: String, remaining: Int) {}
        })

        manager.initVideo("video1", initialQualityHeight = 720)

        // Transition to DEGRADED
        repeat(PlaybackDegradationManager.DEGRADED_THRESHOLD) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }

        assertTrue(stateChanges.isNotEmpty())
        assertEquals("video1", stateChanges.last().first)
        assertEquals(PlaybackDegradationManager.DegradationState.DEGRADED, stateChanges.last().third)
    }

    @Test
    fun `callback receives low budget warning`() {
        var lowBudgetWarnings = mutableListOf<Pair<String, Int>>()

        manager.setCallback(object : PlaybackDegradationManager.DegradationCallback {
            override fun onStateChanged(
                videoId: String,
                oldState: PlaybackDegradationManager.DegradationState,
                newState: PlaybackDegradationManager.DegradationState
            ) {}

            override fun onDegradationRequired(
                videoId: String,
                action: PlaybackDegradationManager.DegradationAction
            ) {}

            override fun onBudgetLow(videoId: String, remaining: Int) {
                lowBudgetWarnings.add(videoId to remaining)
            }
        })

        manager.initVideo("video1", initialQualityHeight = 720)

        // Consume until low budget (remaining <= 2)
        repeat(PlaybackDegradationManager.MAX_REFRESH_BUDGET - 2) {
            manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        }

        assertTrue(lowBudgetWarnings.isNotEmpty())
    }

    // --- Refresh Reason Tests ---

    @Test
    fun `different refresh reasons consume budget equally`() {
        manager.initVideo("video1", initialQualityHeight = 720)
        val initialBudget = manager.getRemainingBudget("video1")

        manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.HTTP_403)
        assertEquals(initialBudget - 1, manager.getRemainingBudget("video1"))

        manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.TTL_EXPIRED)
        assertEquals(initialBudget - 2, manager.getRemainingBudget("video1"))

        manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.MANUAL)
        assertEquals(initialBudget - 3, manager.getRemainingBudget("video1"))

        manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.PREEMPTIVE)
        assertEquals(initialBudget - 4, manager.getRemainingBudget("video1"))

        manager.consumeRefresh("video1", PlaybackDegradationManager.RefreshReason.RECOVERY)
        assertEquals(initialBudget - 5, manager.getRemainingBudget("video1"))
    }
}
