package com.albunyaan.tube.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for PlaybackRecoveryManager.
 *
 * Tests use an injected clock to enable deterministic testing of timing logic
 * (backoff between recovery attempts) without real sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var mockCallbacks: PlaybackRecoveryManager.RecoveryCallbacks
    private lateinit var mockPlayer: ExoPlayer
    private lateinit var recoveryManager: PlaybackRecoveryManager

    // Injected clock for deterministic timing tests
    private var currentTime = 1000L

    /**
     * Helper to advance both coroutine virtual time and injected clock atomically.
     * Prevents synchronization issues between the two time sources.
     */
    private fun TestScope.advanceTimeAndClock(millis: Long) {
        advanceTimeBy(millis)
        currentTime += millis
    }

    @Before
    fun setUp() {
        currentTime = 1000L
        mockCallbacks = mock {
            on { onRequestQualityDownshift() } doReturn true
        }
        mockPlayer = mock {
            on { playbackState } doReturn Player.STATE_IDLE
            on { currentPosition } doReturn 0L
            on { playWhenReady } doReturn true
            on { isPlaying } doReturn false
        }
        recoveryManager = PlaybackRecoveryManager(
            scope = testScope,
            callbacks = mockCallbacks,
            clock = { currentTime }
        )
    }

    // --- New Stream Tests ---

    @Test
    fun `onNewStream resets state and does not trigger callbacks`() {
        // Arrange & Act: start a new stream
        recoveryManager.onNewStream("video1", false)

        // Assert: no callbacks should have been triggered yet
        verify(mockCallbacks, never()).onRecoveryStarted(any(), any())
        verify(mockCallbacks, never()).onRecoverySucceeded()
        verify(mockCallbacks, never()).onRecoveryExhausted()
    }

    @Test
    fun `switching streams resets recovery state`() {
        // Arrange: start stream 1
        recoveryManager.onNewStream("video1", false)

        // Act: switch to stream 2
        recoveryManager.onNewStream("video2", true)

        // Assert: no recovery callbacks
        verify(mockCallbacks, never()).onRecoveryStarted(any(), any())
    }

    // --- Force Refresh Tests ---

    @Test
    fun `requestForceRefresh invokes stream refresh callback with current position`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.currentPosition).thenReturn(12345L)

        // Act
        recoveryManager.requestForceRefresh(mockPlayer)

        // Assert
        verify(mockCallbacks).onRequestStreamRefresh(12345L)
    }

    @Test
    fun `requestForceRefresh clamps negative position to zero`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.currentPosition).thenReturn(-100L)

        // Act
        recoveryManager.requestForceRefresh(mockPlayer)

        // Assert: position should be clamped to 0
        verify(mockCallbacks).onRequestStreamRefresh(0L)
    }

    // --- Manual Retry Tests ---

    @Test
    fun `requestManualRetry invokes recovery callback`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act
        recoveryManager.requestManualRetry(mockPlayer)

        // Assert: recovery started with RE_PREPARE step
        // Note: attempt counter is 0 after reset, but step is still RE_PREPARE
        verify(mockCallbacks).onRecoveryStarted(
            eq(PlaybackRecoveryManager.RecoveryStep.RE_PREPARE),
            any()
        )
    }

    @Test
    fun `requestManualRetry prepares player`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act
        recoveryManager.requestManualRetry(mockPlayer)

        // Assert: player.prepare() was called
        verify(mockPlayer).prepare()
        verify(mockPlayer).playWhenReady = true
    }

    // --- Playback Started Detection ---

    @Test
    fun `onPlaybackStarted does not trigger callback when not recovering`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act
        recoveryManager.onPlaybackStarted()

        // Assert: no recovery succeeded callback (wasn't recovering)
        verify(mockCallbacks, never()).onRecoverySucceeded()
    }

    // --- State Change Tests ---

    @Test
    fun `onPlaybackStateChanged to IDLE cancels monitoring`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act: transition to IDLE
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_IDLE)
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_IDLE)

        // Assert: no crash, state is reset
        verify(mockCallbacks, never()).onRecoveryStarted(any(), any())
    }

    @Test
    fun `onPlaybackStateChanged to ENDED resets state`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act: transition to ENDED
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_ENDED)
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_ENDED)

        // Assert: no callbacks triggered for normal playback end
        verify(mockCallbacks, never()).onRecoveryExhausted()
    }

    // --- Release Tests ---

    @Test
    fun `release cancels all monitoring without crashing`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_BUFFERING)
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_BUFFERING)

        // Act
        recoveryManager.release()

        // Assert: no crash
        verify(mockCallbacks, never()).onRecoveryExhausted()
    }

    @Test
    fun `cancel is alias for release`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act
        recoveryManager.cancel()

        // Assert: no crash, method works
        verify(mockCallbacks, never()).onRecoveryExhausted()
    }

    // --- PlayWhenReady Tests ---

    @Test
    fun `onPlayWhenReadyChanged does not crash when player is not READY`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_IDLE)

        // Act & Assert: no crash
        recoveryManager.onPlayWhenReadyChanged(mockPlayer, true)
        recoveryManager.onPlayWhenReadyChanged(mockPlayer, false)
    }

    // --- Reset Recovery State Tests ---

    @Test
    fun `resetRecoveryState clears internal state`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act
        recoveryManager.resetRecoveryState()

        // Assert: subsequent manual retry uses RE_PREPARE step
        recoveryManager.requestManualRetry(mockPlayer)
        verify(mockCallbacks).onRecoveryStarted(
            eq(PlaybackRecoveryManager.RecoveryStep.RE_PREPARE),
            any()
        )
    }

    // --- Recovery Step Determination Tests (via manual retry) ---

    @Test
    fun `manual retry always starts fresh with RE_PREPARE`() {
        // Arrange
        recoveryManager.onNewStream("video1", false)

        // Act: call manual retry multiple times
        // Manual retry resets state and always does RE_PREPARE (attempt 0)
        recoveryManager.requestManualRetry(mockPlayer)
        recoveryManager.requestManualRetry(mockPlayer)
        recoveryManager.requestManualRetry(mockPlayer)

        // Assert: all retries should be RE_PREPARE with attempt 0
        // (manual retry resets state each time, unlike automatic recovery which progresses)
        verify(mockCallbacks, times(3)).onRecoveryStarted(
            eq(PlaybackRecoveryManager.RecoveryStep.RE_PREPARE),
            eq(0)
        )
    }

    // --- Constants Tests ---

    @Test
    fun `MAX_RECOVERY_ATTEMPTS constant is accessible and correct`() {
        assertEquals("MAX_RECOVERY_ATTEMPTS should be 5", 5, PlaybackRecoveryManager.MAX_RECOVERY_ATTEMPTS)
    }

    // --- Adaptive vs Progressive Tests ---

    @Test
    fun `adaptive stream initialization does not trigger callbacks`() {
        // Arrange & Act: set up adaptive stream
        recoveryManager.onNewStream("video1", true)

        // Verify: initialization alone should not trigger any recovery callbacks
        verify(mockCallbacks, never()).onRequestQualityDownshift()
    }

    @Test
    fun `progressive stream initialization does not trigger callbacks`() {
        // Arrange & Act: set up progressive stream
        recoveryManager.onNewStream("video1", false)

        // Verify: initialization alone should not trigger any recovery callbacks
        verify(mockCallbacks, never()).onRequestQualityDownshift()
    }

    // --- Timing Tests (using injected clock + coroutine advancement) ---

    @Test
    fun `buffering stall triggers recovery after 15 second threshold`() = testScope.runTest {
        // Arrange: start stream and enter buffering state
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_BUFFERING)
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_BUFFERING)

        // Act: advance time past the 15s buffering stall threshold
        advanceTimeAndClock(15_001L)

        // Assert: recovery should have been initiated
        verify(mockCallbacks).onRecoveryStarted(
            eq(PlaybackRecoveryManager.RecoveryStep.RE_PREPARE),
            eq(1)
        )
    }

    @Test
    fun `buffering stall does not trigger recovery before 15 second threshold`() = testScope.runTest {
        // Arrange: start stream and enter buffering state
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_BUFFERING)
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_BUFFERING)

        // Act: advance time just before the threshold
        advanceTimeAndClock(14_999L)

        // Assert: no recovery should be triggered yet
        verify(mockCallbacks, never()).onRecoveryStarted(any(), any())
    }

    @Test
    fun `consecutive stall recoveries progress through recovery steps`() = testScope.runTest {
        // Verify that consecutive buffering stalls trigger progressive recovery steps.
        // Note: With current constants (BUFFERING_STALL_THRESHOLD_MS=15s, RECOVERY_BACKOFF_BASE_MS=2s,
        // MAX_RECOVERY_ATTEMPTS=5), stall threshold (15s) exceeds required backoff up through attempt 5
        // (max 2s*5=10s), so stall-driven attempts won't be blocked by backoff.
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_BUFFERING)
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_BUFFERING)

        // First recovery triggers after 15s stall threshold
        advanceTimeAndClock(15_001L)

        verify(mockCallbacks).onRecoveryStarted(eq(PlaybackRecoveryManager.RecoveryStep.RE_PREPARE), eq(1))
        clearInvocations(mockCallbacks)

        // Simulate player still buffering - re-trigger detection
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_BUFFERING)

        // Second stall fires after another 15s
        advanceTimeAndClock(15_001L)

        // Second recovery should use SEEK_TO_CURRENT step
        verify(mockCallbacks).onRecoveryStarted(eq(PlaybackRecoveryManager.RecoveryStep.SEEK_TO_CURRENT), eq(2))
    }

    @Test
    fun `recovery exhausted after MAX_RECOVERY_ATTEMPTS`() = testScope.runTest {
        // Arrange
        recoveryManager.onNewStream("video1", false)
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_BUFFERING)

        // Trigger 5 recovery attempts (MAX_RECOVERY_ATTEMPTS = 5)
        // Each stall needs 15s to trigger; stall spacing (15s) > max backoff (10s), so no extra delay needed
        for (attempt in 1..5) {
            recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_BUFFERING)
            advanceTimeAndClock(15_001L)
        }

        // Try to trigger 6th attempt - should be rejected as exhausted
        recoveryManager.onPlaybackStateChanged(mockPlayer, Player.STATE_BUFFERING)
        advanceTimeAndClock(15_001L)

        // Assert: exhausted callback should be triggered
        verify(mockCallbacks).onRecoveryExhausted()
    }

    @Test
    fun `clock injection allows deterministic testing`() {
        // Verify the clock is properly injected
        recoveryManager.onNewStream("video1", false)

        // Advance clock significantly
        currentTime = 1000L + 60_000L

        // No crash, clock is used
        verify(mockCallbacks, never()).onRecoveryExhausted()
    }

    @Test
    fun `new stream resets clock-based tracking`() {
        // Set clock to specific time
        currentTime = 5000L
        recoveryManager.onNewStream("video1", false)

        // Do a manual retry
        recoveryManager.requestManualRetry(mockPlayer)

        // Switch to new stream
        currentTime = 6000L
        recoveryManager.onNewStream("video2", false)

        // Another manual retry should work (state is reset)
        recoveryManager.requestManualRetry(mockPlayer)

        // Should have 2 recovery started calls (one per stream)
        verify(mockCallbacks, times(2)).onRecoveryStarted(any(), any())
    }
}
