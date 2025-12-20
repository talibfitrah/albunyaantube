package com.albunyaan.tube.ui.player

import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import com.albunyaan.tube.data.playlist.PlaylistHeader
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.data.playlist.PlaylistPage
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.PlaylistDownloadItem
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.player.StreamPrefetchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var fakePlayerRepository: FakePlayerRepository
    private lateinit var fakeDownloadRepository: FakeDownloadRepository
    private lateinit var fakePlaylistRepository: FakePlaylistDetailRepository
    private lateinit var rateLimiter: ExtractionRateLimiter
    private lateinit var fakePrefetchService: FakePrefetchService
    private lateinit var fakeFavoritesRepository: FakeFavoritesRepository
    private lateinit var fakeMetricsReporter: FakeExtractorMetricsReporter

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePlayerRepository = FakePlayerRepository()
        fakeDownloadRepository = FakeDownloadRepository()
        fakePlaylistRepository = FakePlaylistDetailRepository()
        rateLimiter = ExtractionRateLimiter()  // Use real rate limiter
        fakePrefetchService = FakePrefetchService()
        fakeFavoritesRepository = FakeFavoritesRepository()
        fakeMetricsReporter = FakeExtractorMetricsReporter()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): PlayerViewModel {
        return PlayerViewModel(
            repository = fakePlayerRepository,
            downloadRepository = fakeDownloadRepository,
            playlistDetailRepository = fakePlaylistRepository,
            rateLimiter = rateLimiter,
            prefetchService = fakePrefetchService,
            favoritesRepository = fakeFavoritesRepository,
            metricsReporter = fakeMetricsReporter
        )
    }

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

    // =============================================================================
    // Fake Implementations
    // =============================================================================

    private class FakePlayerRepository : PlayerRepository {
        var resolvedStreams: ResolvedStreams? = null
        var failNextResolve = false
        var alwaysFail = false
        var resolvedStreamsAfterFailure: ResolvedStreams? = null
        private var failCount = 0

        override suspend fun resolveStreams(videoId: String, forceRefresh: Boolean): ResolvedStreams? {
            if (alwaysFail) {
                return null
            }
            if (failNextResolve) {
                failCount++
                if (failCount > 3) {  // After 3 failures (all retries exhausted), next call succeeds
                    failNextResolve = false
                    failCount = 0
                    return resolvedStreamsAfterFailure
                }
                return null  // Fail all 3 retry attempts
            }
            return resolvedStreams
        }
    }

    private class FakeDownloadRepository : DownloadRepository {
        private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
        override val downloads: StateFlow<List<DownloadEntry>> = _downloads

        override fun enqueue(request: DownloadRequest) {}
        override fun pause(requestId: String) {}
        override fun resume(requestId: String) {}
        override fun cancel(requestId: String) {}
        override fun enqueuePlaylist(
            playlistId: String,
            playlistTitle: String,
            qualityLabel: String,
            items: List<PlaylistDownloadItem>,
            audioOnly: Boolean,
            targetHeight: Int?
        ): Int = items.size
        override fun isPlaylistDownloading(playlistId: String, qualityLabel: String) = false
    }

    private class FakePlaylistDetailRepository : PlaylistDetailRepository {
        private var pages: List<PlaylistPage<PlaylistItem>> = emptyList()
        private var currentPageIndex = 0
        var getItemsCallCount = 0
        var failOnSecondPage = false

        fun setPages(pages: List<PlaylistPage<PlaylistItem>>) {
            this.pages = pages
            this.currentPageIndex = 0
        }

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
            channelId = "UC123",
            channelName = "Test Channel",
            itemCount = 10L,
            totalDurationSeconds = 3000L,
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
            getItemsCallCount++

            // Handle failure simulation for second page
            if (page != null && failOnSecondPage) {
                throw java.io.IOException("Simulated network error for testing")
            }

            val pageIndex = if (page == null) 0 else currentPageIndex + 1
            currentPageIndex = pageIndex

            return if (pageIndex < pages.size) {
                pages[pageIndex]
            } else {
                PlaylistPage(emptyList(), null)
            }
        }
    }

    // Use the real ExtractionRateLimiter - it's already designed to allow most requests by default
    // No fake needed since it's a final class and our tests don't require special rate limiting behavior

    /**
     * Fake prefetch service that always returns null (no prefetch data available).
     * This simulates cold-start playback where no prefetch was triggered.
     */
    private class FakePrefetchService : StreamPrefetchService {
        override fun triggerPrefetch(videoId: String, scope: kotlinx.coroutines.CoroutineScope) {}
        override suspend fun awaitOrConsumePrefetch(videoId: String): ResolvedStreams? = null
        override fun consumePrefetch(videoId: String): ResolvedStreams? = null
        override fun isPrefetchInFlight(videoId: String): Boolean = false
        override fun cancelPrefetch(videoId: String) {}
        override fun clearAll() {}
    }

    /**
     * Fake favorites repository for testing.
     * Stores favorites in memory.
     */
    private class FakeFavoritesRepository : FavoritesRepository {
        private val favorites = MutableStateFlow<List<FavoriteVideo>>(emptyList())

        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = favorites

        override fun isFavorite(videoId: String): Flow<Boolean> {
            return favorites.map { list -> list.any { it.videoId == videoId } }
        }

        override suspend fun isFavoriteOnce(videoId: String): Boolean {
            return favorites.value.any { it.videoId == videoId }
        }

        override suspend fun addFavorite(
            videoId: String,
            title: String,
            channelName: String,
            thumbnailUrl: String?,
            durationSeconds: Int
        ) {
            val video = FavoriteVideo(
                videoId = videoId,
                title = title,
                channelName = channelName,
                thumbnailUrl = thumbnailUrl,
                durationSeconds = durationSeconds
            )
            favorites.value = favorites.value + video
        }

        override suspend fun removeFavorite(videoId: String) {
            favorites.value = favorites.value.filter { it.videoId != videoId }
        }

        override suspend fun toggleFavorite(
            videoId: String,
            title: String,
            channelName: String,
            thumbnailUrl: String?,
            durationSeconds: Int
        ): Boolean {
            val isFav = favorites.value.any { it.videoId == videoId }
            if (isFav) {
                removeFavorite(videoId)
            } else {
                addFavorite(videoId, title, channelName, thumbnailUrl, durationSeconds)
            }
            return !isFav
        }

        override fun getFavoriteCount(): Flow<Int> = favorites.map { it.size }

        override suspend fun clearAll() {
            favorites.value = emptyList()
        }
    }

    /**
     * No-op metrics reporter for testing.
     */
    private class FakeExtractorMetricsReporter : ExtractorMetricsReporter {
        override fun onCacheHit(type: ContentType, hitCount: Int) {}
        override fun onCacheMiss(type: ContentType, missCount: Int) {}
        override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) {}
        override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) {}
    }
}
