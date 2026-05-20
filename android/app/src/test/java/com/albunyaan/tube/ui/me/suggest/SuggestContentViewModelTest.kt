package com.albunyaan.tube.ui.me.suggest

import com.albunyaan.tube.data.search.SearchResult
import com.albunyaan.tube.data.search.YouTubeSearchRepository
import com.albunyaan.tube.data.search.dto.SearchHitDto
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import com.albunyaan.tube.data.search.dto.YouTubeSearchResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestContentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun hit(
        id: String = "UC1",
        name: String = "Channel $id",
        contentType: String = "CHANNEL",
    ) = SearchHitDto(
        youtubeId = id,
        name = name,
        url = "https://youtube.com/channel/$id",
        thumbnailUrl = null,
        secondary = null,
        alreadyKnown = false,
        knownStatus = null,
        contentType = contentType,
    )

    private fun successPage(
        items: List<SearchHitDto> = listOf(hit()),
        nextPageToken: String? = null,
    ) = SearchResult.Success(YouTubeSearchResponseDto(items = items, nextPageToken = nextPageToken))

    // ── S1: initial state ──────────────────────────────────────────────────────

    @Test fun `initial state is Idle`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        val vm = SuggestContentViewModel(repo)

        assertTrue(vm.uiState.value is SuggestUiState.Idle)
        verify(repo, never()).search(any(), any(), anyOrNull())
    }

    // ── S2: blank query stays Idle ─────────────────────────────────────────────

    @Test fun `blank query stays Idle and makes no API call`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        val vm = SuggestContentViewModel(repo)

        vm.onQueryChange("   ")
        advanceUntilIdle()

        assertTrue(vm.uiState.value is SuggestUiState.Idle)
        verify(repo, never()).search(any(), any(), anyOrNull())
    }

    // ── S3: text query → ALL type search ─────────────────────────────────────

    @Test fun `query change after debounce calls repo with ALL type and emits Results`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("islam", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage())

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("islam")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is SuggestUiState.Results)
        val results = state as SuggestUiState.Results
        assertEquals(1, results.items.size)
        assertEquals("UC1", results.items[0].youtubeId)
        assertEquals(YouTubeContentTypeDto.ALL, results.activeFilter)
        assertEquals(YouTubeContentTypeDto.ALL, results.searchType)
        assertFalse(results.loadingMore)
        verify(repo, times(1)).search("islam", YouTubeContentTypeDto.ALL, null)
    }

    @Test fun `rapid typing only fires one search after debounce settles`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("isl", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage())

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("i")
        advanceTimeBy(100L)
        vm.onQueryChange("is")
        advanceTimeBy(100L)
        vm.onQueryChange("isl")
        advanceTimeBy(310L)
        advanceUntilIdle()

        // Only the settled value "isl" should have fired
        verify(repo, times(1)).search(any(), any(), anyOrNull())
        verify(repo, times(1)).search("isl", YouTubeContentTypeDto.ALL, null)
    }

    // ── S4: empty results → Empty ──────────────────────────────────────────────

    @Test fun `empty results page emits Empty`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(successPage(items = emptyList()))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("xyz")
        advanceTimeBy(310L)
        advanceUntilIdle()

        assertTrue(vm.uiState.value is SuggestUiState.Empty)
    }

    // ── S5: type chip changes filter locally — no new backend call ────────────

    @Test fun `type chip change filters items locally without a new backend search`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        val mixed = listOf(
            hit("UC1", contentType = "CHANNEL"),
            hit("PL1", contentType = "PLAYLIST"),
            hit("VID1", contentType = "VIDEO"),
        )
        whenever(repo.search("quran", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage(mixed))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("quran")
        advanceTimeBy(310L)
        advanceUntilIdle()

        // All 3 shown with ALL filter
        var state = vm.uiState.value as SuggestUiState.Results
        assertEquals(3, state.items.size)
        assertEquals(YouTubeContentTypeDto.ALL, state.activeFilter)

        // Switch to CHANNEL — no new API call, only local filtering
        vm.onTypeChange(YouTubeContentTypeDto.CHANNEL)

        state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.CHANNEL, state.activeFilter)
        assertEquals(1, state.items.size)
        assertEquals("UC1", state.items[0].youtubeId)

        // Still only one backend call
        verify(repo, times(1)).search(any(), any(), anyOrNull())
    }

    // ── S6: 429 RateLimited ────────────────────────────────────────────────────

    @Test fun `rate limited response emits RateLimited with retryAfterSec`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(SearchResult.RateLimited(retryAfterSec = 42L))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("test")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is SuggestUiState.RateLimited)
        assertEquals(42L, (state as SuggestUiState.RateLimited).retryAfterSec)
    }

    // ── S7: 403 Forbidden ─────────────────────────────────────────────────────

    @Test fun `forbidden response emits Error with Not allowed message`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(SearchResult.Forbidden)

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("test")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is SuggestUiState.Error)
        assertEquals("Not allowed", (state as SuggestUiState.Error).message)
    }

    // ── S8: NetworkError ──────────────────────────────────────────────────────

    @Test fun `network error emits Error with Network error message`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(SearchResult.NetworkError)

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("test")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is SuggestUiState.Error)
        assertEquals("Network error", (state as SuggestUiState.Error).message)
    }

    // ── S9: Unknown server error ──────────────────────────────────────────────

    @Test fun `unknown server error emits Error with code in message`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(SearchResult.Unknown(code = 503))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("test")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is SuggestUiState.Error)
        assertEquals("Server error 503", (state as SuggestUiState.Error).message)
    }

    // ── S10: loadMore — appends items ─────────────────────────────────────────

    @Test fun `loadMore appends new items and updates nextPageToken`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("sunnah", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage(listOf(hit("UC1")), nextPageToken = "tok1"))
        whenever(repo.search("sunnah", YouTubeContentTypeDto.ALL, "tok1"))
            .thenReturn(successPage(listOf(hit("UC2")), nextPageToken = null))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("sunnah")
        advanceTimeBy(310L)
        advanceUntilIdle()

        // First page loaded
        var state = vm.uiState.value as SuggestUiState.Results
        assertEquals(1, state.items.size)
        assertEquals("tok1", state.nextPageToken)

        vm.loadMore()
        advanceUntilIdle()

        state = vm.uiState.value as SuggestUiState.Results
        assertEquals(2, state.items.size)
        assertEquals("UC1", state.items[0].youtubeId)
        assertEquals("UC2", state.items[1].youtubeId)
        assertEquals(null, state.nextPageToken)
        assertFalse(state.loadingMore)
    }

    // ── S11: loadMore — no nextPageToken → no API call ────────────────────────

    @Test fun `loadMore without nextPageToken makes no API call`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(successPage(listOf(hit("UC1")), nextPageToken = null))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("sunnah")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val stateBefore = vm.uiState.value as SuggestUiState.Results
        assertEquals(null, stateBefore.nextPageToken)

        vm.loadMore()
        advanceUntilIdle()

        // Only the initial search, no loadMore call
        verify(repo, times(1)).search(any(), any(), anyOrNull())
        val stateAfter = vm.uiState.value as SuggestUiState.Results
        assertEquals(1, stateAfter.items.size)
    }

    // ── S12: loadMore — not on Results state → no-op ─────────────────────────

    @Test fun `loadMore on non-Results state is a no-op`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        val vm = SuggestContentViewModel(repo)

        // State is Idle — loadMore should do nothing
        vm.loadMore()
        advanceUntilIdle()

        verify(repo, never()).search(any(), any(), anyOrNull())
        assertTrue(vm.uiState.value is SuggestUiState.Idle)
    }

    // ── loadMore must pair the page token with its originating query ──

    @Test fun `loadMore uses captured Results query not latest typed value`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        // First search settled on "isl" — returns one item plus a page-2 token.
        whenever(repo.search("isl", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage(listOf(hit("UC1")), nextPageToken = "tok1"))
        // Page-2 of the original query — what loadMore SHOULD call.
        whenever(repo.search("isl", YouTubeContentTypeDto.ALL, "tok1"))
            .thenReturn(successPage(listOf(hit("UC2")), nextPageToken = null))
        // Default fallback: a new debounced search for "islam" would otherwise NPE.
        whenever(repo.search("islam", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage(listOf(hit("UC9")), nextPageToken = null))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("isl")
        advanceTimeBy(310L)
        advanceUntilIdle()
        // Page-1 of "isl" loaded; Results.query == "isl".

        // User keeps typing — query.value is now "islam" but Results state
        // still holds the "isl" page-1 (debounce hasn't fired the new search
        // yet; the user can tap load-more right now).
        vm.onQueryChange("islam")
        vm.loadMore()
        advanceUntilIdle()

        // The page-2 token is bound to "isl". loadMore MUST send "isl",
        // not "islam", or the backend returns corrupted page-2 results.
        verify(repo).search("isl", YouTubeContentTypeDto.ALL, "tok1")
        verify(repo, never()).search("islam", YouTubeContentTypeDto.ALL, "tok1")
    }
}
