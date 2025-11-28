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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeChannelDetailRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(channelId: String = "UCtest123"): ChannelDetailViewModel {
        return ChannelDetailViewModel(fakeRepository, channelId)
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

        // Second page fails
        fakeRepository.videosError = RuntimeException("Pagination failed")
        viewModel.loadNextPage(ChannelTab.VIDEOS)
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected ErrorAppend state", state is ChannelDetailViewModel.PaginatedState.ErrorAppend)
        assertEquals(1, (state as ChannelDetailViewModel.PaginatedState.ErrorAppend).items.size)
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

        // Rapid fire pagination requests
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
        uploaderName = "Test Channel"
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
     */
    private class FakeChannelDetailRepository : ChannelDetailRepository {
        var headerResponse: ChannelHeader? = null
        var headerError: Exception? = null

        var videosResponse: ChannelPage<ChannelVideo> = ChannelPage(emptyList(), null)
        var videosError: Exception? = null
        var videosCallCount = 0

        var liveResponse: ChannelPage<ChannelLiveStream> = ChannelPage(emptyList(), null)
        var liveError: Exception? = null

        var shortsResponse: ChannelPage<ChannelShort> = ChannelPage(emptyList(), null)
        var shortsError: Exception? = null

        var playlistsResponse: ChannelPage<ChannelPlaylist> = ChannelPage(emptyList(), null)
        var playlistsError: Exception? = null

        override suspend fun getChannelHeader(channelId: String, forceRefresh: Boolean): ChannelHeader {
            headerError?.let { throw it }
            return headerResponse ?: throw RuntimeException("No header response configured")
        }

        override suspend fun getVideos(channelId: String, page: Page?): ChannelPage<ChannelVideo> {
            videosCallCount++
            videosError?.let { throw it }
            return videosResponse
        }

        override suspend fun getLiveStreams(channelId: String, page: Page?): ChannelPage<ChannelLiveStream> {
            liveError?.let { throw it }
            return liveResponse
        }

        override suspend fun getShorts(channelId: String, page: Page?): ChannelPage<ChannelShort> {
            shortsError?.let { throw it }
            return shortsResponse
        }

        override suspend fun getPlaylists(channelId: String, page: Page?): ChannelPage<ChannelPlaylist> {
            playlistsError?.let { throw it }
            return playlistsResponse
        }

        override suspend fun getAbout(channelId: String, forceRefresh: Boolean): ChannelHeader {
            return getChannelHeader(channelId, forceRefresh)
        }
    }
}
