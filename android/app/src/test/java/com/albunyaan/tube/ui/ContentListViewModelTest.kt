package com.albunyaan.tube.ui

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ContentListViewModel pagination behavior.
 *
 * Tests verify:
 * - Initial load populates items and pagination state
 * - loadMore appends items and advances cursor
 * - loadMore on error preserves existing items with paginationError set
 * - hasMoreData stays true on pagination error (enabling retry)
 * - canLoadMore guards prevent concurrent/spam requests
 * - Refresh resets pagination state
 * - Empty page (no more data) terminates pagination
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeService: FakeContentService

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeService = FakeContentService()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(type: ContentType = ContentType.VIDEOS): ContentListViewModel {
        return ContentListViewModel(fakeService, type)
    }

    // --- Initial load tests ---

    @Test
    fun `initial load emits Success with items and hasMoreData when cursor present`() = runTest {
        val videos = listOf(createVideo("1"), createVideo("2"))
        fakeService.responses.add(CursorResponse(videos, CursorResponse.PageInfo("cursor1")))

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.content.value
        assertTrue(state is ContentListViewModel.ContentState.Success)
        val success = state as ContentListViewModel.ContentState.Success
        assertEquals(2, success.items.size)
        assertTrue(success.hasMoreData)
        assertNull(success.paginationError)
    }

    @Test
    fun `initial load emits Success with hasMoreData false when no cursor`() = runTest {
        val videos = listOf(createVideo("1"))
        fakeService.responses.add(CursorResponse(videos, null))

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertFalse(state.hasMoreData)
    }

    @Test
    fun `initial load error emits Error state`() = runTest {
        fakeService.errorOnCall = 0

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.content.value
        assertTrue(state is ContentListViewModel.ContentState.Error)
    }

    // --- Pagination (loadMore) tests ---

    @Test
    fun `loadMore appends items and advances cursor`() = runTest {
        // Page 1
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1"), createVideo("2")), CursorResponse.PageInfo("cursor1"))
        )
        // Page 2
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("3"), createVideo("4")), CursorResponse.PageInfo("cursor2"))
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(4, state.items.size)
        assertTrue(state.hasMoreData)
        assertNull(state.paginationError)
    }

    @Test
    fun `loadMore with no more cursor sets hasMoreData false`() = runTest {
        // Page 1 with cursor
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cursor1"))
        )
        // Page 2 with no cursor (last page)
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("2")), null)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(2, state.items.size)
        assertFalse(state.hasMoreData)
    }

    @Test
    fun `loadMore error preserves items and sets paginationError`() = runTest {
        // Page 1 succeeds
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1"), createVideo("2")), CursorResponse.PageInfo("cursor1"))
        )
        // Page 2 will fail
        fakeService.errorOnCall = 1

        val vm = createViewModel()
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        // Existing items preserved
        assertEquals(2, state.items.size)
        // paginationError is set
        assertNotNull(state.paginationError)
        // hasMoreData stays true (allows manual retry)
        assertTrue(state.hasMoreData)
    }

    @Test
    fun `loadMore skipped when hasMoreData is false`() = runTest {
        // Single page with no cursor
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))

        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.canLoadMore)

        // This should be a no-op
        vm.loadMore()
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(1, state.items.size)
        // Only 1 call was made (initial load), not 2
        assertEquals(1, fakeService.callCount)
    }

    @Test
    fun `canLoadMore is false during loadMore`() = runTest {
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cursor1"))
        )
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("2")), null)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.canLoadMore)

        // Start loadMore but don't advance
        vm.loadMore()
        assertFalse(vm.canLoadMore)

        advanceUntilIdle()
        // After completing, canLoadMore is false because no more data
        assertFalse(vm.canLoadMore)
    }

    // --- Refresh tests ---

    @Test
    fun `refresh resets pagination and reloads from beginning`() = runTest {
        // Initial load
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cursor1"))
        )
        // loadMore
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("2")), CursorResponse.PageInfo("cursor2"))
        )
        // Refresh response (fresh data)
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("new1"), createVideo("new2")), CursorResponse.PageInfo("newCursor"))
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        // Should have 2 items accumulated
        assertEquals(2, (vm.content.value as ContentListViewModel.ContentState.Success).items.size)

        vm.refresh()
        advanceUntilIdle()

        // Should have fresh data only
        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(2, state.items.size)
        assertEquals("new1", (state.items[0] as ContentItem.Video).id)
        assertTrue(state.hasMoreData)
        assertNull(state.paginationError)
    }

    @Test
    fun `canLoadMore is false during refresh`() = runTest {
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cursor1"))
        )
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("2")), null)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.refresh()
        // During refresh, canLoadMore should be false
        assertFalse(vm.canLoadMore)

        advanceUntilIdle()
    }

    @Test
    fun `multiple rapid loadMore calls are deduplicated`() = runTest {
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cursor1"))
        )
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("2")), CursorResponse.PageInfo("cursor2"))
        )

        val vm = createViewModel()
        advanceUntilIdle()

        // Rapid-fire multiple loadMore calls
        vm.loadMore()
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()

        // Only page 1 + 1 loadMore = 2 calls total
        assertEquals(2, fakeService.callCount)
    }

    // --- Retry after error tests ---

    @Test
    fun `loadMore succeeds after previous pagination error`() = runTest {
        // Call 0 (initial): Page 1 succeeds
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cursor1"))
        )
        // Call 1 (loadMore): will fail - placeholder response (skipped due to error)
        fakeService.errorOnCall = 1
        fakeService.responses.add(CursorResponse(emptyList(), null))
        // Call 2 (retry): Page 2 succeeds
        fakeService.responses.add(
            CursorResponse(listOf(createVideo("2")), null)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        // First loadMore fails
        vm.loadMore()
        advanceUntilIdle()

        val errorState = vm.content.value as ContentListViewModel.ContentState.Success
        assertNotNull(errorState.paginationError)
        assertEquals(1, errorState.items.size)

        // Retry loadMore succeeds (errorOnCall=1, but this is call index 2)
        vm.loadMore()
        advanceUntilIdle()

        val retryState = vm.content.value as ContentListViewModel.ContentState.Success
        assertNull(retryState.paginationError)
        assertEquals(2, retryState.items.size)
        assertFalse(retryState.hasMoreData)
    }

    // --- Helper functions ---

    private fun createVideo(id: String) = ContentItem.Video(
        id = id,
        title = "Video $id",
        category = "Test",
        durationSeconds = 60,
        uploadedDaysAgo = 1,
        description = "Test video $id"
    )

    /**
     * Fake ContentService that returns responses from a queue.
     * Supports triggering errors on specific call indices.
     */
    private class FakeContentService : ContentService {
        val responses = mutableListOf<CursorResponse>()
        var errorOnCall: Int? = null
        var callCount = 0

        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState
        ): CursorResponse {
            val index = callCount++
            if (errorOnCall == index) {
                throw RuntimeException("Simulated error on call $index")
            }
            return if (index < responses.size) {
                responses[index]
            } else {
                CursorResponse(emptyList(), null)
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
