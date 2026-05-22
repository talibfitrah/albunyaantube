package com.albunyaan.tube.ui.detail

import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.data.channel.ChannelLiveStream
import com.albunyaan.tube.data.channel.ChannelPage
import com.albunyaan.tube.data.channel.ChannelPlaylist
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.data.channel.ChannelVideo
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.local.ChannelOldest
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.source.AvailabilityCheckType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService
import com.albunyaan.tube.player.StreamRequestTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ChannelDetailViewModel.
 *
 * Tests verify:
 * - Header loading (success, error states)
 * - Tab-specific paginated states (videos, live, shorts, playlists, posts)
 * - Pagination with rate limiting
 * - Error handling for initial load and append
 * - Empty state handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ChannelDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeChannelDetailRepository
    private lateinit var fakeCacheDao: FakeChannelVideoCacheDao
    private lateinit var fakeTelemetry: StreamRequestTelemetry
    private lateinit var fakeContentService: FakeContentService

    /**
     * Fake clock for deterministic rate limiting tests.
     * Initialized to a value > MIN_APPEND_INTERVAL_MS (1000ms) so that loadNextPage()
     * is not rate-limited on first call after initial load. This prevents subtle test
     * failures if a test author forgets to call advanceTimeBy() before pagination.
     */
    private var fakeCurrentTimeMs = 2000L

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeChannelDetailRepository()
        fakeCacheDao = FakeChannelVideoCacheDao()
        fakeTelemetry = StreamRequestTelemetry()
        fakeContentService = FakeContentService()
        fakeCurrentTimeMs = 2000L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Create a ViewModel with an injectable time provider for deterministic rate limiting tests.
     * Pass a custom [contentService] to override the default [FakeContentService] (which always
     * returns available=true). Use a Mockito mock when you need to simulate archived content.
     */
    private fun createViewModel(
        channelId: String = "UCtest123",
        contentService: ContentService = fakeContentService,
    ): ChannelDetailViewModel {
        return ChannelDetailViewModel(fakeRepository, fakeTelemetry, fakeCacheDao, contentService, channelId).apply {
            timeProvider = { fakeCurrentTimeMs }
        }
    }

    /**
     * Advance the fake clock by the specified milliseconds.
     */
    private fun advanceTimeBy(ms: Long) {
        fakeCurrentTimeMs += ms
    }

    // Header Tests

    @Test
    fun `header loading emits Loading then Success`() = runTest {
        val header = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.headerResponse = header

        val viewModel = createViewModel("UCtest123")

        // Initial state should be Loading (constructor calls loadHeader)
        advanceUntilIdle()

        val state = viewModel.headerState.value
        assertTrue("Expected Success state", state is ChannelDetailViewModel.HeaderState.Success)
        assertEquals("Test Channel", (state as ChannelDetailViewModel.HeaderState.Success).header.title)
    }

    @Test
    fun `header loading emits Error when repository throws`() = runTest {
        fakeRepository.headerError = RuntimeException("Network error")

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.headerState.value
        assertTrue("Expected Error state", state is ChannelDetailViewModel.HeaderState.Error)
        assertTrue((state as ChannelDetailViewModel.HeaderState.Error).message.contains("Network error"))
    }

    @Test
    fun `header retry reloads header data`() = runTest {
        // Initially fails
        fakeRepository.headerError = RuntimeException("Error")

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        assertTrue(viewModel.headerState.value is ChannelDetailViewModel.HeaderState.Error)

        // Fix error and retry
        fakeRepository.headerError = null
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")

        viewModel.loadHeader(forceRefresh = true)
        advanceUntilIdle()

        assertTrue(viewModel.headerState.value is ChannelDetailViewModel.HeaderState.Success)
    }

    // Videos Tab Tests

    @Test
    fun `videos initial load emits LoadingInitial then Loaded`() = runTest {
        val videos = listOf(createTestVideo("v1", "Video 1"), createTestVideo("v2", "Video 2"))
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(videos, null)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected Loaded state", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
    }

    @Test
    fun `videos initial reads local cache and lets fresh NewPipe data win`() = runTest {
        // Step 6: cache-first paint. The cache row is emitted before NewPipe
        // responds so users see something immediately; the fresh fetch then
        // replaces the cached state so this assertion checks the FINAL state.
        val cachedRow = ChannelVideoCache(
            videoId = "cached-v1",
            channelId = "UCtest123",
            channelName = "Test Channel",
            title = "Stale title",
            thumbnailUrl = null,
            durationSeconds = null,
            viewCount = null,
            uploadedAt = null,
            isShort = false,
            fetchedAt = 0L,
        )
        fakeCacheDao.rowsForChannel = mapOf("UCtest123" to listOf(cachedRow))
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(
            listOf(createTestVideo("v1", "Fresh title")),
            null,
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // Cache was queried at least once for this channel
        assertTrue(
            "Expected getForChannel to be called",
            fakeCacheDao.getForChannelCallCount >= 1,
        )

        // Final state is the fresh NewPipe item, not the cached row
        val state = viewModel.videosState.value
        assertTrue("Expected Loaded state", state is ChannelDetailViewModel.PaginatedState.Loaded)
        val loaded = state as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(1, loaded.items.size)
        assertEquals("v1", loaded.items[0].id)
        assertEquals("Fresh title", loaded.items[0].title)
    }

    @Test
    fun `videos initial keeps cached items as ErrorAppend when NewPipe fails`() = runTest {
        // Step 6 + review fix: when the Step-6 cache has already painted
        // content and the NewPipe fetch then throws, the catch must NOT
        // overwrite the visible list with ErrorInitial (full-screen error).
        // It must degrade to ErrorAppend so the cached list stays on screen
        // with an inline error footer.
        val cachedRow = ChannelVideoCache(
            videoId = "cached-v1",
            channelId = "UCtest123",
            channelName = "Test Channel",
            title = "Cached Video",
            thumbnailUrl = null,
            durationSeconds = null,
            viewCount = null,
            uploadedAt = null,
            isShort = false,
            fetchedAt = 0L,
        )
        fakeCacheDao.rowsForChannel = mapOf("UCtest123" to listOf(cachedRow))
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosError = RuntimeException("Network down")

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue(
            "Expected ErrorAppend preserving cached items, got: $state",
            state is ChannelDetailViewModel.PaginatedState.ErrorAppend<*>,
        )
        val errorAppend = state as ChannelDetailViewModel.PaginatedState.ErrorAppend<*>
        assertEquals(1, errorAppend.items.size)
        assertEquals("cached-v1", (errorAppend.items[0] as ChannelVideo).id)
    }

    @Test
    fun `videos initial uses ErrorInitial when no cache and NewPipe fails`() = runTest {
        // The non-cache path must still go to ErrorInitial — we did not
        // change the no-cache UX, only the cache-warm-then-fail UX.
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosError = RuntimeException("Network down")

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue(
            "Expected ErrorInitial when no cache, got: $state",
            state is ChannelDetailViewModel.PaginatedState.ErrorInitial,
        )
    }

    @Test
    fun `videos initial filters cached shorts so only long-form rows paint`() = runTest {
        // Step 6 sanity: the cache table holds both videos and shorts (Me-tab
        // also writes to it). The Videos tab must drop isShort=true rows so
        // shorts never bleed into the long-form list during the cached paint.
        val shortRow = ChannelVideoCache(
            videoId = "short1",
            channelId = "UCtest123",
            channelName = "Test Channel",
            title = "A short",
            thumbnailUrl = null,
            durationSeconds = 30L,
            viewCount = null,
            uploadedAt = null,
            isShort = true,
            fetchedAt = 0L,
        )
        fakeCacheDao.rowsForChannel = mapOf("UCtest123" to listOf(shortRow))
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        // NewPipe never responds in this test (videosResponse defaults to empty
        // page), so the cached paint is what we observe — the shorts row must
        // be filtered out before the empty NewPipe response replaces state.
        fakeRepository.videosResponse = ChannelPage(emptyList(), null)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // With cache filtered to non-shorts and NewPipe empty, the final state
        // must be Empty (no cached row leaked through).
        val state = viewModel.videosState.value
        assertTrue(
            "Expected Empty state when cache only has shorts and NewPipe is empty",
            state is ChannelDetailViewModel.PaginatedState.Empty,
        )
    }

    @Test
    fun `videos emits Empty when no videos available`() = runTest {
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(emptyList(), null)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected Empty state", state is ChannelDetailViewModel.PaginatedState.Empty)
    }

    @Test
    fun `videos pagination appends items`() = runTest {
        val page1 = listOf(createTestVideo("v1", "Video 1"))
        val page2 = listOf(createTestVideo("v2", "Video 2"))
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // First page loaded
        var state = viewModel.videosState.value as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(1, state.items.size)

        // Advance fake clock past rate limit
        advanceTimeBy(1100L)

        // Load next page
        fakeRepository.videosResponse = ChannelPage(page2, null)
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        // Both pages loaded
        state = viewModel.videosState.value as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(2, state.items.size)
        assertEquals("Video 1", state.items[0].title)
        assertEquals("Video 2", state.items[1].title)
    }

    @Test
    fun `videos pagination stops when no next page`() = runTest {
        val videos = listOf(createTestVideo("v1", "Video 1"))
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(videos, null) // No next page

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // Try to load next page (should be no-op)
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        val state = viewModel.videosState.value as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(1, state.items.size)
        assertNull(state.nextPage)
    }

    @Test
    fun `videos ErrorInitial when first load fails`() = runTest {
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosError = RuntimeException("Failed to load videos")

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected ErrorInitial state", state is ChannelDetailViewModel.PaginatedState.ErrorInitial)
    }

    @Test
    fun `videos ErrorAppend preserves existing items on pagination failure`() = runTest {
        val page1 = listOf(createTestVideo("v1", "Video 1"))
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // First page loaded
        assertTrue(viewModel.videosState.value is ChannelDetailViewModel.PaginatedState.Loaded)

        // Advance fake clock past rate limit
        advanceTimeBy(1100L)

        // Second page fails
        fakeRepository.videosError = RuntimeException("Pagination failed")
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected ErrorAppend state", state is ChannelDetailViewModel.PaginatedState.ErrorAppend)
        assertEquals(1, (state as ChannelDetailViewModel.PaginatedState.ErrorAppend).items.size)
    }

    @Test
    fun `retryAppend successfully loads next page from ErrorAppend state`() = runTest {
        val page1 = listOf(createTestVideo("v1", "Video 1"))
        val page2 = listOf(createTestVideo("v2", "Video 2"))
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // First page loaded
        assertTrue(viewModel.videosState.value is ChannelDetailViewModel.PaginatedState.Loaded)

        // Advance fake clock past rate limit (MIN_APPEND_INTERVAL_MS = 1000L)
        advanceTimeBy(1100L)

        // Second page fails
        fakeRepository.videosError = RuntimeException("Pagination failed")
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        val errorState = viewModel.videosState.value
        assertTrue("Expected ErrorAppend state", errorState is ChannelDetailViewModel.PaginatedState.ErrorAppend)
        assertEquals(1, (errorState as ChannelDetailViewModel.PaginatedState.ErrorAppend).items.size)
        // Verify nextPage is preserved in ErrorAppend state
        assertEquals(nextPage, errorState.nextPage)

        // Advance fake clock past rate limit again
        advanceTimeBy(1100L)

        // Fix error and retry - the nextPage should be restored from ErrorAppend state
        fakeRepository.videosError = null
        fakeRepository.videosResponse = ChannelPage(page2, null)
        viewModel.retryAppend(ChannelTab.VIDEOS)
        advanceUntilIdle()

        // Should successfully load next page
        val loadedState = viewModel.videosState.value
        assertTrue("Expected Loaded state after retry", loadedState is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (loadedState as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
        assertEquals("Video 1", loadedState.items[0].title)
        assertEquals("Video 2", loadedState.items[1].title)
    }

    /**
     * Edge case test: Ensures retry works even if hasReachedEnd was incorrectly set to true
     * while ErrorAppend state contains a valid nextPage.
     *
     * This scenario shouldn't occur in normal code flow, but the ViewModel should be defensive
     * against such inconsistent state by treating ErrorAppend.nextPage as the source of truth.
     */
    @Test
    fun `retryAppend restores hasReachedEnd when ErrorAppend has valid nextPage`() = runTest {
        val page1 = listOf(createTestVideo("v1", "Video 1"))
        val page2 = listOf(createTestVideo("v2", "Video 2"))
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // First page loaded
        assertTrue(viewModel.videosState.value is ChannelDetailViewModel.PaginatedState.Loaded)

        // Advance time past rate limit
        advanceTimeBy(1100L)

        // Second page fails - this creates ErrorAppend state with nextPage preserved
        fakeRepository.videosError = RuntimeException("Pagination failed")
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        val errorState = viewModel.videosState.value
        assertTrue("Expected ErrorAppend state", errorState is ChannelDetailViewModel.PaginatedState.ErrorAppend)
        assertEquals(nextPage, (errorState as ChannelDetailViewModel.PaginatedState.ErrorAppend).nextPage)

        // Advance time again
        advanceTimeBy(1100L)

        // Fix error and retry - the restoration logic should clear hasReachedEnd
        // and allow the retry to proceed even if there was inconsistent state
        fakeRepository.videosError = null
        fakeRepository.videosResponse = ChannelPage(page2, null)
        val retryAccepted = viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        // Retry should have been accepted
        assertTrue("Retry should be accepted when ErrorAppend has valid nextPage", retryAccepted)

        // Should successfully load next page
        val loadedState = viewModel.videosState.value
        assertTrue("Expected Loaded state after retry", loadedState is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (loadedState as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
    }

    /**
     * True edge case test: Forces controller into inconsistent state (hasReachedEnd=true,
     * nextPage=null) while ErrorAppend state still has a valid nextPage.
     *
     * This is the most defensive test scenario - it explicitly corrupts internal state
     * to verify that the restoration logic in loadNextPage() properly recovers from
     * the ErrorAppend state even when the controller itself is in an invalid state.
     */
    @Test
    fun `retryAppend recovers from forced inconsistent controller state`() = runTest {
        val page1 = listOf(createTestVideo("v1", "Video 1"))
        val page2 = listOf(createTestVideo("v2", "Video 2"))
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // First page loaded
        assertTrue(viewModel.videosState.value is ChannelDetailViewModel.PaginatedState.Loaded)

        // Advance time past rate limit
        advanceTimeBy(1100L)

        // Second page fails - this creates ErrorAppend state with nextPage preserved
        fakeRepository.videosError = RuntimeException("Pagination failed")
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        val errorState = viewModel.videosState.value
        assertTrue("Expected ErrorAppend state", errorState is ChannelDetailViewModel.PaginatedState.ErrorAppend)
        assertEquals(nextPage, (errorState as ChannelDetailViewModel.PaginatedState.ErrorAppend).nextPage)

        // FORCE INCONSISTENT STATE: Simulate a bug where controller says "reached end"
        // but ErrorAppend state still has a valid nextPage that should be usable
        viewModel.forceInconsistentState(ChannelTab.VIDEOS)

        // Advance time again
        advanceTimeBy(1100L)

        // Fix error and retry - the restoration logic MUST recover from this inconsistency
        fakeRepository.videosError = null
        fakeRepository.videosResponse = ChannelPage(page2, null)
        val retryAccepted = viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        // Retry MUST be accepted because ErrorAppend has valid nextPage
        assertTrue("Retry MUST be accepted when ErrorAppend has valid nextPage, regardless of controller state", retryAccepted)

        // Should successfully load next page
        val loadedState = viewModel.videosState.value
        assertTrue("Expected Loaded state after retry", loadedState is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (loadedState as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
        assertEquals("Video 1", loadedState.items[0].title)
        assertEquals("Video 2", loadedState.items[1].title)
    }

    // Rate Limiting Tests

    @Test
    fun `rate limiting prevents rapid pagination requests`() = runTest {
        val page1 = listOf(createTestVideo("v1", "Video 1"))
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // Reset call count after initial load
        fakeRepository.videosCallCount = 0

        // Advance time to allow first request, then rapid fire at same time
        advanceTimeBy(1100L)

        // Rapid fire pagination requests at same time (rate limiting should block 2nd and 3rd)
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        // Only first request should have been processed due to rate limiting
        assertEquals("Only 1 pagination call expected due to rate limiting", 1, fakeRepository.videosCallCount)
    }

    // Shorts Tab Tests

    @Test
    fun `shorts initial load works correctly`() = runTest {
        val shorts = listOf(createTestShort("s1", "Short 1"), createTestShort("s2", "Short 2"))
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.shortsResponse = ChannelPage(shorts, null)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // Manually load shorts (they are not pre-loaded like videos)
        viewModel.loadInitial(ChannelTab.SHORTS)
        advanceUntilIdle()

        val state = viewModel.shortsState.value
        assertTrue("Expected Loaded state", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
    }

    // Live Tab Tests

    @Test
    fun `live streams initial load works correctly`() = runTest {
        val liveStreams = listOf(
            createTestLiveStream("l1", "Live Stream 1", isLiveNow = true),
            createTestLiveStream("l2", "Upcoming Stream", isUpcoming = true)
        )
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.liveResponse = ChannelPage(liveStreams, null)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.LIVE)
        advanceUntilIdle()

        val state = viewModel.liveState.value
        assertTrue("Expected Loaded state", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
    }

    // Playlists Tab Tests

    @Test
    fun `playlists initial load works correctly`() = runTest {
        val playlists = listOf(
            createTestPlaylist("p1", "Playlist 1"),
            createTestPlaylist("p2", "Playlist 2")
        )
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.playlistsResponse = ChannelPage(playlists, null)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.PLAYLISTS)
        advanceUntilIdle()

        val state = viewModel.playlistsState.value
        assertTrue("Expected Loaded state", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
    }

    // Empty First Page With Continuation Tests

    @Test
    fun `live exposes empty first page continuation before manual append`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)
        val page2Live = listOf(createTestLiveStream("l1", "Live Stream 1", isLiveNow = true))

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.livePagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),
            ChannelPage(page2Live, null)
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.LIVE)
        advanceUntilIdle()

        val state = viewModel.liveState.value
        assertTrue("Expected Loaded state with continuation", state is ChannelDetailViewModel.PaginatedState.Loaded)
        val initial = state as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(0, initial.items.size)
        assertEquals(nextPage, initial.nextPage)
        assertTrue(initial.showLoadMoreFooter)
        assertEquals(1, fakeRepository.liveCallCount)

        assertTrue(viewModel.loadNextPage(ChannelTab.LIVE))
        advanceUntilIdle()

        val appended = viewModel.liveState.value as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(1, appended.items.size)
        assertEquals(2, fakeRepository.liveCallCount)
    }

    @Test
    fun `shorts exposes empty first page continuation before manual append`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)
        val page2Shorts = listOf(createTestShort("s1", "Short 1"))

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.shortsPagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),
            ChannelPage(page2Shorts, null)
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.SHORTS)
        advanceUntilIdle()

        val state = viewModel.shortsState.value
        assertTrue("Expected Loaded state with continuation", state is ChannelDetailViewModel.PaginatedState.Loaded)
        val initial = state as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(0, initial.items.size)
        assertEquals(nextPage, initial.nextPage)
        assertTrue(initial.showLoadMoreFooter)
        assertEquals(1, fakeRepository.shortsCallCount)

        assertTrue(viewModel.loadNextPage(ChannelTab.SHORTS))
        advanceUntilIdle()

        val appended = viewModel.shortsState.value as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(1, appended.items.size)
        assertEquals(2, fakeRepository.shortsCallCount)
    }

    @Test
    fun `playlists exposes empty first page continuation before manual append`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)
        val page2Playlists = listOf(createTestPlaylist("p1", "Playlist 1"))

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.playlistsPagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),
            ChannelPage(page2Playlists, null)
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.PLAYLISTS)
        advanceUntilIdle()

        val state = viewModel.playlistsState.value
        assertTrue("Expected Loaded state with continuation", state is ChannelDetailViewModel.PaginatedState.Loaded)
        val initial = state as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(0, initial.items.size)
        assertEquals(nextPage, initial.nextPage)
        assertTrue(initial.showLoadMoreFooter)
        assertEquals(1, fakeRepository.playlistsCallCount)

        assertTrue(viewModel.loadNextPage(ChannelTab.PLAYLISTS))
        advanceUntilIdle()

        val appended = viewModel.playlistsState.value as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals(1, appended.items.size)
        assertEquals(2, fakeRepository.playlistsCallCount)
    }

    @Test
    fun `videos emits Empty only when both first page and continuation are empty`() = runTest {
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(emptyList(), null) // No items, no continuation

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected Empty state", state is ChannelDetailViewModel.PaginatedState.Empty)
    }

    @Test
    fun `videos append shows load more when empty continuation cap is reached`() = runTest {
        val firstNextPage = Page("http://initial-next", null, null, null)
        val initialVideos = listOf(createTestVideo("v1", "Video 1"))
        val emptyContinuationPages = (1..6).map { i ->
            ChannelPage<ChannelVideo>(emptyList(), Page("http://append-continuation$i", null, null, null))
        }

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(initialVideos, firstNextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        fakeRepository.videosCallCount = 0
        fakeRepository.videosPagedResponses = emptyContinuationPages
        advanceTimeBy(1100L)

        assertTrue(viewModel.loadNextPage(ChannelTab.VIDEOS))
        advanceUntilIdle()

        assertEquals("Append should stop at the empty-page cap", 5, fakeRepository.videosCallCount)
        val state = viewModel.videosState.value
        assertTrue("Expected Loaded state", state is ChannelDetailViewModel.PaginatedState.Loaded)
        val loaded = state as ChannelDetailViewModel.PaginatedState.Loaded
        assertEquals("Existing items should be preserved", 1, loaded.items.size)
        assertTrue("Load More footer should appear after capped empty append", loaded.showLoadMoreFooter)
        assertEquals("Continuation should be preserved for manual Load More", "http://append-continuation5", loaded.nextPage?.url)
    }

    @Test
    fun `live emits Empty when continuation also returns empty`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.livePagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),
            ChannelPage(emptyList(), null)
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.LIVE)
        advanceUntilIdle()

        val initial = viewModel.liveState.value
        assertTrue("Expected Loaded state with continuation", initial is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(1, fakeRepository.liveCallCount)

        assertTrue(viewModel.loadNextPage(ChannelTab.LIVE))
        advanceUntilIdle()

        val state = viewModel.liveState.value
        assertTrue("Expected Empty state", state is ChannelDetailViewModel.PaginatedState.Empty)
        assertEquals(2, fakeRepository.liveCallCount)
    }

    @Test
    fun `shorts emits Empty when continuation also returns empty`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.shortsPagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),
            ChannelPage(emptyList(), null)
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.SHORTS)
        advanceUntilIdle()

        val initial = viewModel.shortsState.value
        assertTrue("Expected Loaded state with continuation", initial is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(1, fakeRepository.shortsCallCount)

        assertTrue(viewModel.loadNextPage(ChannelTab.SHORTS))
        advanceUntilIdle()

        val state = viewModel.shortsState.value
        assertTrue("Expected Empty state", state is ChannelDetailViewModel.PaginatedState.Empty)
        assertEquals(2, fakeRepository.shortsCallCount)
    }

    @Test
    fun `playlists emits Empty when continuation also returns empty`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.playlistsPagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),
            ChannelPage(emptyList(), null)
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        viewModel.loadInitial(ChannelTab.PLAYLISTS)
        advanceUntilIdle()

        val initial = viewModel.playlistsState.value
        assertTrue("Expected Loaded state with continuation", initial is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(1, fakeRepository.playlistsCallCount)

        assertTrue(viewModel.loadNextPage(ChannelTab.PLAYLISTS))
        advanceUntilIdle()

        val state = viewModel.playlistsState.value
        assertTrue("Expected Empty state", state is ChannelDetailViewModel.PaginatedState.Empty)
        assertEquals(2, fakeRepository.playlistsCallCount)
    }

    // Tab Selection Tests

    @Test
    fun `setSelectedTab updates selected tab state`() = runTest {
        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        assertEquals(0, viewModel.selectedTab.value)

        viewModel.setSelectedTab(2)
        assertEquals(2, viewModel.selectedTab.value)

        viewModel.setSelectedTab(5)
        assertEquals(5, viewModel.selectedTab.value)
    }

    // Scroll Pagination Trigger Tests

    @Test
    fun `onListScrolled triggers pagination near end of list`() = runTest {
        val page1 = (1..10).map { createTestVideo("v$it", "Video $it") }
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        fakeRepository.videosCallCount = 0
        fakeRepository.videosResponse = ChannelPage(emptyList(), null)

        // Advance fake clock past rate limit
        advanceTimeBy(1100L)

        // Simulate scroll near end (threshold is 5)
        viewModel.onListScrolled(ChannelTab.VIDEOS, lastVisibleItem = 8, totalCount = 10)
        advanceUntilIdle()

        // Should trigger pagination
        assertEquals(1, fakeRepository.videosCallCount)
    }

    @Test
    fun `onListScrolled does not trigger when far from end`() = runTest {
        val page1 = (1..10).map { createTestVideo("v$it", "Video $it") }
        val nextPage = Page("http://next", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosResponse = ChannelPage(page1, nextPage)

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        fakeRepository.videosCallCount = 0

        // Simulate scroll far from end
        viewModel.onListScrolled(ChannelTab.VIDEOS, lastVisibleItem = 2, totalCount = 10)
        advanceUntilIdle()

        // Should NOT trigger pagination
        assertEquals(0, fakeRepository.videosCallCount)
    }

    // Helper functions to create test data

    private fun createTestHeader(id: String, title: String) = ChannelHeader(
        id = id,
        title = title,
        avatarUrl = "https://example.com/avatar.jpg",
        bannerUrl = "https://example.com/banner.jpg",
        subscriberCount = 1000000L,
        shortDescription = "Short description",
        summaryLine = "Test Channel • Verified",
        fullDescription = "Full description text",
        links = emptyList(),
        location = null,
        joinedDate = null,
        totalViews = null,
        isVerified = true,
        tags = listOf("tag1", "tag2")
    )

    private fun createTestVideo(id: String, title: String) = ChannelVideo(
        id = id,
        title = title,
        thumbnailUrl = "https://example.com/thumb.jpg",
        durationSeconds = 600,
        viewCount = 10000L,
        publishedTime = "1 day ago",
        uploaderName = "Test Channel"
    )

    private fun createTestShort(id: String, title: String) = ChannelShort(
        id = id,
        title = title,
        thumbnailUrl = "https://example.com/short.jpg",
        viewCount = 5000L,
        durationSeconds = 30,
        publishedTime = "2 hours ago"
    )

    private fun createTestLiveStream(
        id: String,
        title: String,
        isLiveNow: Boolean = false,
        isUpcoming: Boolean = false
    ) = ChannelLiveStream(
        id = id,
        title = title,
        thumbnailUrl = "https://example.com/live.jpg",
        isLiveNow = isLiveNow,
        isUpcoming = isUpcoming,
        scheduledStartTime = null,
        viewCount = if (isLiveNow) 1000L else null,
        uploaderName = "Test Channel",
        durationSeconds = if (!isLiveNow && !isUpcoming) 3600 else null,
        publishedTime = if (!isLiveNow && !isUpcoming) "2 weeks ago" else null
    )

    private fun createTestPlaylist(id: String, title: String) = ChannelPlaylist(
        id = id,
        title = title,
        thumbnailUrl = "https://example.com/playlist.jpg",
        itemCount = 25L,
        description = "Playlist description",
        uploaderName = "Test Channel"
    )

    // ── Availability Gate Tests ───────────────────────────────────────────────

    @Test
    fun `loadHeader archived channel emits ContentUnavailable and skips repository`() = runTest {
        val mockContentService: ContentService = mock()
        whenever(mockContentService.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
            .thenReturn(false)

        val vm = createViewModel(channelId = "UCabc", contentService = mockContentService)
        advanceUntilIdle()

        assertEquals(ChannelDetailViewModel.HeaderState.ContentUnavailable, vm.headerState.value)
        // Repository must not have been called — no NewPipe work for archived channels
        assertEquals(0, fakeRepository.headerCallCount)
    }

    @Test
    fun `loadHeader transport error fails open and proceeds to repository`() = runTest {
        val mockContentService: ContentService = mock()
        val testHeader = createTestHeader("UCabc", "Test Channel")
        whenever(mockContentService.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
            .thenThrow(RuntimeException("network timeout"))
        fakeRepository.headerResponse = testHeader

        val vm = createViewModel(channelId = "UCabc", contentService = mockContentService)
        advanceUntilIdle()

        // Fail-open: transport error should not block the user; header must succeed
        assertTrue(vm.headerState.value is ChannelDetailViewModel.HeaderState.Success)
        assertEquals(1, fakeRepository.headerCallCount)
    }

    @Test
    fun `loadInitial called after ContentUnavailable is a no-op`() = runTest {
        val mockContentService: ContentService = mock()
        whenever(mockContentService.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
            .thenReturn(false)

        val vm = createViewModel(channelId = "UCabc", contentService = mockContentService)
        advanceUntilIdle()

        assertEquals(ChannelDetailViewModel.HeaderState.ContentUnavailable, vm.headerState.value)

        // Any explicit tab loads triggered after the unavailable state is set
        // (e.g., ensureTabLoaded calls, tab selection) must be short-circuited.
        val callCountBefore = fakeRepository.videosCallCount
        vm.loadInitial(ChannelTab.VIDEOS)
        advanceUntilIdle()

        assertEquals("loadInitial must not call repository when channel is unavailable",
            callCountBefore, fakeRepository.videosCallCount)
    }

    @Test
    fun `loadInitial on archived channel skips NewPipe and emits ContentUnavailable`() = runTest {
        // Simulate the parallel-fetch race: loadInitial fires at the same time as
        // loadHeader (from init {}), BEFORE loadHeader has had a chance to settle
        // _headerState to ContentUnavailable. The async gate inside loadInitial's
        // launch block must independently query the backend and abort the tab load.
        val mockContentService: ContentService = mock()
        whenever(mockContentService.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
            .thenReturn(false)

        val vm = createViewModel(channelId = "UCabc", contentService = mockContentService)
        advanceUntilIdle()

        assertEquals(ChannelDetailViewModel.HeaderState.ContentUnavailable, vm.headerState.value)
        // Repository must never have been called — no NewPipe work for archived channels.
        assertEquals(0, fakeRepository.videosCallCount)
    }

    // ── Internal Fake Classes ─────────────────────────────────────────────────

    /**
     * Fake ChannelDetailRepository for testing.
     * Supports both single response mode (for simple tests) and paged response mode
     * (for testing pagination scenarios like empty first page with continuation).
     */
    private class FakeChannelDetailRepository : ChannelDetailRepository {
        var headerResponse: ChannelHeader? = null
        var headerError: Exception? = null
        var headerCallCount = 0

        // Single response mode (for backward compatibility with existing tests)
        var videosResponse: ChannelPage<ChannelVideo> = ChannelPage(emptyList(), null)
        var videosError: Exception? = null
        var videosCallCount = 0

        // Paged response mode (for testing pagination)
        var videosPagedResponses: List<ChannelPage<ChannelVideo>>? = null

        var liveResponse: ChannelPage<ChannelLiveStream> = ChannelPage(emptyList(), null)
        var liveError: Exception? = null
        var liveCallCount = 0
        var livePagedResponses: List<ChannelPage<ChannelLiveStream>>? = null

        var shortsResponse: ChannelPage<ChannelShort> = ChannelPage(emptyList(), null)
        var shortsError: Exception? = null
        var shortsCallCount = 0
        var shortsPagedResponses: List<ChannelPage<ChannelShort>>? = null

        var playlistsResponse: ChannelPage<ChannelPlaylist> = ChannelPage(emptyList(), null)
        var playlistsError: Exception? = null
        var playlistsCallCount = 0
        var playlistsPagedResponses: List<ChannelPage<ChannelPlaylist>>? = null

        override suspend fun getChannelHeader(channelId: String, forceRefresh: Boolean): ChannelHeader {
            headerCallCount++
            headerError?.let { throw it }
            return headerResponse ?: throw RuntimeException("No header response configured")
        }

        override suspend fun getVideos(channelId: String, page: Page?): ChannelPage<ChannelVideo> {
            val callIndex = videosCallCount
            videosCallCount++
            videosError?.let { throw it }
            // Use paged responses if configured, otherwise fall back to single response
            return videosPagedResponses?.getOrNull(callIndex) ?: videosResponse
        }

        // Channel-tab fast-paint path. Tests don't usually distinguish this
        // from [getVideos] — return the same response so the ViewModel sees
        // identical items via either route. Honors [videosError] by default
        // so error-propagation tests fail BOTH paths and the ViewModel can
        // surface ErrorInitial/ErrorAppend as expected. Tests that need the
        // channel-tab path to behave differently can override the dedicated
        // [channelTabResponse] / [channelTabError] fields.
        var channelTabResponse: ChannelPage<ChannelVideo>? = null
        var channelTabError: Exception? = null
        var channelTabCallCount = 0
        override suspend fun getVideosViaChannelTab(channelId: String): ChannelPage<ChannelVideo> {
            channelTabCallCount++
            (channelTabError ?: videosError)?.let { throw it }
            return channelTabResponse ?: videosResponse
        }

        override suspend fun getLiveStreams(channelId: String, page: Page?): ChannelPage<ChannelLiveStream> {
            val callIndex = liveCallCount
            liveCallCount++
            liveError?.let { throw it }
            return livePagedResponses?.getOrNull(callIndex) ?: liveResponse
        }

        override suspend fun getShorts(channelId: String, page: Page?): ChannelPage<ChannelShort> {
            val callIndex = shortsCallCount
            shortsCallCount++
            shortsError?.let { throw it }
            return shortsPagedResponses?.getOrNull(callIndex) ?: shortsResponse
        }

        override suspend fun getPlaylists(channelId: String, page: Page?): ChannelPage<ChannelPlaylist> {
            val callIndex = playlistsCallCount
            playlistsCallCount++
            playlistsError?.let { throw it }
            return playlistsPagedResponses?.getOrNull(callIndex) ?: playlistsResponse
        }

        override suspend fun getAbout(channelId: String, forceRefresh: Boolean): ChannelHeader {
            return getChannelHeader(channelId, forceRefresh)
        }
    }

    /**
     * Fake [ChannelVideoCacheDao] used to exercise the Step 6 cache-read path
     * in the ViewModel. Only [getForChannel] and [upsertAll] are wired —
     * the Flow/cleanup methods aren't reached by the ViewModel under test.
     */
    private class FakeChannelVideoCacheDao : ChannelVideoCacheDao {
        var rowsForChannel: Map<String, List<ChannelVideoCache>> = emptyMap()
        var lastUpsert: List<ChannelVideoCache>? = null
        var getForChannelCallCount: Int = 0

        override suspend fun getForChannel(channelId: String): List<ChannelVideoCache> {
            getForChannelCallCount++
            return rowsForChannel[channelId] ?: emptyList()
        }

        override suspend fun upsertAll(rows: List<ChannelVideoCache>) {
            lastUpsert = rows
        }

        override suspend fun insertIgnoreAll(rows: List<ChannelVideoCache>) {
            // no-op for tests
        }

        override fun observeRecentForChannels(
            channelIds: List<String>,
            minUploadedAt: Long,
        ): Flow<List<ChannelVideoCache>> =
            throw NotImplementedError("not exercised by ChannelDetailViewModel tests")

        override fun observeRangeForChannels(
            channelIds: List<String>,
            fromMs: Long,
            toMs: Long,
        ): Flow<List<ChannelVideoCache>> =
            throw NotImplementedError("not exercised by ChannelDetailViewModel tests")

        override suspend fun deleteForChannel(channelId: String) {
            // no-op for tests
        }

        override suspend fun pruneUnsubscribed() {
            // no-op for tests
        }

        override suspend fun pruneOlderThan(cutoffMs: Long) {
            // no-op for tests
        }

        override suspend fun countForChannels(channelIds: List<String>): Int = 0

        override suspend fun oldestPerChannel(channelIds: List<String>): List<ChannelOldest> =
            emptyList()

        override fun observeRangeForChannelsOrPlaylists(
            channelIds: List<String>,
            playlistIds: List<String>,
            fromMs: Long,
            toMs: Long,
        ): Flow<List<ChannelVideoCache>> =
            throw NotImplementedError("not exercised by ChannelDetailViewModel tests")

        override suspend fun countForChannelsOrPlaylists(
            channelIds: List<String>,
            playlistIds: List<String>,
        ): Int = 0
    }
}
