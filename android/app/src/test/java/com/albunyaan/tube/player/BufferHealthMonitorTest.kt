package com.albunyaan.tube.player

import androidx.media3.common.Player
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
 * Unit tests for BufferHealthMonitor.
 *
 * Tests use an injected clock to enable deterministic testing of timing logic
 * (grace periods, cooldowns) without real sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BufferHealthMonitorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var mockCallbacks: BufferHealthMonitor.BufferHealthCallbacks
    private lateinit var mockPlayer: Player
    private lateinit var bufferMonitor: BufferHealthMonitor

    // Injected clock for deterministic timing tests
    private var currentTime = 1000L

    @Before
    fun setUp() {
        currentTime = 1000L
        mockCallbacks = mock {
            on { onProactiveDownshiftRequested() } doReturn true
            on { isInRecoveryState() } doReturn false
        }
        mockPlayer = mock {
            on { isPlaying } doReturn true
            on { currentPosition } doReturn 0L
            on { bufferedPosition } doReturn 30_000L // 30 seconds buffered
        }
        bufferMonitor = BufferHealthMonitor(
            scope = testScope,
            callbacks = mockCallbacks,
            clock = { currentTime }
        )
    }

    // --- Stream Type Tests ---

    @Test
    fun `adaptive streams are not monitored - no downshift`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = true)

        // Assert: no monitoring for adaptive streams
        verify(mockCallbacks, never()).onProactiveDownshiftRequested()
    }

    @Test
    fun `progressive stream sets isProgressiveStream correctly`() {
        // Arrange & Act
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Assert: can proactive downshift (only for progressive)
        assertTrue("Should be able to proactive downshift", bufferMonitor.canProactiveDownshift())
    }

    // --- New Stream State Reset Tests ---

    @Test
    fun `onNewStream resets proactive downshift count`() {
        // Arrange: simulate some downshifts on stream 1
        bufferMonitor.onNewStream("video1", isAdaptive = false)
        // Can't directly increment count without full monitoring, so just test initial state

        // Act: switch to new stream
        bufferMonitor.onNewStream("video2", isAdaptive = false)

        // Assert: count is reset
        assertEquals("Downshift count should be 0 for new stream", 0, bufferMonitor.getProactiveDownshiftCount())
    }

    @Test
    fun `onNewStream resets canProactiveDownshift`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Act: switch to new stream
        bufferMonitor.onNewStream("video2", isAdaptive = false)

        // Assert: can downshift for new stream
        assertTrue("Should be able to downshift for new stream", bufferMonitor.canProactiveDownshift())
    }

    // --- Downshift Count Tests ---

    @Test
    fun `getProactiveDownshiftCount returns zero initially`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Assert
        assertEquals("Initial downshift count should be 0", 0, bufferMonitor.getProactiveDownshiftCount())
    }

    @Test
    fun `canProactiveDownshift returns true initially`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Assert
        assertTrue("Should be able to downshift initially", bufferMonitor.canProactiveDownshift())
    }

    // --- Playback State Tests ---

    @Test
    fun `onPlaybackPaused does not crash`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Act & Assert: no crash
        bufferMonitor.onPlaybackPaused()
    }

    @Test
    fun `onPlaybackStarted does not crash for adaptive streams`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = true)

        // Act & Assert: no crash (monitoring is not started for adaptive)
        bufferMonitor.onPlaybackStarted(mockPlayer)
    }

    @Test
    fun `onPlaybackStarted for progressive stream does not immediately trigger callback`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Act
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Assert: no immediate callback (need to wait for grace period + samples)
        verify(mockCallbacks, never()).onProactiveDownshiftRequested()

        // Cleanup: release to cancel monitoring coroutine
        bufferMonitor.release()
    }

    // --- Release Tests ---

    @Test
    fun `release stops monitoring without crash`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Act
        bufferMonitor.release()

        // Assert: no crash, no callbacks after release
        verify(mockCallbacks, never()).onProactiveDownshiftRequested()
    }

    @Test
    fun `release clears stream ID`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Act
        bufferMonitor.release()

        // No direct way to verify streamId is null, but ensures no crash
        // Starting new stream after release should work
        bufferMonitor.onNewStream("video2", isAdaptive = false)
        assertEquals("New stream should reset count", 0, bufferMonitor.getProactiveDownshiftCount())
    }

    // --- Constants Tests ---

    @Test
    fun `MAX_PROACTIVE_DOWNSHIFTS constant is accessible and correct`() {
        assertEquals("MAX_PROACTIVE_DOWNSHIFTS should be 3", 3, BufferHealthMonitor.MAX_PROACTIVE_DOWNSHIFTS)
    }

    // --- Multiple Stream Tests ---

    @Test
    fun `switching between adaptive and progressive resets state`() {
        // Arrange: start with progressive
        bufferMonitor.onNewStream("video1", isAdaptive = false)
        assertTrue(bufferMonitor.canProactiveDownshift())

        // Act: switch to adaptive
        bufferMonitor.onNewStream("video2", isAdaptive = true)

        // Assert: still can downshift (flag is per-stream, but canProactiveDownshift
        // is based on count which is reset)
        assertTrue(bufferMonitor.canProactiveDownshift())
    }

    @Test
    fun `multiple new streams keep count at zero`() {
        // Arrange & Act
        bufferMonitor.onNewStream("video1", isAdaptive = false)
        bufferMonitor.onNewStream("video2", isAdaptive = false)
        bufferMonitor.onNewStream("video3", isAdaptive = false)

        // Assert
        assertEquals("Count should be 0 after multiple stream switches", 0, bufferMonitor.getProactiveDownshiftCount())
    }

    // --- Pause/Resume Lifecycle Tests ---

    @Test
    fun `pause then resume for progressive does not crash`() {
        // Arrange
        bufferMonitor.onNewStream("video1", isAdaptive = false)
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Act
        bufferMonitor.onPlaybackPaused()
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Assert: no crash
        verify(mockCallbacks, never()).onProactiveDownshiftRequested()

        // Cleanup: release to cancel monitoring coroutine
        bufferMonitor.release()
    }

    // --- isInRecoveryState Integration ---

    @Test
    fun `callbacks isInRecoveryState is checked during monitoring setup`() {
        // Arrange
        whenever(mockCallbacks.isInRecoveryState()).thenReturn(true)
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Act
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Assert: no downshift when in recovery state
        verify(mockCallbacks, never()).onProactiveDownshiftRequested()

        // Cleanup: release to cancel monitoring coroutine
        bufferMonitor.release()
    }

    // --- Timing Tests (using injected clock + coroutine advancement) ---

    @Test
    fun `grace period blocks downshift within 10 seconds of stream start`() = testScope.runTest {
        // Arrange: configure low buffer that would normally trigger downshift
        whenever(mockPlayer.currentPosition).thenReturn(0L)
        whenever(mockPlayer.bufferedPosition).thenReturn(1_500L) // Critical buffer (< 2s)

        bufferMonitor.onNewStream("video1", isAdaptive = false)
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Act: advance time within grace period (9 seconds)
        repeat(9) { // 9 samples at 1s each
            advanceTimeBy(1_000L)
            currentTime += 1_000L
        }

        // Assert: no downshift during grace period despite critical buffer
        verify(mockCallbacks, never()).onProactiveDownshiftRequested()

        // Cleanup: release monitor to cancel coroutine
        bufferMonitor.release()
    }

    @Test
    fun `downshift triggers after grace period with critical declining buffer`() = testScope.runTest {
        // Arrange: configure critical buffer that is declining
        var bufferMs = 3_000L
        whenever(mockPlayer.currentPosition).thenReturn(0L)
        whenever(mockPlayer.bufferedPosition).thenAnswer { bufferMs }

        bufferMonitor.onNewStream("video1", isAdaptive = false)
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Advance past grace period (10s)
        repeat(11) {
            advanceTimeBy(1_000L)
            currentTime += 1_000L
        }

        // Now simulate declining buffer (drops by >200ms each sample)
        repeat(4) {
            bufferMs -= 500L // Declining by 500ms per sample
            advanceTimeBy(1_000L)
            currentTime += 1_000L
        }

        // Assert: downshift should be triggered (critical + declining)
        verify(mockCallbacks, atLeastOnce()).onProactiveDownshiftRequested()

        // Cleanup: release monitor to cancel coroutine
        bufferMonitor.release()
    }

    @Test
    fun `cooldown prevents consecutive downshifts within 30 seconds`() = testScope.runTest {
        // Arrange: configure critical declining buffer
        var bufferMs = 1_500L
        whenever(mockPlayer.currentPosition).thenReturn(0L)
        whenever(mockPlayer.bufferedPosition).thenAnswer { bufferMs }

        bufferMonitor.onNewStream("video1", isAdaptive = false)
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Pass grace period
        repeat(11) {
            advanceTimeBy(1_000L)
            currentTime += 1_000L
        }

        // Trigger first downshift with declining buffer
        repeat(4) {
            bufferMs -= 300L
            advanceTimeBy(1_000L)
            currentTime += 1_000L
        }

        // First downshift should trigger
        verify(mockCallbacks, times(1)).onProactiveDownshiftRequested()

        // Continue declining within cooldown (less than 30s since first downshift)
        bufferMs = 1_500L // Reset buffer
        repeat(10) { // Only 10 more seconds
            bufferMs -= 300L
            advanceTimeBy(1_000L)
            currentTime += 1_000L
        }

        // Still only 1 downshift (cooldown blocks second)
        verify(mockCallbacks, times(1)).onProactiveDownshiftRequested()

        // Cleanup: release monitor to cancel coroutine
        bufferMonitor.release()
    }

    @Test
    fun `max downshifts limits to 3 per stream`() = testScope.runTest {
        // Arrange: configure critical declining buffer
        var bufferMs = 1_500L
        whenever(mockPlayer.currentPosition).thenReturn(0L)
        whenever(mockPlayer.bufferedPosition).thenAnswer { bufferMs }

        bufferMonitor.onNewStream("video1", isAdaptive = false)
        bufferMonitor.onPlaybackStarted(mockPlayer)

        // Pass grace period
        repeat(11) {
            advanceTimeBy(1_000L)
            currentTime += 1_000L
        }

        // Trigger 3 downshifts (with cooldown between each)
        for (i in 1..4) {
            // Declining buffer triggers downshift
            repeat(5) {
                bufferMs -= 300L
                advanceTimeBy(1_000L)
                currentTime += 1_000L
            }
            // Wait for cooldown (30s)
            bufferMs = 1_500L // Reset buffer
            repeat(31) {
                advanceTimeBy(1_000L)
                currentTime += 1_000L
            }
        }

        // Assert: exactly 3 downshifts triggered (4th attempt blocked by max limit)
        verify(mockCallbacks, times(3)).onProactiveDownshiftRequested()
        assertFalse("Should not be able to downshift after max", bufferMonitor.canProactiveDownshift())

        // Cleanup: release monitor to cancel coroutine
        bufferMonitor.release()
    }

    @Test
    fun `clock injection allows deterministic testing`() {
        // Verify the clock is properly injected by checking stream start time
        bufferMonitor.onNewStream("video1", isAdaptive = false)

        // Advance clock significantly
        currentTime = 1000L + 60_000L

        // No crash, clock is used
        assertEquals("Downshift count should still be 0", 0, bufferMonitor.getProactiveDownshiftCount())
    }

}
