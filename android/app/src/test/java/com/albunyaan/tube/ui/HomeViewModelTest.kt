package com.albunyaan.tube.ui

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.model.HomeFeedResult
import com.albunyaan.tube.data.model.HomeSection
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.source.AvailabilityCheckType
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
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
 * Unit tests for HomeViewModel (category-section-based home feed).
 *
 * Tests verify:
 * - Initial feed loading populates sections
 * - Error handling on initial load
 * - Load-more pagination appends sections
 * - Empty state when no sections returned
 * - Refresh resets and reloads
 * - Defensive content limit based on device type
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeContentService: FakeContentService
    private lateinit var filterManager: FilterManager
    private lateinit var viewModel: HomeViewModel
    private lateinit var tempPrefsFile: File

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeContentService = FakeContentService()
        tempPrefsFile = File.createTempFile("test_prefs", ".preferences_pb")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempPrefsFile }
        )
        filterManager = FilterManager(dataStore, testScope)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempPrefsFile.delete()
    }

    private fun createViewModel(): HomeViewModel {
        val app = RuntimeEnvironment.getApplication()
        setDeviceMode(app, Configuration.UI_MODE_TYPE_NORMAL) // Phone mode = 10 item limit
        return HomeViewModel(app, fakeContentService, filterManager)
    }

    @Test
    fun `initial load emits Success with sections`() = runTest {
        val sections = listOf(
            createSection("cat1", "Category 1", 3),
            createSection("cat2", "Category 2", 2)
        )
        fakeContentService.homeFeedResponses.add(HomeFeedResult(sections, null, false))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.homeState.value
        assertTrue("Expected Success state", state is HomeViewModel.HomeState.Success)
        assertEquals(2, (state as HomeViewModel.HomeState.Success).sections.size)
    }

    @Test
    fun `initial load emits Error when service throws exception`() = runTest {
        fakeContentService.homeFeedError = RuntimeException("Network error")

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.homeState.value
        assertTrue("Expected Error state", state is HomeViewModel.HomeState.Error)
        assertEquals("Network error", (state as HomeViewModel.HomeState.Error).message)
    }

    @Test
    fun `initial load emits Empty when no sections returned`() = runTest {
        fakeContentService.homeFeedResponses.add(HomeFeedResult(emptyList(), null, false))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.homeState.value
        assertTrue("Expected Empty state", state is HomeViewModel.HomeState.Empty)
    }

    @Test
    fun `loadMore appends sections and updates cursor`() = runTest {
        // Initial page
        val page1 = listOf(createSection("cat1", "Category 1", 3))
        fakeContentService.homeFeedResponses.add(HomeFeedResult(page1, "cursor1", true))
        // Page 2
        val page2 = listOf(createSection("cat2", "Category 2", 2))
        fakeContentService.homeFeedResponses.add(HomeFeedResult(page2, null, false))

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.canLoadMore)

        viewModel.loadMoreSections()
        advanceUntilIdle()

        val state = viewModel.homeState.value as HomeViewModel.HomeState.Success
        assertEquals(2, state.sections.size)
        assertEquals("Category 1", state.sections[0].categoryName)
        assertEquals("Category 2", state.sections[1].categoryName)
    }

    @Test
    fun `refresh resets and reloads from beginning`() = runTest {
        // Initial load
        fakeContentService.homeFeedResponses.add(
            HomeFeedResult(listOf(createSection("cat1", "Old", 1)), "cursor1", true)
        )
        // Load more
        fakeContentService.homeFeedResponses.add(
            HomeFeedResult(listOf(createSection("cat2", "Old2", 1)), null, false)
        )
        // Refresh response
        fakeContentService.homeFeedResponses.add(
            HomeFeedResult(listOf(createSection("catNew", "Fresh", 2)), null, false)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadMoreSections()
        advanceUntilIdle()

        assertEquals(2, (viewModel.homeState.value as HomeViewModel.HomeState.Success).sections.size)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.homeState.value as HomeViewModel.HomeState.Success
        assertEquals(1, state.sections.size)
        assertEquals("Fresh", state.sections[0].categoryName)
    }

    @Test
    fun `loadMore error preserves existing sections`() = runTest {
        // Initial load succeeds
        fakeContentService.homeFeedResponses.add(
            HomeFeedResult(listOf(createSection("cat1", "Category 1", 2)), "cursor1", true)
        )
        // Load more will fail
        fakeContentService.homeFeedErrorOnCall = 1

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadMoreSections()
        advanceUntilIdle()

        // Should still show existing sections
        val state = viewModel.homeState.value as HomeViewModel.HomeState.Success
        assertEquals(1, state.sections.size)
    }

    @Test
    fun `phone content limit is 10`() = runTest {
        fakeContentService.homeFeedResponses.add(
            HomeFeedResult(listOf(createSection("cat1", "Category 1", 3)), null, false)
        )

        val app = RuntimeEnvironment.getApplication()
        setDeviceMode(app, Configuration.UI_MODE_TYPE_NORMAL)
        viewModel = HomeViewModel(app, fakeContentService, filterManager)
        advanceUntilIdle()

        assertEquals(10, fakeContentService.lastContentLimit)
    }

    @Test
    fun `TV content limit is 20`() = runTest {
        fakeContentService.homeFeedResponses.add(
            HomeFeedResult(listOf(createSection("cat1", "Category 1", 3)), null, false)
        )

        val app = RuntimeEnvironment.getApplication()
        setDeviceMode(app, Configuration.UI_MODE_TYPE_TELEVISION)
        viewModel = HomeViewModel(app, fakeContentService, filterManager)
        advanceUntilIdle()

        assertEquals(20, fakeContentService.lastContentLimit)
    }

    // Helper functions

    private fun createSection(id: String, name: String, itemCount: Int): HomeSection {
        val items = (1..itemCount).map { i ->
            ContentItem.Video(
                id = "${id}_v$i",
                title = "Video $i",
                category = name,
                durationSeconds = 60,
                uploadedDaysAgo = 1,
                description = "Test"
            )
        }
        return HomeSection(
            categoryId = id,
            categoryName = name,
            items = items,
            totalItemCount = itemCount
        )
    }

    private fun setDeviceMode(context: Context, modeType: Int) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(uiModeManager).currentModeType = modeType
    }

    /**
     * Fake ContentService for testing the home feed flow.
     */
    private class FakeContentService : ContentService {
        val homeFeedResponses = mutableListOf<HomeFeedResult>()
        var homeFeedError: Exception? = null
        var homeFeedErrorOnCall: Int? = null
        var lastContentLimit: Int? = null
        private var homeFeedCallCount = 0

        override suspend fun fetchHomeFeed(
            cursor: String?,
            categoryLimit: Int,
            contentLimit: Int,
            category: String?
        ): HomeFeedResult {
            lastContentLimit = contentLimit
            val callIndex = homeFeedCallCount++
            if (homeFeedErrorOnCall == callIndex) {
                throw RuntimeException("Simulated error on call $callIndex")
            }
            homeFeedError?.let { throw it }
            return if (callIndex < homeFeedResponses.size) {
                homeFeedResponses[callIndex]
            } else {
                HomeFeedResult(emptyList(), null, false)
            }
        }

        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState,
            query: String?
        ): CursorResponse {
            return CursorResponse(emptyList(), null)
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

        override suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean = true

        override suspend fun isInApprovedRegistry(type: AvailabilityCheckType, youtubeId: String): Boolean = true
    }
}
