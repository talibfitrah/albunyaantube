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
import com.albunyaan.tube.data.source.SearchMatch
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec §6 / §9.2: stale searches are cancelled, results never swap to a spinner, the tier reaches the UI. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Each query answers when the test completes its deferred; a cancelled call never answers. */
    private class GatedContentService : ContentService {
        val answers = mutableMapOf<String, CompletableDeferred<ContentSearchResult>>()
        val asked = mutableListOf<String>()

        override suspend fun search(query: String, type: String?, limit: Int): ContentSearchResult {
            asked.add(query)
            return answers.getOrPut(query) { CompletableDeferred() }.await()
        }

        override suspend fun fetchContent(
            type: ContentType, cursor: String?, pageSize: Int, filters: FilterState, query: String?
        ): CursorResponse = throw UnsupportedOperationException()

        override suspend fun fetchHomeFeed(
            cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?
        ): HomeFeedResult = throw UnsupportedOperationException()

        override suspend fun fetchCategories(): List<Category> = throw UnsupportedOperationException()
        override suspend fun fetchSubcategories(parentId: String): List<Category> = throw UnsupportedOperationException()
        override suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean = true
        override suspend fun isInApprovedRegistry(type: AvailabilityCheckType, youtubeId: String): Boolean = true
    }

    private fun video(id: String) = ContentItem.Video(
        id = id, title = "Video $id", category = "Test", durationSeconds = 60, uploadedDaysAgo = 1, description = ""
    )

    @Test
    fun `a slow first query is cancelled by a second so only the second result shows`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        val states = mutableListOf<SearchViewModel.SearchState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.searchResults.collect { states.add(it) } }

        vm.search("first"); advanceUntilIdle()
        vm.search("second"); advanceUntilIdle()
        service.answers.getValue("second").complete(ContentSearchResult(listOf(video("b"))))
        advanceUntilIdle()
        service.answers.getValue("first").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        assertEquals(SearchViewModel.SearchState.Success(listOf(video("b")), SearchMatch.EXACT), vm.searchResults.value)
        assertTrue("a superseded search must never show an error", states.none { it is SearchViewModel.SearchState.Error })
    }

    @Test
    fun `results stay on screen while the next query loads and the tier reaches the state`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()
        assertEquals(SearchViewModel.SearchState.Loading, vm.searchResults.value) // first search: full spinner
        assertFalse(vm.isSearching.value)
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        val states = mutableListOf<SearchViewModel.SearchState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.searchResults.collect { states.add(it) } }
        vm.search("zakit"); advanceUntilIdle()
        assertTrue(vm.isSearching.value)
        assertTrue(states.none { it is SearchViewModel.SearchState.Loading })

        service.answers.getValue("zakit").complete(ContentSearchResult(listOf(video("b")), SearchMatch.APPROXIMATE))
        advanceUntilIdle()
        assertFalse(vm.isSearching.value)
        assertEquals(SearchViewModel.SearchState.Success(listOf(video("b")), SearchMatch.APPROXIMATE), vm.searchResults.value)
    }

    @Test
    fun `clearing the field while a search is in flight keeps the history screen`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()

        vm.clearResults()
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        assertEquals(SearchViewModel.SearchState.Empty, vm.searchResults.value)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun `clearing the field while a newer search loads over results turns the progress bar off`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()
        vm.search("tafsir"); advanceUntilIdle()
        assertTrue(vm.isSearching.value)

        vm.clearResults()

        assertFalse("the bar stayed on over the history screen", vm.isSearching.value)
    }

    @Test
    fun `a query with no matches replaces the results with no results`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        vm.search("xyzzy"); advanceUntilIdle()
        service.answers.getValue("xyzzy").complete(ContentSearchResult(emptyList()))
        advanceUntilIdle()

        assertEquals(SearchViewModel.SearchState.NoResults("xyzzy"), vm.searchResults.value)
    }

    @Test
    fun `the next keystroke keeps no results on screen under the progress bar`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("xyzzy"); advanceUntilIdle()
        service.answers.getValue("xyzzy").complete(ContentSearchResult(emptyList()))
        advanceUntilIdle()
        val states = mutableListOf<SearchViewModel.SearchState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.searchResults.collect { states.add(it) } }

        vm.search("xyzzyq", debounceMs = 300); advanceUntilIdle() // in flight

        assertTrue("no results flipped to the full-screen spinner: $states", states.none { it is SearchViewModel.SearchState.Loading })
        assertTrue(vm.isSearching.value)
    }

    @Test
    fun `typing back to the query on screen drops the newer search and its progress bar`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("ab"); advanceUntilIdle()
        service.answers.getValue("ab").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()
        vm.search("abc", debounceMs = 300); advanceUntilIdle() // in flight

        vm.search("ab", debounceMs = 300) // backspace
        service.answers.getValue("abc").complete(ContentSearchResult(listOf(video("c")))) // its late answer
        advanceUntilIdle()

        assertEquals("the results on screen were fetched again", listOf("ab", "abc"), service.asked)
        assertFalse(vm.isSearching.value)
        assertEquals(SearchViewModel.SearchState.Success(listOf(video("a")), SearchMatch.EXACT), vm.searchResults.value)
    }

    @Test
    fun `a trailing space on the query on screen searches nothing`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        vm.search("quran ", debounceMs = 300); advanceUntilIdle()

        assertEquals(listOf("quran"), service.asked)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun `typing under two characters drops the search for the history screen`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("qu", debounceMs = 300); advanceUntilIdle() // in flight

        vm.search("q", debounceMs = 300) // backspace
        service.answers.getValue("qu").complete(ContentSearchResult(listOf(video("a")))) // its late answer
        advanceUntilIdle()

        assertEquals(SearchViewModel.SearchState.Empty, vm.searchResults.value)
        assertEquals(listOf("qu"), service.asked)
    }

    @Test
    fun `the restored text of a submitted one-character search keeps its results`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("q"); advanceUntilIdle() // submitted: the 2-character minimum is for typing only
        service.answers.getValue("q").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        vm.search("q", debounceMs = 300); advanceUntilIdle() // a recreated view re-sends its text

        assertEquals(SearchViewModel.SearchState.Success(listOf(video("a")), SearchMatch.EXACT), vm.searchResults.value)
        assertEquals(listOf("q"), service.asked)
    }

    // --- The typing debounce lives here, so a keystroke cancels the old search at once ---

    @Test
    fun `typing cancels the in-flight search at once and starts the next one 300 ms later`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()
        vm.search("quran tv"); advanceUntilIdle() // in flight

        vm.search("quran tafsir", debounceMs = SearchViewModel.TYPING_DEBOUNCE_MS) // the user types on, as the screen does
        service.answers.getValue("quran tv").completeExceptionally(java.io.IOException("offline")) // its late answer
        advanceTimeBy(299); runCurrent()

        assertEquals(SearchViewModel.SearchState.Success(listOf(video("a")), SearchMatch.EXACT), vm.searchResults.value)
        assertEquals("searched before the 300 ms debounce ran out", listOf("quran", "quran tv"), service.asked)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("quran", "quran tv", "quran tafsir"), service.asked)
    }

    @Test
    fun `typing the query already shown, as a restored view does, does not search again`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        vm.search("quran", debounceMs = 300); advanceUntilIdle()

        assertEquals(listOf("quran"), service.asked)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun `typing the same query after a failure searches again`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()
        service.answers.remove("quran")!!.completeExceptionally(java.io.IOException("offline"))
        advanceUntilIdle()

        vm.search("quran", debounceMs = 300); advanceUntilIdle()

        assertEquals(listOf("quran", "quran"), service.asked)
    }

    @Test
    fun `submitting the query still in its debounce searches at once, and only once`() = runTest {
        val service = GatedContentService()
        val vm = SearchViewModel(service)

        vm.search("quran", debounceMs = 300) // a history tap types the query...
        vm.search("quran") // ...and submits it
        runCurrent()
        assertEquals(listOf("quran"), service.asked)

        advanceUntilIdle()
        assertEquals(listOf("quran"), service.asked)
    }
}
