package com.albunyaan.tube.ui

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.model.HomeFeedResult
import com.albunyaan.tube.data.source.AvailabilityCheckType
import com.albunyaan.tube.data.source.ContentSearchResult
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    fun `a return to the app after idle reloads the list`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("old")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("fresh")), null))
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = ContentListViewModel(fakeService, ContentType.VIDEOS, returns)
        advanceUntilIdle()

        returns.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("fresh"), (vm.content.value as ContentListViewModel.ContentState.Success).items.map { (it as ContentItem.Video).id })
    }

    @Test
    fun `a return after idle refreshes page one in place and keeps the pages below`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("a"), createVideo("b")), CursorResponse.PageInfo("c1")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("c")), CursorResponse.PageInfo("c2")))
        // Page 1 again: "b" was excluded meanwhile, "n" is new
        fakeService.responses.add(CursorResponse(listOf(createVideo("n"), createVideo("a")), CursorResponse.PageInfo("x1")))
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = ContentListViewModel(fakeService, ContentType.VIDEOS, returns)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        returns.tryEmit(Unit)
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(listOf("n", "a", "c"), state.items.map { (it as ContentItem.Video).id })
        assertTrue(state.inPlace) // the fragment keeps its scroll place
        vm.loadMore()
        advanceUntilIdle()
        assertEquals("c2", fakeService.asked.last()) // paging goes on below the pages kept
    }

    @Test
    fun `a scroll while the return-after-idle refresh runs does not cancel it`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("a"), createVideo("b")), CursorResponse.PageInfo("c1")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("c")), CursorResponse.PageInfo("c2")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("n"), createVideo("a")), CursorResponse.PageInfo("x1")))
        fakeService.gates[2] = CompletableDeferred()
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = ContentListViewModel(fakeService, ContentType.VIDEOS, returns)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        returns.tryEmit(Unit)
        advanceUntilIdle()

        vm.loadMore() // the user scrolls near the bottom while page 1 reloads
        fakeService.gates.getValue(2).complete(Unit)
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(listOf("n", "a", "c"), state.items.map { (it as ContentItem.Video).id })
        assertEquals(3, fakeService.asked.size)
    }

    @Test
    fun `a page after a return-after-idle refresh drops rows already shown`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("a"), createVideo("b")), CursorResponse.PageInfo("c1")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("c"), createVideo("d")), CursorResponse.PageInfo("c2")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("n"), createVideo("a")), CursorResponse.PageInfo("x1")))
        // An offset cursor kept from before: "n" at the top shifted every row one down, so "d" comes again
        fakeService.responses.add(CursorResponse(listOf(createVideo("d"), createVideo("e")), null))
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = ContentListViewModel(fakeService, ContentType.VIDEOS, returns)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        returns.tryEmit(Unit)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(listOf("n", "a", "c", "d", "e"), state.items.map { (it as ContentItem.Video).id })
    }

    @Test
    fun `a list refreshed in place keeps its place, or its top so the new rows show`() {
        assertTrue(opensAtTop(listOf("a", "b"), listOf("n", "a"), inPlace = true, atTop = true))
        assertFalse(opensAtTop(listOf("a", "b"), listOf("n", "a"), inPlace = true, atTop = false))
        // A view rebuilt (back from the player, a rotation) replays the state into an empty list: it restores its place
        assertFalse(opensAtTop(emptyList(), listOf("n", "a"), inPlace = true, atTop = true))
        assertTrue(opensAtTop(listOf("a", "b"), listOf("x", "y"), inPlace = false, atTop = false)) // another query
        assertFalse(opensAtTop(listOf("a", "b"), listOf("a", "b", "c"), inPlace = false, atTop = false)) // a page below
    }

    @Test
    fun `a return after idle retries a first load that failed`() = runTest {
        // The tab shows its skeleton with no pull-to-refresh: the return is its automatic retry.
        fakeService.errorOnCall = 0
        fakeService.responses.add(CursorResponse(emptyList(), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("a")), null))
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = ContentListViewModel(fakeService, ContentType.VIDEOS, returns)
        advanceUntilIdle()
        assertTrue(vm.content.value is ContentListViewModel.ContentState.Error)

        returns.tryEmit(Unit)
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(listOf("a"), state.items.map { (it as ContentItem.Video).id })
    }

    @Test
    fun `a failed return-after-idle reload keeps the rows and their paging`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("a")), CursorResponse.PageInfo("c1")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("b")), CursorResponse.PageInfo("c2")))
        fakeService.errorOnCall = 2
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = ContentListViewModel(fakeService, ContentType.VIDEOS, returns)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        returns.tryEmit(Unit)
        advanceUntilIdle()

        val state = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(listOf("a", "b"), state.items.map { (it as ContentItem.Video).id })
        vm.loadMore()
        advanceUntilIdle()
        assertEquals("c2", fakeService.asked.last())
    }

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

    // --- Retry of a first page that failed ---

    @Test
    fun `scrolling to the end retries a first page that failed`() = runTest {
        fakeService.errorOnCall = 0 // the first page fails
        fakeService.responses.add(CursorResponse(emptyList(), null)) // (call 0 throws instead)
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), CursorResponse.PageInfo("c2"))) // the retry
        fakeService.responses.add(CursorResponse(listOf(createVideo("3")), null)) // a later return after idle: page 1 again
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = ContentListViewModel(fakeService, ContentType.VIDEOS, returns)
        advanceUntilIdle()
        assertTrue(vm.content.value is ContentListViewModel.ContentState.Error)

        assertTrue("paging is left dead after the failure", vm.canLoadMore)
        vm.loadMore(); advanceUntilIdle()

        assertEquals(null, fakeService.asked.last()) // page 1 again, not a cursor
        assertEquals(listOf(createVideo("2")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
        // the retried page counts as page 1: a return after idle swaps it, it does not keep it as a page below
        returns.tryEmit(Unit); advanceUntilIdle()
        assertEquals(listOf(createVideo("3")), vm.content.value.items)
    }

    @Test
    fun `a scroll retry of the first page that fails again keeps the Error instead of reading as an empty list`() = runTest {
        fakeService.errorOnCall = 0
        fakeService.gates[1] = CompletableDeferred<Unit>().apply { completeExceptionally(RuntimeException("still offline")) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.loadMore(); advanceUntilIdle() // the retry fails too

        val state = vm.content.value
        assertTrue("the failed retry read as $state", state is ContentListViewModel.ContentState.Error)
    }

    @Test
    fun `a page load, a refresh and a failed refresh keep the rows on screen in their state`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("c1")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), null))
        fakeService.gates[1] = CompletableDeferred() // page 2 hangs
        fakeService.gates[2] = CompletableDeferred() // so does the refresh, which then fails
        fakeService.errorOnCall = 2
        val vm = createViewModel()
        advanceUntilIdle()

        vm.loadMore(); runCurrent()
        assertEquals(ContentListViewModel.ContentState.Loading(ContentListViewModel.LoadingType.PAGINATION, listOf(createVideo("1"))), vm.content.value)
        fakeService.gates[1]!!.complete(Unit); advanceUntilIdle()

        vm.refresh(); runCurrent()
        val bothRows = listOf(createVideo("1"), createVideo("2"))
        assertEquals(ContentListViewModel.ContentState.Loading(ContentListViewModel.LoadingType.REFRESH, bothRows), vm.content.value)
        fakeService.gates[2]!!.complete(Unit); advanceUntilIdle()
        assertTrue(vm.content.value is ContentListViewModel.ContentState.Error)
        assertEquals(bothRows, vm.content.value.items)
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
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val asked = mutableListOf<String?>() // cursor of each call (fetchContent takes no query)

        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState
        ): CursorResponse {
            asked.add(cursor)
            val index = callCount++
            gates[index]?.await()
            if (errorOnCall == index) {
                throw RuntimeException("Simulated error on call $index")
            }
            return if (index < responses.size) {
                responses[index]
            } else {
                CursorResponse(emptyList(), null)
            }
        }

        override suspend fun search(query: String, type: String?, limit: Int): ContentSearchResult {
            return ContentSearchResult(emptyList())
        }

        override suspend fun fetchCategories(): List<Category> {
            return emptyList()
        }

        override suspend fun fetchSubcategories(parentId: String): List<Category> {
            return emptyList()
        }

        override suspend fun fetchHomeFeed(
            cursor: String?,
            categoryLimit: Int,
            contentLimit: Int,
            category: String?
        ): HomeFeedResult {
            return HomeFeedResult(emptyList(), null, false)
        }

        override suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean = true

        override suspend fun isInApprovedRegistry(type: AvailabilityCheckType, youtubeId: String): Boolean = true
    }
}
