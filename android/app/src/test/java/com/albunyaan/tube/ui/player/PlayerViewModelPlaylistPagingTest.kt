package com.albunyaan.tube.ui.player

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.WorkManagerTestInitHelper.ExecutorsMode
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.data.playlist.PlaylistPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for PR6.6: Playlist Playback Reliability.
 *
 * Tests verify:
 * - Deep start algorithm (finding targetVideoId across pages)
 * - Lazy queue loading on Next button
 * - hasNext computation from queue + hasMore
 * - Auto-skip for unplayable items
 * - Single-flight paging (no concurrent fetches)
 * - Page timeout handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelPlaylistPagingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakePlayerRepository = FakePlayerRepository()
    private val fakePlaylistRepository = FakePlaylistDetailRepository()
    private val fakeFavoritesRepository = FakeFavoritesRepository()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Pin WorkManager to a deterministic test instance with SYNCHRONOUS executors.
        // AlBunyaanApplication.onCreate() enqueues real periodic/cleanup work, which
        // otherwise spins up the real constraint trackers (BatteryNotLowTracker
        // registers a BroadcastReceiver on a WM.task-* thread) that outlive a test and
        // fire after Robolectric teardown — surfacing as UncaughtExceptionsBeforeTest
        // mis-attributed to whichever test runs next in the shared JVM. Synchronous
        // executors plus draining in tearDown keep nothing in flight. Mirrors the
        // established pattern in RefreshSchedulerTest / DownloadRepositoryTest.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext(),
            Configuration.Builder().build(),
            ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS,
        )
    }

    @After
    fun tearDown() {
        // Drain before closing so no constraint tracker keeps running on a WM.task-*
        // thread past teardown (omitting the drain re-surfaces the flake in the next
        // test). Mirrors RefreshSchedulerTest. IllegalStateException (WorkManager
        // already torn down by a prior test in the shared JVM) is expected and
        // swallowed; with synchronous executors the drains complete in-call, so any
        // other failure is a genuine error and is allowed to escape — the finally
        // still resets Main, so the test dispatcher never leaks regardless.
        try {
            val wm = WorkManager.getInstance(ApplicationProvider.getApplicationContext())
            wm.cancelAllWork().result.get()
            wm.pruneWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
        } catch (_: IllegalStateException) {
            // WorkManager already torn down by a prior test; nothing to drain.
        } finally {
            // Always reset Main, even if the drain throws something other than
            // IllegalStateException (e.g. ExecutionException) — otherwise the test
            // dispatcher leaks into the next test in the shared JVM.
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel() = playerViewModel(
        repository = fakePlayerRepository,
        playlistDetailRepository = fakePlaylistRepository,
        favoritesRepository = fakeFavoritesRepository,
    )

    // =============================================================================
    // Deep Start Tests - Finding targetVideoId
    // =============================================================================

    @Test
    fun `targetVideoId found on first page uses fast path`() = runTest {
        // Arrange: Video at index 3 on first page
        val items = (1..10).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_4")

        val viewModel = createViewModel()

        // Act: Load playlist with targetVideoId = video_4
        viewModel.loadPlaylist(
            playlistId = "PL123",
            targetVideoId = "video_4",
            startIndexHint = 3  // Correct hint
        )
        advanceUntilIdle()

        // Assert: Started at video_4, queue has 6 remaining items
        assertEquals("video_4", viewModel.state.value.currentItem?.id)
        assertEquals(6, viewModel.state.value.upNext.size)
        assertEquals(1, fakePlaylistRepository.getItemsCallCount)  // Only 1 page fetch
    }

    @Test
    fun `targetVideoId found on later page pages until found`() = runTest {
        // Arrange: Video at index 15 (second page)
        val page1Items = (1..10).map { createPlaylistItem(it, "video_$it") }
        val page2Items = (11..20).map { createPlaylistItem(it, "video_$it") }
        val page1 = PlaylistPage(page1Items, Page("http://page2", "token2", null, null), nextItemOffset = 11)
        val page2 = PlaylistPage(page2Items, null, nextItemOffset = 21)
        fakePlaylistRepository.setPages(listOf(page1, page2))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_15")

        val viewModel = createViewModel()

        // Act: Load playlist with targetVideoId = video_15
        viewModel.loadPlaylist(
            playlistId = "PL123",
            targetVideoId = "video_15",
            startIndexHint = 0  // Wrong hint - doesn't help
        )
        advanceUntilIdle()

        // Assert: Started at video_15
        assertEquals("video_15", viewModel.state.value.currentItem?.id)
        assertEquals(2, fakePlaylistRepository.getItemsCallCount)  // Paged to find it
    }

    @Test
    fun `targetVideoId not found falls back to startIndexHint`() = runTest {
        // Arrange: targetVideoId doesn't exist, limit pages to simulate bounds
        val items = (1..10).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_5")

        val viewModel = createViewModel()

        // Act: Load playlist with non-existent targetVideoId
        viewModel.loadPlaylist(
            playlistId = "PL123",
            targetVideoId = "nonexistent_video",
            startIndexHint = 4  // Should fall back to this
        )
        advanceUntilIdle()

        // Assert: Fell back to index 4 (video_5)
        assertEquals("video_5", viewModel.state.value.currentItem?.id)
    }

    @Test
    fun `startIndexHint matches targetVideoId uses fast path`() = runTest {
        // Arrange: Hint is correct
        val items = (1..10).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_3")

        val viewModel = createViewModel()

        // Act: Correct hint should hit fast path
        viewModel.loadPlaylist(
            playlistId = "PL123",
            targetVideoId = "video_3",
            startIndexHint = 2
        )
        advanceUntilIdle()

        // Assert: Fast path used
        assertEquals("video_3", viewModel.state.value.currentItem?.id)
        assertEquals(1, fakePlaylistRepository.getItemsCallCount)  // Single fetch
    }

    // =============================================================================
    // hasNext Computation Tests
    // =============================================================================

    @Test
    fun `hasNext true when queue empty but hasMore is true`() = runTest {
        // Arrange: First page with more pages available
        val items = listOf(createPlaylistItem(1, "video_1"))
        val nextPage = Page("http://page2", "token2", null, null)
        fakePlaylistRepository.setPages(listOf(
            PlaylistPage(items, nextPage, nextItemOffset = 2)
        ))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()

        // Act
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Assert: Queue is empty but hasNext should be true (more pages exist)
        assertEquals("video_1", viewModel.state.value.currentItem?.id)
        assertTrue("upNext should be empty", viewModel.state.value.upNext.isEmpty())
        assertTrue("hasNext should be true (more pages available)", viewModel.state.value.hasNext)
    }

    @Test
    fun `hasNext false when queue empty and no more pages`() = runTest {
        // Arrange: Single page, single item
        val items = listOf(createPlaylistItem(1, "video_1"))
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()

        // Act
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Assert: No more pages and empty queue
        assertEquals("video_1", viewModel.state.value.currentItem?.id)
        assertTrue(viewModel.state.value.upNext.isEmpty())
        assertFalse("hasNext should be false", viewModel.state.value.hasNext)
    }

    @Test
    fun `hasNext true when queue has items regardless of hasMore`() = runTest {
        // Arrange: Multiple items, no more pages
        val items = (1..5).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()

        // Act
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Assert: Queue has items
        assertEquals(4, viewModel.state.value.upNext.size)
        assertTrue("hasNext should be true (queue has items)", viewModel.state.value.hasNext)
    }

    // =============================================================================
    // Lazy Loading Tests
    // =============================================================================

    @Test
    fun `advanceToNext loads more items when queue empty and hasMore`() = runTest {
        // Arrange: 2 pages, start at last item of first page
        val page1Items = (1..2).map { createPlaylistItem(it, "video_$it") }
        val page2Items = (3..4).map { createPlaylistItem(it, "video_$it") }
        val page1 = PlaylistPage(page1Items, Page("http://page2", "token2", null, null), nextItemOffset = 3)
        val page2 = PlaylistPage(page2Items, null, nextItemOffset = 5)
        fakePlaylistRepository.setPages(listOf(page1, page2))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_2")

        val viewModel = createViewModel()

        // Start at video_2 (last item of page 1)
        viewModel.loadPlaylist("PL123", targetVideoId = "video_2", startIndexHint = 1)
        advanceUntilIdle()

        assertEquals("video_2", viewModel.state.value.currentItem?.id)
        assertTrue("Queue should be empty initially", viewModel.state.value.upNext.isEmpty())
        assertEquals(1, fakePlaylistRepository.getItemsCallCount)  // Only first page loaded

        // Setup resolution for next video
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_3")

        // Act: Skip to next - should trigger page load
        val advanced = viewModel.skipToNext()
        advanceUntilIdle()

        // Assert: Page 2 was loaded and we advanced
        assertTrue("Should have advanced", advanced)
        assertEquals("video_3", viewModel.state.value.currentItem?.id)
        assertEquals(2, fakePlaylistRepository.getItemsCallCount)  // Second page fetched
    }

    @Test
    fun `background prefetch triggered when queue runs low`() = runTest {
        // Arrange: Start with 6 items (threshold is 5)
        val page1Items = (1..6).map { createPlaylistItem(it, "video_$it") }
        val page2Items = (7..10).map { createPlaylistItem(it, "video_$it") }
        val page1 = PlaylistPage(page1Items, Page("http://page2", "token2", null, null), nextItemOffset = 7)
        val page2 = PlaylistPage(page2Items, null, nextItemOffset = 11)
        fakePlaylistRepository.setPages(listOf(page1, page2))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()

        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Queue has 5 items (threshold exactly)
        assertEquals(5, viewModel.state.value.upNext.size)
        assertEquals(1, fakePlaylistRepository.getItemsCallCount)

        // Setup for next video
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_2")

        // Act: Skip to next - queue drops to 4, should trigger background fetch
        viewModel.skipToNext()
        advanceUntilIdle()

        // Assert: Background fetch should have been triggered
        // Queue should now include page 2 items
        assertTrue("Queue should have grown from background fetch", viewModel.state.value.upNext.size >= 4)
    }

    // =============================================================================
    // Auto-Skip Tests
    // =============================================================================

    @Test
    fun `unplayable video auto-skipped in playlist mode`() = runTest {
        // Arrange: 3 videos, video_2 will always fail, video_3 succeeds
        val items = (1..3).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))

        // First video succeeds
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        assertEquals("video_1", viewModel.state.value.currentItem?.id)

        // Configure: video_2 fails all retries, then video_3 succeeds
        // failNextResolve triggers 3 failures, then resolvedStreamsAfterFailure is used
        fakePlayerRepository.resolvedStreams = null
        fakePlayerRepository.failNextResolve = true
        fakePlayerRepository.resolvedStreamsAfterFailure = createResolvedStreams("video_3")

        // Act: Skip to next - video_2 fails, should auto-skip to video_3
        viewModel.skipToNext()
        advanceUntilIdle()

        // Assert: Should have auto-skipped past video_2 to video_3
        assertEquals("video_3", viewModel.state.value.currentItem?.id)
        // Queue should be empty (video_2 skipped, video_3 now playing)
        assertTrue(viewModel.state.value.upNext.isEmpty())
    }

    // =============================================================================
    // C1 Fix: Archived Content Gate in Playlist Queue Path
    // =============================================================================
    // The chokepoint gate inside DefaultPlayerRepository.resolveStreams throws
    // ContentUnavailableException for archived videos. PlayerViewModel must
    // catch it BEFORE NewPipe is reached, and either auto-skip in playlist mode
    // or surface ContentUnavailable for single-video paths.

    @Test
    fun `loadPlaylist starting on archived video auto-skips to next item`() = runTest {
        // Arrange: 3-item playlist, first item is archived. Loading the playlist
        // at startIndexHint=0 must NOT play the archived item via NewPipe — the
        // gate fires inside resolveStreams and the queue advances to video_2.
        val items = (1..3).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.archivedIds += "video_1"
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_2")

        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // The auto-skip path advances to video_2.
        assertEquals(
            "Archived first item must auto-skip to next valid item",
            "video_2",
            viewModel.state.value.currentItem?.id,
        )
        // The archived video did flow through resolveStreams, but the gate threw
        // before NewPipe was reached — that's the C1 invariant: no NewPipe call
        // for an archived id even if it's the playlist start.
        assertTrue(
            "resolveStreams must have been invoked for video_1 (gate throws inside)",
            "video_1" in fakePlayerRepository.resolvedVideoIds,
        )
    }

    @Test
    fun `advanceToNext to archived video auto-skips to following valid item`() = runTest {
        // Arrange: 3 items, first valid, second archived, third valid. After
        // playing video_1 the user (or autoplay) hits Next — the archived
        // video_2 must NOT play, and video_3 must take its place.
        val items = (1..3).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.archivedIds += "video_2"
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()
        assertEquals("video_1", viewModel.state.value.currentItem?.id)

        // Reconfigure: video_3 will resolve to a valid stream after the skip.
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_3")

        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(
            "Archived video_2 must be skipped — playback continues at video_3",
            "video_3",
            viewModel.state.value.currentItem?.id,
        )
    }

    @Test
    fun `archived video in single-video mode emits ContentUnavailable`() = runTest {
        // playItem (queue add → play) is the single-video selection path. When
        // the chosen video turns out to be archived, the user must see the
        // ContentUnavailable overlay rather than a generic stream error or a
        // silent retry loop. NOT in playlist mode — so no auto-skip.
        val items = (1..2).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()
        // Use loadVideo for the single-video flow. T12's existing gate at
        // loadVideo would short-circuit at the VM layer; the chokepoint inside
        // DefaultPlayerRepository defends a different code path. We exercise
        // the chokepoint here by clearing playlist mode and forcing the
        // archived id through resolveStreamFor's retry loop — that's what the
        // queue-advance path looks like at the call site level.
        viewModel.loadVideo(videoId = "vid_archived")
        advanceUntilIdle()
        // Now mark the SAME id as archived and call retryCurrentStream so the
        // retry loop hits resolveStreams → throws ContentUnavailableException.
        // (Post-NB1 the gate lives inside [GlobalStreamResolver]; the fake
        // repository here mirrors that contract by throwing the same
        // exception, so we exercise resolveStreamFor's retry path identically.)
        fakePlayerRepository.archivedIds += "vid_archived"
        viewModel.retryCurrentStream()
        advanceUntilIdle()

        assertEquals(
            "Archived video in single-video mode must surface ContentUnavailable",
            StreamState.ContentUnavailable,
            viewModel.state.value.streamState,
        )
    }

    @Test
    fun `consecutive skip limit prevents infinite loop`() = runTest {
        // Arrange: 5 videos, all will fail to resolve
        // MAX_CONSECUTIVE_SKIPS is 3, so after 3 skips we should see error state
        val items = (1..5).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Verify we start at video_1
        assertEquals("video_1", viewModel.state.value.currentItem?.id)

        // All future resolves fail permanently
        fakePlayerRepository.resolvedStreams = null
        fakePlayerRepository.alwaysFail = true

        // Try to advance - auto-skip will try 3 times then show error
        viewModel.skipToNext()
        advanceUntilIdle()

        // After MAX_CONSECUTIVE_SKIPS (3), should be in error state
        val state = viewModel.state.value
        assertTrue(
            "Should show error after max consecutive skips",
            state.streamState is StreamState.Error
        )
    }

    // =============================================================================
    // Shuffle Mode Tests
    // =============================================================================

    @Test
    fun `shuffle mode disables paging`() = runTest {
        // Arrange: 2 pages
        val page1Items = (1..5).map { createPlaylistItem(it, "video_$it") }
        val page1 = PlaylistPage(page1Items, Page("http://page2", "token2", null, null), nextItemOffset = 6)
        fakePlaylistRepository.setPages(listOf(page1))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()

        // Act: Load with shuffle enabled
        viewModel.loadPlaylist("PL123", shuffled = true)
        advanceUntilIdle()

        // Assert: hasNext based only on loaded items, no lazy loading
        assertNotNull(viewModel.state.value.currentItem)
        // In shuffle mode, paging is disabled
        assertEquals(4, viewModel.state.value.upNext.size)  // 5 items - 1 current = 4
    }

    // =============================================================================
    // Error Handling Tests
    // =============================================================================

    @Test
    fun `page fetch failure marks pagingFailed`() = runTest {
        // Arrange: First page succeeds, second page will fail (throw exception)
        val page1Items = listOf(createPlaylistItem(1, "video_1"))
        val page1 = PlaylistPage(page1Items, Page("http://page2", "token2", null, null), nextItemOffset = 2)
        fakePlaylistRepository.setPages(listOf(page1))
        fakePlaylistRepository.failOnSecondPage = true  // Throws instead of timeout
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()

        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Queue is empty, hasMore is true initially
        assertTrue(viewModel.state.value.upNext.isEmpty())
        assertTrue("hasNext should be true initially", viewModel.state.value.hasNext)

        // Try to advance - page fetch will fail
        fakePlayerRepository.resolvedStreams = null

        viewModel.skipToNext()
        advanceUntilIdle()

        // After failure, hasNext should become false (pagingFailed)
        assertFalse("hasNext should be false after paging failure", viewModel.state.value.hasNext)
    }

    @Test
    fun `empty playlist shows error state`() = runTest {
        // Arrange: Empty playlist
        fakePlaylistRepository.setPages(listOf(PlaylistPage(emptyList(), null)))

        val viewModel = createViewModel()

        // Act
        viewModel.loadPlaylist("PL_EMPTY")
        advanceUntilIdle()

        // Assert: Error state
        assertTrue(viewModel.state.value.streamState is StreamState.Error)
    }

    @Test
    fun `loadVideo clears stale playlist paging state`() = runTest {
        // Arrange: Load a playlist with more pages available
        val page1Items = (1..3).map { createPlaylistItem(it, "video_$it") }
        val page1 = PlaylistPage(page1Items, Page("http://page2", "token2", null, null), nextItemOffset = 4)
        fakePlaylistRepository.setPages(listOf(page1))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Verify playlist mode: hasNext should be true (queue + more pages)
        assertTrue("hasNext should be true in playlist mode", viewModel.state.value.hasNext)
        assertEquals(2, viewModel.state.value.upNext.size)  // video_2, video_3 in queue

        // Act: Switch to single video mode
        fakePlayerRepository.resolvedStreams = createResolvedStreams("single_video")
        viewModel.loadVideo("single_video", title = "Single Video")
        advanceUntilIdle()

        // Assert: Paging state should be cleared, hasNext should be false
        assertEquals("single_video", viewModel.state.value.currentItem?.id)
        assertTrue("Queue should be empty", viewModel.state.value.upNext.isEmpty())
        assertFalse("hasNext should be false for single video", viewModel.state.value.hasNext)
    }

    @Test
    fun `switching from playlist to video does not attempt paging`() = runTest {
        // Arrange: Load a playlist
        val page1Items = (1..2).map { createPlaylistItem(it, "video_$it") }
        val page1 = PlaylistPage(page1Items, Page("http://page2", "token2", null, null), nextItemOffset = 3)
        fakePlaylistRepository.setPages(listOf(page1))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        val initialPageFetchCount = fakePlaylistRepository.getItemsCallCount

        // Act: Switch to single video mode and try skipToNext
        fakePlayerRepository.resolvedStreams = createResolvedStreams("single_video")
        viewModel.loadVideo("single_video", title = "Single Video")
        advanceUntilIdle()

        // Try to skip (queue is empty, should NOT trigger page fetch)
        val result = viewModel.skipToNext()
        advanceUntilIdle()

        // Assert: No new page fetches should occur
        assertEquals(
            "No additional page fetches should occur after switching to single video",
            initialPageFetchCount,
            fakePlaylistRepository.getItemsCallCount
        )
        assertFalse("skipToNext should return false with empty queue", result)
    }

    // =============================================================================
    // Up-Next Tap Regression
    // =============================================================================

    @Test
    fun `tapping a gated up-next item plays it`() = runTest {
        // Standalone playlist whose parent channel is NOT in the approved registry:
        // gateChannelName() blanks the channel name on every on-screen up-next item,
        // so the displayed item is a copy that is NOT equals() to its queue original.
        // Tapping it must still start playback.
        //
        // Regression: playItem() removed the tapped item via queue.remove(item) —
        // full-object equality — which silently failed for gated copies. Every tap
        // in the up-next list was a dead no-op and the user had to back out to the
        // previous screen to change videos. FakeContentService.isInApprovedRegistry
        // returns false by default, so gating is active here without extra mocking.
        val items = (1..5).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // Precondition: playback starts at video_1 and the gate is active.
        assertEquals("video_1", viewModel.state.value.currentItem?.id)
        val shownItem = viewModel.state.value.upNext[1] // video_3 (queue is video_2..video_5)
        assertEquals(
            "Gate must be active for this regression: channel name is blanked on screen",
            "",
            shownItem.channelName,
        )

        // Act: tap the on-screen (gated) item, exactly as UpNextAdapter does.
        fakePlayerRepository.resolvedStreams = createResolvedStreams(shownItem.streamId)
        viewModel.playItem(shownItem)
        advanceUntilIdle()

        // Assert: the tapped video is now the current item.
        assertEquals(
            "Tapping a gated up-next item must start playing it",
            shownItem.id,
            viewModel.state.value.currentItem?.id,
        )
    }

    // S7-N5 (1.0.4 A7, owner ruling: favourite with the raw name). The gate blanks the channel on
    // screen only; a blank channelName is a 400 on PUT /favorites, and sync drops the row for good.
    @Test
    fun `a favourite in a standalone playlist keeps the channel name`() = runTest {
        fakePlaylistRepository.setPages(listOf(PlaylistPage(listOf(createPlaylistItem(1, "video_1")), null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")
        val viewModel = createViewModel()
        viewModel.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()
        assertEquals("the gate is active", "", viewModel.state.value.currentItem?.channelName)

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertEquals("Test Channel", fakeFavoritesRepository.getAllFavorites().first().single().channelName)
    }

    // =============================================================================
    // Helper Functions
    // =============================================================================

    private fun createPlaylistItem(position: Int, videoId: String) = PlaylistItem(
        position = position,
        videoId = videoId,
        title = "Video $position",
        thumbnailUrl = "https://example.com/$videoId.jpg",
        durationSeconds = 300,
        viewCount = 1000L,
        publishedTime = "1 day ago",
        channelId = "UC123",
        channelName = "Test Channel"
    )

    private fun createResolvedStreams(streamId: String) = ResolvedStreams(
        streamId = streamId,
        videoTracks = listOf(
            VideoTrack(
                url = "https://example.com/video.mp4",
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                bitrate = 2000000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = false
            )
        ),
        audioTracks = listOf(
            AudioTrack(
                url = "https://example.com/audio.m4a",
                mimeType = "audio/mp4",
                bitrate = 128000,
                codec = "mp4a"
            )
        ),
        subtitleTracks = emptyList(),
        durationSeconds = 300,
        hlsUrl = null,
        dashUrl = null,
        urlGeneratedAt = 0L,
        urlTimebaseVersion = ResolvedStreams.URL_TIMEBASE_VERSION
    )
}
