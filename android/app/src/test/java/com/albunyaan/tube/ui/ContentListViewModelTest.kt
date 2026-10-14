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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

    // --- In-tab search keeps the list (spec §6.1 step 7) ---

    @Test
    fun `a query change with items on screen keeps the list (REFRESH, not INITIAL)`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), null))
        val vm = createViewModel()
        advanceUntilIdle()
        val states = mutableListOf<ContentListViewModel.ContentState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.content.collect { states.add(it) } }

        vm.setSearchQuery("ab")
        advanceUntilIdle()

        assertTrue(states.contains(ContentListViewModel.ContentState.Loading(ContentListViewModel.LoadingType.REFRESH, listOf(createVideo("1")))))
        assertFalse(states.contains(ContentListViewModel.ContentState.Loading(ContentListViewModel.LoadingType.INITIAL)))
    }

    @Test
    fun `typing again while a search is in flight shows neither the skeleton nor an error`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.gates[1] = CompletableDeferred() // the first search hangs until it is superseded
        val vm = createViewModel()
        advanceUntilIdle()
        val states = mutableListOf<ContentListViewModel.ContentState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.content.collect { states.add(it) } }

        vm.setSearchQuery("ab"); advanceUntilIdle()
        vm.setSearchQuery("abc"); advanceUntilIdle()

        assertFalse(states.contains(ContentListViewModel.ContentState.Loading(ContentListViewModel.LoadingType.INITIAL)))
        assertTrue(states.none { it is ContentListViewModel.ContentState.Error })
        assertTrue(vm.content.value is ContentListViewModel.ContentState.Success)
    }

    @Test
    fun `a pull-to-refresh cancelled by a search shows no error`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.gates[1] = CompletableDeferred() // the refresh hangs until the search supersedes it
        val vm = createViewModel()
        advanceUntilIdle()
        val states = mutableListOf<ContentListViewModel.ContentState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.content.collect { states.add(it) } }

        vm.refresh(); advanceUntilIdle()
        vm.setSearchQuery("ab"); advanceUntilIdle()

        assertTrue("the cancelled refresh emitted an Error: $states", states.none { it is ContentListViewModel.ContentState.Error })
        assertTrue(vm.content.value is ContentListViewModel.ContentState.Success)
    }

    @Test
    fun `loadMore during an in-flight search load neither cancels it nor fetches`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("c1"))) // page 1 of the tab, has more
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), CursorResponse.PageInfo("c2"))) // the search result, has more
        fakeService.gates[1] = CompletableDeferred() // the search hangs until released
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab"); advanceUntilIdle() // the search fetch is in flight
        val refreshing = ContentListViewModel.ContentState.Loading(ContentListViewModel.LoadingType.REFRESH, listOf(createVideo("1")))
        assertEquals(refreshing, vm.content.value)

        vm.loadMore(); advanceUntilIdle() // the user scrolled near the bottom of the kept list

        assertEquals("loadMore fetched while the search was loading", 2, fakeService.callCount)
        assertEquals("loadMore replaced the search's loading state", refreshing, vm.content.value)
        assertFalse(vm.canLoadMore)

        fakeService.gates[1]!!.complete(Unit); advanceUntilIdle()

        val landed = vm.content.value as ContentListViewModel.ContentState.Success
        assertEquals(listOf(createVideo("2")), landed.items) // the search was not cancelled
        assertTrue("pagination works again once the search has landed", vm.canLoadMore)
    }

    // --- Smooth in-tab typing ---

    @Test
    fun `a submitted query fetches at once`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSearchQuery("ab")
        runCurrent() // no virtual time passes

        assertEquals("the submitted query waited", 2, fakeService.callCount)
    }

    @Test
    fun `typing cancels the in-flight search at once and fetches the next query 300 ms later`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("isl")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("isla")), null))
        fakeService.gates[1] = CompletableDeferred() // "isl" is slow
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("isl"); runCurrent() // in flight

        vm.setSearchQuery("isla", debounceMs = ContentListViewModel.TYPING_DEBOUNCE_MS) // the user types on, as the tabs do
        fakeService.gates[1]!!.complete(Unit) // the late "isl" answer
        advanceTimeBy(299); runCurrent()

        assertEquals("fetched before the 300 ms debounce ran out", listOf<Pair<String?, String?>>(null to null, "isl" to null), fakeService.asked)
        assertEquals("the late answer of the query the user left landed", listOf(createVideo("1")), vm.content.value.items)
        advanceTimeBy(1); runCurrent()
        assertEquals("isla" to null, fakeService.asked.last())
        assertEquals(listOf(createVideo("isla")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
    }

    @Test
    fun `emptying the box brings the full list back at once`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab")), null))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab", debounceMs = 300); advanceUntilIdle()

        vm.setSearchQuery("", debounceMs = 300) // the clear button, through the text watcher
        runCurrent() // no virtual time passes

        assertEquals("the emptied box waited out the typing debounce", null to null, fakeService.asked.last())
        advanceUntilIdle() // past the 300 ms
        assertEquals("the emptied box loaded the list more than once", listOf<Pair<String?, String?>>(null to null, "ab" to null, null to null), fakeService.asked)
    }

    @Test
    fun `pressing Search during the typing debounce searches at once`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab", debounceMs = 300)

        vm.setSearchQuery("ab") // the keyboard's Search key, on the text still in its debounce
        runCurrent() // no virtual time passes

        assertEquals("the submit waited out the typing debounce", "ab" to null, fakeService.asked.last())
        advanceUntilIdle() // past the 300 ms
        assertEquals("the debounced search ran as well", listOf<Pair<String?, String?>>(null to null, "ab" to null), fakeService.asked)
    }

    @Test
    fun `pressing Search on the query already shown reloads nothing`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab")), null))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab", debounceMs = 300); advanceUntilIdle()

        vm.setSearchQuery("ab"); advanceUntilIdle()

        assertEquals(2, fakeService.callCount)
    }

    @Test
    fun `undoing a keystroke before its search starts keeps the rows, their pages and their place`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab1")), CursorResponse.PageInfo("c1")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab2")), CursorResponse.PageInfo("c2")))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab", debounceMs = 300); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle() // the user scrolled to page 2
        val shown = vm.content.value

        vm.setSearchQuery("abc", debounceMs = 300); advanceTimeBy(100)
        vm.setSearchQuery("ab", debounceMs = 300); advanceUntilIdle() // backspace

        assertEquals("the rows on screen were fetched again", 3, fakeService.callCount)
        assertEquals(shown, vm.content.value)
        vm.loadMore(); runCurrent()
        assertEquals("paging lost its place", "ab" to "c2", fakeService.asked.last())
    }

    @Test
    fun `undoing a keystroke while its search loads drops that search and keeps the rows`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("abc")), null))
        fakeService.gates[2] = CompletableDeferred() // "abc" is slow
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab", debounceMs = 300); advanceUntilIdle()
        vm.setSearchQuery("abc", debounceMs = 300); advanceUntilIdle() // in flight under the spinner

        vm.setSearchQuery("ab ", debounceMs = 300) // backspace (a trailing space is the same query)
        fakeService.gates[2]!!.complete(Unit) // its late answer
        advanceUntilIdle()

        assertEquals("the rows on screen were fetched again", 3, fakeService.callCount)
        assertEquals(ContentListViewModel.ContentState.Success(listOf(createVideo("ab")), hasMoreData = false, isSearchActive = true), vm.content.value)
    }

    @Test
    fun `re-sending the query being loaded or shown fetches nothing more`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab")), null))
        fakeService.gates[1] = CompletableDeferred()
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab"); runCurrent() // in flight

        vm.setSearchQuery("ab", debounceMs = 300); advanceUntilIdle() // a restored view re-sends its text while loading
        fakeService.gates[1]!!.complete(Unit); advanceUntilIdle()
        vm.setSearchQuery("ab", debounceMs = 300); advanceUntilIdle() // ...and once it is shown

        assertEquals(2, fakeService.callCount)
        assertEquals(listOf(createVideo("ab")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
    }

    @Test
    fun `typing back to the query on screen while the next one loads ends on its rows, not a stuck spinner`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("isl")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("isla")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("isl")), null))
        fakeService.gates[2] = CompletableDeferred() // "isla" hangs until it is superseded
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("isl", debounceMs = 300); advanceUntilIdle()
        vm.setSearchQuery("isla", debounceMs = 300); advanceUntilIdle() // in flight under the spinner

        vm.setSearchQuery("isl", debounceMs = 300); advanceUntilIdle()

        assertEquals(listOf(createVideo("isl")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
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

    @Test
    fun `after a failed search, scrolling to the end retries it`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cA")))
        fakeService.errorOnCall = 1 // the search fails
        fakeService.responses.add(CursorResponse(emptyList(), null)) // (call 1 throws instead)
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), null)) // the retry
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab"); advanceUntilIdle()
        assertTrue(vm.content.value is ContentListViewModel.ContentState.Error)

        assertTrue("paging is left dead after the failure", vm.canLoadMore)
        vm.loadMore(); advanceUntilIdle()

        assertEquals("ab" to null, fakeService.asked.last()) // page 1 of the failed query
        assertEquals(listOf(createVideo("2")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
    }

    @Test
    fun `a scroll retry that fails again keeps the list up instead of reading as no results`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cA")))
        fakeService.errorOnCall = 1 // the search fails
        fakeService.gates[2] = CompletableDeferred<Unit>().apply { completeExceptionally(RuntimeException("still offline")) }
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab"); advanceUntilIdle()

        vm.loadMore(); advanceUntilIdle() // the retry fails too

        val state = vm.content.value
        assertTrue("the failed retry read as $state", state is ContentListViewModel.ContentState.Error)
        assertEquals(listOf(createVideo("1")), state.items)
    }

    @Test
    fun `submitting the same query again after a failed search reloads it`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.errorOnCall = 1
        fakeService.responses.add(CursorResponse(emptyList(), null)) // (call 1 throws instead)
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), null))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab"); advanceUntilIdle()

        vm.setSearchQuery("ab"); advanceUntilIdle()

        assertEquals("the same query was not tried again", 3, fakeService.callCount)
        assertEquals(listOf(createVideo("2")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
    }

    @Test
    fun `typing after a failed search keeps the list it left on screen`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.errorOnCall = 1 // "ab" fails; the fragment keeps row 1 on screen
        fakeService.responses.add(CursorResponse(emptyList(), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), null))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab"); advanceUntilIdle()
        val states = mutableListOf<ContentListViewModel.ContentState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.content.collect { states.add(it) } }

        vm.setSearchQuery("abc"); advanceUntilIdle()

        val loadingTypes = states.filterIsInstance<ContentListViewModel.ContentState.Loading>().map { it.type }
        assertEquals("the kept list was swapped for the skeleton", listOf(ContentListViewModel.LoadingType.REFRESH), loadingTypes)
    }

    @Test
    fun `a loadMore right after a query change never pairs the new query with the old cursor`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cA")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("2")), null))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSearchQuery("ab")
        vm.loadMore() // the kept list was scrolled to its end
        advanceUntilIdle()

        assertEquals(listOf<Pair<String?, String?>>(null to null, "ab" to null), fakeService.asked)
    }

    @Test
    fun `a search that superseded a loadMore is itself cancelled by the next query`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), CursorResponse.PageInfo("cA")))
        fakeService.responses.add(CursorResponse(listOf(createVideo("page2")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("abc")), null))
        fakeService.gates[1] = CompletableDeferred() // page 2 hangs
        fakeService.gates[2] = CompletableDeferred() // so does "ab"
        val vm = createViewModel()
        advanceUntilIdle()

        vm.loadMore(); advanceUntilIdle()
        vm.setSearchQuery("ab"); advanceUntilIdle()
        vm.setSearchQuery("abc"); advanceUntilIdle()
        fakeService.gates[2]!!.complete(Unit); advanceUntilIdle() // a late "ab" answer

        assertEquals(listOf(createVideo("abc")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
    }

    @Test
    fun `a list-keeping load and its failure carry the rows on screen so a recreated view can redraw them`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.gates[1] = CompletableDeferred()
        fakeService.errorOnCall = 1
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSearchQuery("ab"); advanceUntilIdle()
        assertEquals(listOf(createVideo("1")), vm.content.value.items) // Loading(REFRESH)

        fakeService.gates[1]!!.complete(Unit); advanceUntilIdle()
        assertTrue(vm.content.value is ContentListViewModel.ContentState.Error)
        assertEquals(listOf(createVideo("1")), vm.content.value.items)
    }

    @Test
    fun `after a filter change fails, undoing a keystroke does not bring back the old filter's rows`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("a1")), CursorResponse.PageInfo("cA"))) // filter A
        fakeService.errorOnCall = 1 // filter B fails (offline): A's rows stay up with an error
        fakeService.responses.add(CursorResponse(emptyList(), null)) // (call 1 throws instead)
        fakeService.responses.add(CursorResponse(emptyList(), null)) // (call 2, "x", is gated)
        fakeService.responses.add(CursorResponse(listOf(createVideo("b1")), null)) // filter B
        fakeService.gates[2] = CompletableDeferred() // "x" loads under the spinner, over A's rows
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setFilters(FilterState(category = "b")); advanceUntilIdle()

        vm.setSearchQuery("x", debounceMs = 300); advanceUntilIdle()
        vm.setSearchQuery("", debounceMs = 300); advanceUntilIdle() // backspace

        assertEquals("filter A's rows came back as filter B's result", listOf(createVideo("b1")), vm.content.value.items)
        assertEquals(null to null, fakeService.asked.last())
    }

    @Test
    fun `pressing Search on the typed query already loading does not send it again`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("1")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("ab")), null))
        fakeService.gates[1] = CompletableDeferred() // "ab" is slow
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSearchQuery("ab", debounceMs = ContentListViewModel.TYPING_DEBOUNCE_MS)
        advanceTimeBy(ContentListViewModel.TYPING_DEBOUNCE_MS); runCurrent() // past the debounce: "ab" is in flight
        assertEquals(2, fakeService.callCount)

        vm.setSearchQuery("ab") // the keyboard's Search key
        fakeService.gates[1]!!.complete(Unit); advanceUntilIdle()

        assertEquals("the search in flight was sent again", 2, fakeService.callCount)
        assertEquals(listOf(createVideo("ab")), (vm.content.value as ContentListViewModel.ContentState.Success).items)
    }

    @Test
    fun `typing a character and undoing it does not drop a pull-to-refresh`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("old")), null))
        fakeService.responses.add(CursorResponse(emptyList(), null)) // (call 1, the refresh, never answers)
        fakeService.responses.add(CursorResponse(listOf(createVideo("new")), null))
        fakeService.gates[1] = CompletableDeferred()
        val vm = createViewModel()
        advanceUntilIdle()
        vm.refresh(); runCurrent() // the user pulled to refresh

        vm.setSearchQuery("a", debounceMs = 300); advanceTimeBy(100)
        vm.setSearchQuery("", debounceMs = 300); advanceUntilIdle() // and undid it

        assertEquals("the refresh was dropped", listOf(createVideo("new")), vm.content.value.items)
    }

    @Test
    fun `pressing Search on the empty box during a pull-to-refresh does not send it again`() = runTest {
        fakeService.responses.add(CursorResponse(listOf(createVideo("old")), null))
        fakeService.responses.add(CursorResponse(listOf(createVideo("new")), null))
        fakeService.gates[1] = CompletableDeferred() // the refresh is slow
        val vm = createViewModel()
        advanceUntilIdle()
        vm.refresh(); runCurrent()

        vm.setSearchQuery("") // the keyboard's Search key on the empty box
        fakeService.gates[1]!!.complete(Unit); advanceUntilIdle()

        assertEquals("the refresh was sent again", 2, fakeService.callCount)
        assertEquals(listOf(createVideo("new")), vm.content.value.items)
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
        val asked = mutableListOf<Pair<String?, String?>>() // (query, cursor) of each call

        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState,
            query: String?
        ): CursorResponse {
            asked.add(query to cursor)
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
