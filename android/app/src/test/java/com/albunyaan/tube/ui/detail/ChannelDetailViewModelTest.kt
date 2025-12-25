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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
        fakeCurrentTimeMs = 2000L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Create a ViewModel with an injectable time provider for deterministic rate limiting tests.
     */
    private fun createViewModel(channelId: String = "UCtest123"): ChannelDetailViewModel {
        return ChannelDetailViewModel(fakeRepository, channelId).apply {
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
    fun `videos auto-fetches next page when first page is empty but has continuation`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)
        val page2Videos = listOf(createTestVideo("v1", "Video 1"), createTestVideo("v2", "Video 2"))

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        // Configure: first page empty with continuation, second page has items
        fakeRepository.videosPagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),  // First page: empty with continuation
            ChannelPage(page2Videos, null)       // Second page: has items
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        // Should have auto-fetched the second page and have items
        val state = viewModel.videosState.value
        assertTrue("Expected Loaded state with items", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
        // Verify both pages were fetched
        assertEquals(2, fakeRepository.videosCallCount)
    }

    @Test
    fun `live auto-fetches next page when first page is empty but has continuation`() = runTest {
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
        assertTrue("Expected Loaded state with items", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(1, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
        assertEquals(2, fakeRepository.liveCallCount)
    }

    @Test
    fun `shorts auto-fetches next page when first page is empty but has continuation`() = runTest {
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
        assertTrue("Expected Loaded state with items", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(1, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
        assertEquals(2, fakeRepository.shortsCallCount)
    }

    @Test
    fun `playlists auto-fetches next page when first page is empty but has continuation`() = runTest {
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
        assertTrue("Expected Loaded state with items", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(1, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
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
    fun `videos emits Empty when first page has continuation but continuation also returns empty`() = runTest {
        val nextPage = Page("http://continuation", null, null, null)

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        // First page: empty with continuation, second page: also empty with no continuation
        fakeRepository.videosPagedResponses = listOf(
            ChannelPage(emptyList(), nextPage),  // First page: empty with continuation
            ChannelPage(emptyList(), null)       // Second page: empty with no continuation
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected Empty state when all pages are empty", state is ChannelDetailViewModel.PaginatedState.Empty)
        // Verify both pages were fetched
        assertEquals(2, fakeRepository.videosCallCount)
    }

    @Test
    fun `videos handles multiple consecutive empty pages before finding content`() = runTest {
        val nextPage1 = Page("http://continuation1", null, null, null)
        val nextPage2 = Page("http://continuation2", null, null, null)
        val nextPage3 = Page("http://continuation3", null, null, null)
        val videos = listOf(createTestVideo("v1", "Video 1"))

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        // Multiple empty pages before content
        fakeRepository.videosPagedResponses = listOf(
            ChannelPage(emptyList(), nextPage1),
            ChannelPage(emptyList(), nextPage2),
            ChannelPage(emptyList(), nextPage3),
            ChannelPage(videos, null)
        )

        val viewModel = createViewModel("UCtest123")
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected Loaded state after skipping empty pages", state is ChannelDetailViewModel.PaginatedState.Loaded)
        assertEquals(1, (state as ChannelDetailViewModel.PaginatedState.Loaded).items.size)
        // Verify all pages were fetched
        assertEquals(4, fakeRepository.videosCallCount)
    }

    @Test
    fun `videos limits consecutive empty page fetches to prevent infinite loops`() = runTest {
        // Create more continuation pages than the limit (5)
        val pages = (1..10).map { i ->
            ChannelPage<ChannelVideo>(emptyList(), Page("http://continuation$i", null, null, null))
        }

        fakeRepository.headerResponse = createTestHeader("UCtest123", "Test Channel")
        fakeRepository.videosPagedResponses = pages

        // Creating the ViewModel triggers loadHeader -> loadInitial(VIDEOS)
        createViewModel("UCtest123")
        advanceUntilIdle()

        // Should stop at MAX_EMPTY_PAGE_FETCHES (5), not fetch all 10
        assertEquals("Should limit empty page fetches to 5", 5, fakeRepository.videosCallCount)
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

    /**
     * Fake ChannelDetailRepository for testing.
     * Supports both single response mode (for simple tests) and paged response mode
     * (for testing pagination scenarios like empty first page with continuation).
     */
    private class FakeChannelDetailRepository : ChannelDetailRepository {
        var headerResponse: ChannelHeader? = null
        var headerError: Exception? = null

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
}
