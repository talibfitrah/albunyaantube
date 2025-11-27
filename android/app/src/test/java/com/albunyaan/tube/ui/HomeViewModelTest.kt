package com.albunyaan.tube.ui

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.model.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for HomeViewModel per-section loading and error handling.
 *
 * Tests verify:
 * - Independent section loading (featured, channels, playlists, videos)
 * - Error isolation (one section failure doesn't affect others)
 * - Retry functionality for individual sections
 * - Video sorting by recency
 * - Defensive take(limit) behavior
 * - Featured section mixed content handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeContentService: FakeContentService
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeContentService = FakeContentService()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        val app = RuntimeEnvironment.getApplication()
        setDeviceMode(app, Configuration.UI_MODE_TYPE_NORMAL) // Phone mode = 10 item limit
        return HomeViewModel(app, fakeContentService)
    }

    @Test
    fun `loadChannels emits Success when service returns channels`() = runTest {
        val channels = listOf(
            createChannel("1", "Channel 1"),
            createChannel("2", "Channel 2")
        )
        fakeContentService.channelsResponse = CursorResponse(channels, null)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.channelsState.value
        assertTrue("Expected Success state", state is HomeViewModel.SectionState.Success)
        assertEquals(2, (state as HomeViewModel.SectionState.Success).items.size)
    }

    @Test
    fun `loadChannels emits Error when service throws exception`() = runTest {
        fakeContentService.channelsError = RuntimeException("Network error")

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.channelsState.value
        assertTrue("Expected Error state", state is HomeViewModel.SectionState.Error)
        assertEquals("Network error", (state as HomeViewModel.SectionState.Error).message)
    }

    @Test
    fun `loadPlaylists emits Success when service returns playlists`() = runTest {
        val playlists = listOf(
            createPlaylist("1", "Playlist 1"),
            createPlaylist("2", "Playlist 2"),
            createPlaylist("3", "Playlist 3")
        )
        fakeContentService.playlistsResponse = CursorResponse(playlists, null)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.playlistsState.value
        assertTrue("Expected Success state", state is HomeViewModel.SectionState.Success)
        assertEquals(3, (state as HomeViewModel.SectionState.Success).items.size)
    }

    @Test
    fun `loadVideos sorts by uploadedDaysAgo ascending`() = runTest {
        // Videos in random order by upload date
        val videos = listOf(
            createVideo("1", "Old Video", uploadedDaysAgo = 30),
            createVideo("2", "New Video", uploadedDaysAgo = 1),
            createVideo("3", "Medium Video", uploadedDaysAgo = 15)
        )
        fakeContentService.videosResponse = CursorResponse(videos, null)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.videosState.value
        assertTrue("Expected Success state", state is HomeViewModel.SectionState.Success)

        val sortedVideos = (state as HomeViewModel.SectionState.Success).items
        assertEquals("New Video", sortedVideos[0].title)
        assertEquals("Medium Video", sortedVideos[1].title)
        assertEquals("Old Video", sortedVideos[2].title)
    }

    @Test
    fun `section failure does not affect other sections`() = runTest {
        // Channels fail, but playlists and videos succeed
        fakeContentService.channelsError = RuntimeException("Channels failed")
        fakeContentService.playlistsResponse = CursorResponse(
            listOf(createPlaylist("1", "Playlist")), null
        )
        fakeContentService.videosResponse = CursorResponse(
            listOf(createVideo("1", "Video")), null
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // Channels should be in error state
        assertTrue(viewModel.channelsState.value is HomeViewModel.SectionState.Error)

        // Playlists and videos should succeed
        assertTrue(viewModel.playlistsState.value is HomeViewModel.SectionState.Success)
        assertTrue(viewModel.videosState.value is HomeViewModel.SectionState.Success)
    }

    @Test
    fun `retry loads only the specific section`() = runTest {
        // Initially all fail
        fakeContentService.channelsError = RuntimeException("Error")
        fakeContentService.playlistsError = RuntimeException("Error")
        fakeContentService.videosError = RuntimeException("Error")

        viewModel = createViewModel()
        advanceUntilIdle()

        // All sections should be in error state
        assertTrue(viewModel.channelsState.value is HomeViewModel.SectionState.Error)
        assertTrue(viewModel.playlistsState.value is HomeViewModel.SectionState.Error)
        assertTrue(viewModel.videosState.value is HomeViewModel.SectionState.Error)

        // Fix channels service and retry only channels
        fakeContentService.channelsError = null
        fakeContentService.channelsResponse = CursorResponse(
            listOf(createChannel("1", "Channel")), null
        )

        viewModel.loadChannels()
        advanceUntilIdle()

        // Channels should now succeed
        assertTrue(viewModel.channelsState.value is HomeViewModel.SectionState.Success)

        // Playlists and videos should still be in error state
        assertTrue(viewModel.playlistsState.value is HomeViewModel.SectionState.Error)
        assertTrue(viewModel.videosState.value is HomeViewModel.SectionState.Error)
    }

    @Test
    fun `defensive take limits results to device limit`() = runTest {
        // Service returns more items than the limit
        val channels = (1..15).map { createChannel(it.toString(), "Channel $it") }
        fakeContentService.channelsResponse = CursorResponse(channels, null)

        viewModel = createViewModel() // Phone mode = 10 item limit
        advanceUntilIdle()

        val state = viewModel.channelsState.value
        assertTrue("Expected Success state", state is HomeViewModel.SectionState.Success)
        assertEquals(10, (state as HomeViewModel.SectionState.Success).items.size)
    }

    // Featured section tests

    @Test
    fun `loadFeatured emits Success with mixed content types`() = runTest {
        // Featured can contain videos, playlists, and channels
        val mixedContent: List<ContentItem> = listOf(
            createVideo("v1", "Featured Video"),
            createPlaylist("p1", "Featured Playlist"),
            createChannel("c1", "Featured Channel")
        )
        fakeContentService.featuredResponse = CursorResponse(mixedContent, null)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.featuredState.value
        assertTrue("Expected Success state", state is HomeViewModel.SectionState.Success)
        val items = (state as HomeViewModel.SectionState.Success).items
        assertEquals(3, items.size)
        assertTrue("Should contain video", items.any { it is ContentItem.Video })
        assertTrue("Should contain playlist", items.any { it is ContentItem.Playlist })
        assertTrue("Should contain channel", items.any { it is ContentItem.Channel })
    }

    @Test
    fun `loadFeatured passes Featured category ID in filters`() = runTest {
        fakeContentService.featuredResponse = CursorResponse(emptyList(), null)

        viewModel = createViewModel()
        advanceUntilIdle()

        val filters = fakeContentService.lastFeaturedFilters
        assertEquals(
            "Should use Featured category ID",
            HomeViewModel.FEATURED_CATEGORY_ID,
            filters?.category
        )
    }

    @Test
    fun `loadFeatured emits Error when service throws exception`() = runTest {
        fakeContentService.featuredError = RuntimeException("Featured failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.featuredState.value
        assertTrue("Expected Error state", state is HomeViewModel.SectionState.Error)
        assertEquals("Featured failed", (state as HomeViewModel.SectionState.Error).message)
    }

    @Test
    fun `Featured failure does not affect other sections`() = runTest {
        // Featured fails, but channels, playlists, and videos succeed
        fakeContentService.featuredError = RuntimeException("Featured failed")
        fakeContentService.channelsResponse = CursorResponse(
            listOf(createChannel("1", "Channel")), null
        )
        fakeContentService.playlistsResponse = CursorResponse(
            listOf(createPlaylist("1", "Playlist")), null
        )
        fakeContentService.videosResponse = CursorResponse(
            listOf(createVideo("1", "Video")), null
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // Featured should be in error state
        assertTrue(viewModel.featuredState.value is HomeViewModel.SectionState.Error)

        // Other sections should succeed
        assertTrue(viewModel.channelsState.value is HomeViewModel.SectionState.Success)
        assertTrue(viewModel.playlistsState.value is HomeViewModel.SectionState.Success)
        assertTrue(viewModel.videosState.value is HomeViewModel.SectionState.Success)
    }

    @Test
    fun `retry Featured loads only Featured section`() = runTest {
        // Initially Featured fails
        fakeContentService.featuredError = RuntimeException("Error")
        fakeContentService.channelsResponse = CursorResponse(
            listOf(createChannel("1", "Channel")), null
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.featuredState.value is HomeViewModel.SectionState.Error)
        assertTrue(viewModel.channelsState.value is HomeViewModel.SectionState.Success)

        // Fix Featured and retry
        fakeContentService.featuredError = null
        fakeContentService.featuredResponse = CursorResponse(
            listOf(createVideo("1", "Video")), null
        )

        viewModel.loadFeatured()
        advanceUntilIdle()

        // Featured should now succeed
        assertTrue(viewModel.featuredState.value is HomeViewModel.SectionState.Success)
    }

    // Helper functions to create test data
    private fun createChannel(id: String, name: String) = ContentItem.Channel(
        id = id,
        name = name,
        category = "Test",
        subscribers = 1000,
        description = "Test channel"
    )

    private fun createPlaylist(id: String, title: String) = ContentItem.Playlist(
        id = id,
        title = title,
        category = "Test",
        itemCount = 10,
        description = "Test playlist"
    )

    private fun createVideo(id: String, title: String, uploadedDaysAgo: Int = 1) = ContentItem.Video(
        id = id,
        title = title,
        category = "Test",
        durationSeconds = 10,
        uploadedDaysAgo = uploadedDaysAgo,
        description = "Test video"
    )

    private fun setDeviceMode(context: Context, modeType: Int) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(uiModeManager).currentModeType = modeType
    }

    /**
     * Fake ContentService for testing that allows controlling responses per content type.
     */
    private class FakeContentService : ContentService {
        var featuredResponse: CursorResponse? = CursorResponse(emptyList(), null)
        var featuredError: Exception? = null
        var lastFeaturedFilters: FilterState? = null

        var channelsResponse: CursorResponse? = CursorResponse(emptyList(), null)
        var channelsError: Exception? = null

        var playlistsResponse: CursorResponse? = CursorResponse(emptyList(), null)
        var playlistsError: Exception? = null

        var videosResponse: CursorResponse? = CursorResponse(emptyList(), null)
        var videosError: Exception? = null

        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState
        ): CursorResponse {
            return when (type) {
                ContentType.CHANNELS -> {
                    channelsError?.let { throw it }
                    channelsResponse ?: CursorResponse(emptyList(), null)
                }
                ContentType.PLAYLISTS -> {
                    playlistsError?.let { throw it }
                    playlistsResponse ?: CursorResponse(emptyList(), null)
                }
                ContentType.VIDEOS -> {
                    videosError?.let { throw it }
                    videosResponse ?: CursorResponse(emptyList(), null)
                }
                ContentType.ALL -> {
                    // Featured section uses ALL type with category filter
                    lastFeaturedFilters = filters
                    featuredError?.let { throw it }
                    featuredResponse ?: CursorResponse(emptyList(), null)
                }
                ContentType.HOME -> {
                    CursorResponse(emptyList(), null)
                }
            }
        }

        override suspend fun search(query: String, type: String?, limit: Int): List<ContentItem> {
            return emptyList()
        }

        override suspend fun fetchCategories(): List<Category> {
            return emptyList()
        }

        override suspend fun fetchSubcategories(parentId: String): List<Category> {
            return emptyList()
        }
    }
}
