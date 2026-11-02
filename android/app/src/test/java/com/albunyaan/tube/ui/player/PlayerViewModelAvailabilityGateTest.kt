package com.albunyaan.tube.ui.player

import com.albunyaan.tube.player.ContentUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T12 (post-NB1 chokepoint move): Tests for the player's content-unavailable
 * UI flow — the gate itself moved into [GlobalStreamResolver], so these tests
 * now drive it via [FakePlayerRepository] throwing [ContentUnavailableException]
 * (which is exactly what the chokepoint does in production).
 *
 * Verifies:
 * - Archived video (repository throws ContentUnavailable) → ContentUnavailable state
 * - Valid video → proceeds normally (existing behavior preserved)
 * - Transport-error fail-open is now covered by [GlobalStreamResolverTest] /
 *   [DefaultPlayerRepositoryTest]; here we just assert the VM forwards the
 *   Loading state and resolveStreams gets called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelAvailabilityGateTest {

    private val testDispatcher = StandardTestDispatcher()

    private val fakePlayerRepository = FakePlayerRepository()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = playerViewModel(repository = fakePlayerRepository)

    // ── Availability Gate Tests ───────────────────────────────────────────────

    @Test
    fun `loadVideo archived video emits ContentUnavailable when repository throws`() = runTest {
        // The chokepoint inside [GlobalStreamResolver] throws ContentUnavailableException
        // for archived videos; the production [DefaultPlayerRepository] just forwards.
        // Drive that here via the fake so the VM sees the same behaviour.
        fakePlayerRepository.archivedIds.add("vid_archived")

        val vm = createViewModel()
        vm.loadVideo(videoId = "vid_archived")
        advanceUntilIdle()

        assertEquals(
            "Archived video must emit ContentUnavailable",
            StreamState.ContentUnavailable,
            vm.state.value.streamState,
        )
        // resolveStreams MUST be called now (the gate is one level deeper) — the
        // VM's job is to translate the exception into the right UI state.
        assert(fakePlayerRepository.resolveCallCount > 0) {
            "resolveStreams must be called (gate now lives inside GlobalStreamResolver)"
        }
    }

    @Test
    fun `loadVideo valid video proceeds to stream resolution`() = runTest {
        // Provide a resolvable stream so the VM can reach Ready
        fakePlayerRepository.resolvedStreams = null // returns null → Error, but still called

        val vm = createViewModel()
        vm.loadVideo(videoId = "vid_valid")
        advanceUntilIdle()

        // State must NOT be ContentUnavailable — it should be Error (no streams returned by fake)
        assertNotEquals(
            "Valid video must not end in ContentUnavailable",
            StreamState.ContentUnavailable,
            vm.state.value.streamState,
        )
        // resolveStreams MUST have been called at least once
        assert(fakePlayerRepository.resolveCallCount > 0) {
            "resolveStreams must be called for a valid video"
        }
    }

    @Test
    fun `resolve that never returns ends in Error overlay, not a silent black Loading`() = runTest {
        // Cold-start regression: the per-attempt withTimeout threw TimeoutCancellationException,
        // which the catch rethrew as a plain cancellation — the resolve job died with no retry and
        // no Error state, leaving the player black forever.
        fakePlayerRepository.hang = true

        val vm = createViewModel()
        vm.loadVideo(videoId = "vid_slow")
        advanceUntilIdle()

        assertEquals(
            "Every attempt timing out must surface the retry/error overlay",
            StreamState.Error(com.albunyaan.tube.R.string.player_stream_error),
            vm.state.value.streamState,
        )
        assertEquals("Each timed-out attempt must be retried", 3, fakePlayerRepository.resolveCallCount)
    }
}
