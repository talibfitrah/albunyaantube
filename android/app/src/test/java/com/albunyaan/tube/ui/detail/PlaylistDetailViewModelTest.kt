package com.albunyaan.tube.ui.detail

import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import com.albunyaan.tube.data.playlist.PlaylistHeader
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.data.playlist.PlaylistPage
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.download.PlaylistDownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for PlaylistDetailViewModel.
 *
 * Tests verify:
 * - Header loading (success, error states)
 * - Paginated items (initial load, pagination, empty, errors)
 * - Rate limiting for pagination
 * - Download state aggregation
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlaylistDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakePlaylistDetailRepository
    private lateinit var fakeDownloadRepository: FakeDownloadRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakePlaylistDetailRepository()
        fakeDownloadRepository = FakeDownloadRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        playlistId: String = "PLtest123",
        initialTitle: String? = null,
        initialCategory: String? = null,
        initialCount: Int = 0,
        downloadPolicy: DownloadPolicy = DownloadPolicy.ENABLED,
        excluded: Boolean = false
    ): PlaylistDetailViewModel {
        return PlaylistDetailViewModel(
            repository = fakeRepository,
            downloadRepository = fakeDownloadRepository,
            playlistId = playlistId,
            initialTitle = initialTitle,
            initialCategory = initialCategory,
            initialCount = initialCount,
            downloadPolicy = downloadPolicy,
            excluded = excluded
        )
    }

    // Header Tests

    @Test
    fun `header loading emits Loading then Success`() = runTest {
        val header = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.headerResponse = header

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        val state = viewModel.headerState.value
        assertTrue("Expected Success state", state is PlaylistDetailViewModel.HeaderState.Success)
        assertEquals("Test Playlist", (state as PlaylistDetailViewModel.HeaderState.Success).header.title)
    }

    @Test
    fun `header loading emits Error when repository throws`() = runTest {
        fakeRepository.headerError = RuntimeException("Network error")

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        val state = viewModel.headerState.value
        assertTrue("Expected Error state", state is PlaylistDetailViewModel.HeaderState.Error)
        assertTrue((state as PlaylistDetailViewModel.HeaderState.Error).message.contains("Network error"))
    }

    @Test
    fun `header retry reloads header data`() = runTest {
        fakeRepository.headerError = RuntimeException("Error")

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        assertTrue(viewModel.headerState.value is PlaylistDetailViewModel.HeaderState.Error)

        fakeRepository.headerError = null
        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")

        viewModel.loadHeader(forceRefresh = true)
        advanceUntilIdle()

        assertTrue(viewModel.headerState.value is PlaylistDetailViewModel.HeaderState.Success)
    }

    // Items Tests

    @Test
    fun `items initial load emits LoadingInitial then Loaded`() = runTest {
        val items = listOf(
            createTestItem(1, "v1", "Video 1"),
            createTestItem(2, "v2", "Video 2")
        )
        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(items, null)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        val state = viewModel.itemsState.value
        assertTrue("Expected Loaded state", state is PlaylistDetailViewModel.PaginatedState.Loaded)
        assertEquals(2, (state as PlaylistDetailViewModel.PaginatedState.Loaded).items.size)
    }

    @Test
    fun `items emits Empty when no items available`() = runTest {
        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(emptyList(), null)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        val state = viewModel.itemsState.value
        assertTrue("Expected Empty state", state is PlaylistDetailViewModel.PaginatedState.Empty)
    }

    @Test
    fun `items pagination appends items`() = runTest {
        val page1 = listOf(createTestItem(1, "v1", "Video 1"))
        val page2 = listOf(createTestItem(2, "v2", "Video 2"))
        val nextPage = Page("http://next", "2", null, null)

        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(page1, nextPage)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        var state = viewModel.itemsState.value as PlaylistDetailViewModel.PaginatedState.Loaded
        assertEquals(1, state.items.size)

        fakeRepository.itemsResponse = PlaylistPage(page2, null)
        viewModel.loadNextPage()
        advanceUntilIdle()

        state = viewModel.itemsState.value as PlaylistDetailViewModel.PaginatedState.Loaded
        assertEquals(2, state.items.size)
        assertEquals("Video 1", state.items[0].title)
        assertEquals("Video 2", state.items[1].title)
    }

    @Test
    fun `items pagination stops when no next page`() = runTest {
        val items = listOf(createTestItem(1, "v1", "Video 1"))
        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(items, null)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        val state = viewModel.itemsState.value as PlaylistDetailViewModel.PaginatedState.Loaded
        assertEquals(1, state.items.size)
        assertNull(state.nextPage)
    }

    @Test
    fun `items ErrorInitial when first load fails`() = runTest {
        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsError = RuntimeException("Failed to load items")

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        val state = viewModel.itemsState.value
        assertTrue("Expected ErrorInitial state", state is PlaylistDetailViewModel.PaginatedState.ErrorInitial)
    }

    @Test
    fun `items ErrorAppend preserves existing items on pagination failure`() = runTest {
        val page1 = listOf(createTestItem(1, "v1", "Video 1"))
        val nextPage = Page("http://next", "2", null, null)

        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(page1, nextPage)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        assertTrue(viewModel.itemsState.value is PlaylistDetailViewModel.PaginatedState.Loaded)

        fakeRepository.itemsError = RuntimeException("Pagination failed")
        viewModel.loadNextPage()
        advanceUntilIdle()

        val state = viewModel.itemsState.value
        assertTrue("Expected ErrorAppend state", state is PlaylistDetailViewModel.PaginatedState.ErrorAppend)
        assertEquals(1, (state as PlaylistDetailViewModel.PaginatedState.ErrorAppend).items.size)
    }

    // Rate Limiting Tests

    @Test
    fun `rate limiting prevents rapid pagination requests`() = runTest {
        val page1 = listOf(createTestItem(1, "v1", "Video 1"))
        val nextPage = Page("http://next", "2", null, null)

        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(page1, nextPage)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        fakeRepository.itemsCallCount = 0

        // Rapid fire pagination requests
        viewModel.loadNextPage()
        viewModel.loadNextPage()
        viewModel.loadNextPage()
        advanceUntilIdle()

        // Only first request should have been processed due to rate limiting
        assertEquals("Only 1 pagination call expected due to rate limiting", 1, fakeRepository.itemsCallCount)
    }

    // Scroll Pagination Trigger Tests

    @Test
    fun `onListScrolled triggers pagination near end of list`() = runTest {
        val page1 = (1..10).map { createTestItem(it, "v$it", "Video $it") }
        val nextPage = Page("http://next", "11", null, null)

        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(page1, nextPage)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        fakeRepository.itemsCallCount = 0
        fakeRepository.itemsResponse = PlaylistPage(emptyList(), null)

        viewModel.onListScrolled(lastVisibleItem = 8, totalCount = 10)
        advanceUntilIdle()

        assertEquals(1, fakeRepository.itemsCallCount)
    }

    @Test
    fun `onListScrolled does not trigger when far from end`() = runTest {
        val page1 = (1..10).map { createTestItem(it, "v$it", "Video $it") }
        val nextPage = Page("http://next", "11", null, null)

        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(page1, nextPage)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        fakeRepository.itemsCallCount = 0

        viewModel.onListScrolled(lastVisibleItem = 2, totalCount = 10)
        advanceUntilIdle()

        assertEquals(0, fakeRepository.itemsCallCount)
    }

    // Multi-page Pagination Tests

    @Test
    fun `multi-page pagination maintains monotonic item positions`() = runTest {
        // Use a repository that simulates 2 pages
        val pageRepository = object : PlaylistDetailRepository {
            private var pageNumber = 0

            override suspend fun getHeader(
                playlistId: String,
                forceRefresh: Boolean,
                category: String?,
                excluded: Boolean,
                downloadPolicy: DownloadPolicy
            ): PlaylistHeader = createTestHeader(playlistId, "Large Playlist", category, excluded, downloadPolicy)

            override suspend fun getItems(
                playlistId: String,
                page: Page?,
                itemOffset: Int
            ): PlaylistPage<PlaylistItem> {
                pageNumber++

                return when (pageNumber) {
                    1 -> PlaylistPage(
                        items = (1..5).map { createTestItem(it, "v$it", "Video $it") },
                        nextPage = Page("http://page2", "page2Token", null, null),
                        nextItemOffset = 6
                    )
                    else -> PlaylistPage(
                        items = (6..10).map { createTestItem(it, "v$it", "Video $it") },
                        nextPage = null,
                        nextItemOffset = 11
                    )
                }
            }
        }

        val viewModel = PlaylistDetailViewModel(
            repository = pageRepository,
            downloadRepository = fakeDownloadRepository,
            playlistId = "PLtest123",
            initialTitle = null,
            initialCategory = null,
            initialCount = 0,
            downloadPolicy = DownloadPolicy.ENABLED,
            excluded = false
        )
        val clock = AtomicLong(0L)
        viewModel.setClockForTesting { clock.get() }
        advanceUntilIdle()

        // Verify page 1
        var state = viewModel.itemsState.value as PlaylistDetailViewModel.PaginatedState.Loaded
        assertEquals("Page 1 should have 5 items", 5, state.items.size)
        assertEquals(1, state.items.first().position)
        assertEquals(5, state.items.last().position)

        // Advance past rate limit interval
        clock.addAndGet(PlaylistDetailViewModel.MIN_APPEND_INTERVAL_MS + 1)

        // Load page 2
        viewModel.loadNextPage()
        advanceUntilIdle()

        state = viewModel.itemsState.value as PlaylistDetailViewModel.PaginatedState.Loaded
        assertEquals("Page 2 should add 5 items (total 10)", 10, state.items.size)

        // Verify positions remain monotonically increasing across pages
        val positions = state.items.map { it.position }
        assertEquals("Positions should be 1-10", listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), positions)
        assertTrue(
            "Positions should be strictly monotonically increasing",
            positions.zipWithNext().all { (a, b) -> b == a + 1 }
        )

        // Verify final page has no next page
        assertNull("Final page should have no nextPage", state.nextPage)
    }

    @Test
    fun `pagination tracks nextItemOffset correctly across pages`() = runTest {
        // Use a tracking repository that records received offsets
        val receivedOffsets = mutableListOf<Int>()
        val trackingRepository = object : PlaylistDetailRepository {
            private var pageNumber = 0

            override suspend fun getHeader(
                playlistId: String,
                forceRefresh: Boolean,
                category: String?,
                excluded: Boolean,
                downloadPolicy: DownloadPolicy
            ): PlaylistHeader = PlaylistHeader(
                id = playlistId,
                title = "Test Playlist",
                thumbnailUrl = "https://example.com/thumb.jpg",
                bannerUrl = null,
                channelId = "UCtest",
                channelName = "Test Channel",
                itemCount = 10L,
                totalDurationSeconds = 600L,
                description = "Test",
                tags = emptyList(),
                category = category,
                excluded = excluded,
                downloadPolicy = downloadPolicy
            )

            override suspend fun getItems(
                playlistId: String,
                page: Page?,
                itemOffset: Int
            ): PlaylistPage<PlaylistItem> {
                receivedOffsets.add(itemOffset)
                pageNumber++

                // First page: items 1-5, next offset = 6
                // Second page: items 6-10, no more pages
                return if (pageNumber == 1) {
                    PlaylistPage(
                        items = (1..5).map { createTestItem(it, "v$it", "Video $it") },
                        nextPage = Page("http://next", "token2", null, null),
                        nextItemOffset = 6
                    )
                } else {
                    PlaylistPage(
                        items = (6..10).map { createTestItem(it, "v$it", "Video $it") },
                        nextPage = null,
                        nextItemOffset = 11
                    )
                }
            }
        }

        // Create ViewModel with tracking repository
        val trackingViewModel = PlaylistDetailViewModel(
            repository = trackingRepository,
            downloadRepository = fakeDownloadRepository,
            playlistId = "PLtest123",
            initialTitle = null,
            initialCategory = null,
            initialCount = 0,
            downloadPolicy = DownloadPolicy.ENABLED,
            excluded = false
        )
        val clock = AtomicLong(0L)
        trackingViewModel.setClockForTesting { clock.get() }
        advanceUntilIdle()

        // Verify first page loaded with offset 1
        assertTrue(trackingViewModel.itemsState.value is PlaylistDetailViewModel.PaginatedState.Loaded)
        assertEquals("First page should receive offset 1", 1, receivedOffsets.first())

        // Advance past rate limit interval
        clock.addAndGet(PlaylistDetailViewModel.MIN_APPEND_INTERVAL_MS + 1)

        // Load next page - should receive offset 6 (from first page's nextItemOffset)
        trackingViewModel.loadNextPage()
        advanceUntilIdle()

        // Verify second page received correct offset
        assertEquals("Should have called getItems twice", 2, receivedOffsets.size)
        assertEquals("Second page should receive offset 6", 6, receivedOffsets[1])
    }

    // Download State Tests

    @Test
    fun `download state updates when downloads change`() = runTest {
        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(emptyList(), null)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        // Initially no downloads
        var downloadState = viewModel.downloadUiState.value
        assertEquals(0, downloadState.downloadedCount)
        assertEquals(0, downloadState.totalCount)

        // Add a download for this playlist
        val request = DownloadRequest(
            id = "PLtest123|360p|v1",
            title = "Video 1",
            videoId = "v1",
            audioOnly = true
        )
        fakeDownloadRepository.addDownload(
            DownloadEntry(request, DownloadStatus.RUNNING, progress = 50)
        )
        advanceUntilIdle()

        downloadState = viewModel.downloadUiState.value
        assertTrue(downloadState.isDownloading)
        assertEquals(1, downloadState.totalCount)
        assertEquals(50, downloadState.progressPercent)
    }

    @Test
    fun `download state shows completed count`() = runTest {
        fakeRepository.headerResponse = createTestHeader("PLtest123", "Test Playlist")
        fakeRepository.itemsResponse = PlaylistPage(emptyList(), null)

        val viewModel = createViewModel("PLtest123")
        advanceUntilIdle()

        // Add completed downloads
        fakeDownloadRepository.addDownload(
            DownloadEntry(
                DownloadRequest("PLtest123|360p|v1", "Video 1", "v1", true),
                DownloadStatus.COMPLETED,
                progress = 100
            )
        )
        fakeDownloadRepository.addDownload(
            DownloadEntry(
                DownloadRequest("PLtest123|360p|v2", "Video 2", "v2", true),
                DownloadStatus.COMPLETED,
                progress = 100
            )
        )
        advanceUntilIdle()

        val downloadState = viewModel.downloadUiState.value
        assertEquals(2, downloadState.downloadedCount)
        assertEquals(2, downloadState.totalCount)
    }

    // Helper functions to create test data

    private fun createTestHeader(
        id: String,
        title: String,
        category: String? = null,
        excluded: Boolean = false,
        downloadPolicy: DownloadPolicy = DownloadPolicy.ENABLED
    ) = PlaylistHeader(
        id = id,
        title = title,
        thumbnailUrl = "https://example.com/thumb.jpg",
        bannerUrl = "https://example.com/banner.jpg",
        channelId = "UCtest123",
        channelName = "Test Channel",
        itemCount = 10L,
        totalDurationSeconds = 3600L,
        description = "Test playlist description",
        tags = listOf("tag1", "tag2"),
        category = category,
        excluded = excluded,
        downloadPolicy = downloadPolicy
    )

    private fun createTestItem(position: Int, videoId: String, title: String) = PlaylistItem(
        position = position,
        videoId = videoId,
        title = title,
        thumbnailUrl = "https://example.com/thumb.jpg",
        durationSeconds = 600,
        viewCount = 10000L,
        publishedTime = "1 day ago",
        channelId = "UCtest123",
        channelName = "Test Channel"
    )

    /**
     * Fake PlaylistDetailRepository for testing.
     */
    private class FakePlaylistDetailRepository : PlaylistDetailRepository {
        var headerResponse: PlaylistHeader? = null
        var headerError: Exception? = null

        var itemsResponse: PlaylistPage<PlaylistItem> = PlaylistPage(emptyList(), null)
        var itemsError: Exception? = null
        var itemsCallCount = 0

        override suspend fun getHeader(
            playlistId: String,
            forceRefresh: Boolean,
            category: String?,
            excluded: Boolean,
            downloadPolicy: DownloadPolicy
        ): PlaylistHeader {
            headerError?.let { throw it }
            return headerResponse ?: throw RuntimeException("No header response configured")
        }

        override suspend fun getItems(
            playlistId: String,
            page: Page?,
            itemOffset: Int
        ): PlaylistPage<PlaylistItem> {
            itemsCallCount++
            itemsError?.let { throw it }
            // Return items with nextItemOffset calculated from itemOffset
            return itemsResponse.copy(nextItemOffset = itemOffset + itemsResponse.items.size)
        }
    }

    /**
     * Fake DownloadRepository for testing.
     */
    private class FakeDownloadRepository : DownloadRepository {
        private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
        override val downloads: StateFlow<List<DownloadEntry>> = _downloads

        fun addDownload(entry: DownloadEntry) {
            _downloads.value = _downloads.value + entry
        }

        override fun enqueue(request: DownloadRequest) {
            // No-op for tests
        }

        override fun pause(requestId: String) {
            // No-op for tests
        }

        override fun resume(requestId: String) {
            // No-op for tests
        }

        override fun cancel(requestId: String) {
            // No-op for tests
        }

        override fun enqueuePlaylist(
            playlistId: String,
            playlistTitle: String,
            qualityLabel: String,
            items: List<PlaylistDownloadItem>,
            audioOnly: Boolean,
            targetHeight: Int?
        ): Int {
            return items.size
        }

        override fun isPlaylistDownloading(playlistId: String, qualityLabel: String): Boolean {
            return false
        }
    }
}
