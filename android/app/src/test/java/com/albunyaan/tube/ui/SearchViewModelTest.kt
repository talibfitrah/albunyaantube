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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

        override suspend fun search(query: String, type: String?, limit: Int): ContentSearchResult =
            answers.getOrPut(query) { CompletableDeferred() }.await()

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
        // Review Focus 4
        val service = GatedContentService()
        val vm = SearchViewModel(service)
        vm.search("quran"); advanceUntilIdle()

        vm.clearResults()
        service.answers.getValue("quran").complete(ContentSearchResult(listOf(video("a"))))
        advanceUntilIdle()

        assertEquals(SearchViewModel.SearchState.Empty, vm.searchResults.value)
        assertFalse(vm.isSearching.value)
    }
}
