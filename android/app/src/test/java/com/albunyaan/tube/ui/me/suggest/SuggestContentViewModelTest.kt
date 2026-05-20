package com.albunyaan.tube.ui.me.suggest

import com.albunyaan.tube.R
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
import org.mockito.kotlin.eq
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

    // ── URL parsing ────────────────────────────────────────────────────────────

    @Test fun `youtu-be short URL extracts video ID`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("dQw4w9WgXcQ", YouTubeContentTypeDto.VIDEO, null))
            .thenReturn(successPage(listOf(hit("dQw4w9WgXcQ", contentType = "VIDEO"))))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("https://youtu.be/dQw4w9WgXcQ")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.VIDEO, state.searchType)
        verify(repo).search("dQw4w9WgXcQ", YouTubeContentTypeDto.VIDEO, null)
    }

    @Test fun `youtube watch URL extracts video ID`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("abc123", YouTubeContentTypeDto.VIDEO, null))
            .thenReturn(successPage(listOf(hit("abc123", contentType = "VIDEO"))))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("https://www.youtube.com/watch?v=abc123")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.VIDEO, state.searchType)
        verify(repo).search("abc123", YouTubeContentTypeDto.VIDEO, null)
    }

    @Test fun `youtube playlist URL extracts playlist ID`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("PLxxx", YouTubeContentTypeDto.PLAYLIST, null))
            .thenReturn(successPage(listOf(hit("PLxxx", contentType = "PLAYLIST"))))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("https://www.youtube.com/playlist?list=PLxxx")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.PLAYLIST, state.searchType)
        verify(repo).search("PLxxx", YouTubeContentTypeDto.PLAYLIST, null)
    }

    @Test fun `youtube channel URL extracts channel ID`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("UCq2abc", YouTubeContentTypeDto.CHANNEL, null))
            .thenReturn(successPage(listOf(hit("UCq2abc", contentType = "CHANNEL"))))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("https://www.youtube.com/channel/UCq2abc")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.CHANNEL, state.searchType)
        verify(repo).search("UCq2abc", YouTubeContentTypeDto.CHANNEL, null)
    }

    @Test fun `youtube shorts URL extracts video ID`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("shortsId1", YouTubeContentTypeDto.VIDEO, null))
            .thenReturn(successPage(listOf(hit("shortsId1", contentType = "VIDEO"))))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("https://www.youtube.com/shorts/shortsId1")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.VIDEO, state.searchType)
        verify(repo).search("shortsId1", YouTubeContentTypeDto.VIDEO, null)
    }

    @Test fun `youtube handle URL extracts handle as channel search`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("@IslamicChannel", YouTubeContentTypeDto.CHANNEL, null))
            .thenReturn(successPage(listOf(hit("UC123", contentType = "CHANNEL"))))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("https://www.youtube.com/@IslamicChannel")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.CHANNEL, state.searchType)
        verify(repo).search("@IslamicChannel", YouTubeContentTypeDto.CHANNEL, null)
    }

    @Test fun `plain text query falls through to ALL search`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("quran recitation", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage())

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("quran recitation")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.ALL, state.searchType)
        verify(repo).search("quran recitation", YouTubeContentTypeDto.ALL, null)
    }

    @Test fun `malformed URL falls through to ALL text search`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        whenever(repo.search("https://not-youtube.com/xyz", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage())

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("https://not-youtube.com/xyz")
        advanceTimeBy(310L)
        advanceUntilIdle()

        verify(repo).search("https://not-youtube.com/xyz", YouTubeContentTypeDto.ALL, null)
    }

    // ── activeFilter preserved across new queries ──────────────────────────────

    @Test fun `active CHANNEL filter is preserved when query changes`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        val mixed = listOf(
            hit("UC1", contentType = "CHANNEL"),
            hit("VID1", contentType = "VIDEO"),
        )
        whenever(repo.search(any(), eq(YouTubeContentTypeDto.ALL), eq(null)))
            .thenReturn(successPage(mixed))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("quran")
        advanceTimeBy(310L)
        advanceUntilIdle()

        // Switch to CHANNEL filter
        vm.onTypeChange(YouTubeContentTypeDto.CHANNEL)
        assertEquals(YouTubeContentTypeDto.CHANNEL, (vm.uiState.value as SuggestUiState.Results).activeFilter)

        // Type a new query — filter should be preserved in results
        vm.onQueryChange("quran tafsir")
        advanceTimeBy(310L)
        advanceUntilIdle()

        val state = vm.uiState.value as SuggestUiState.Results
        assertEquals(YouTubeContentTypeDto.CHANNEL, state.activeFilter)
        assertEquals(1, state.items.size)
        assertEquals("UC1", state.items[0].youtubeId)
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

        val state = vm.uiState.value as SuggestUiState.Error
        assertEquals(R.string.suggest_error_not_allowed, state.messageRes)
        assertEquals(null, state.formatArg)
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

        val state = vm.uiState.value as SuggestUiState.Error
        assertEquals(R.string.suggest_error_network, state.messageRes)
        assertEquals(null, state.formatArg)
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

        val state = vm.uiState.value as SuggestUiState.Error
        assertEquals(R.string.suggest_error_server, state.messageRes)
        assertEquals("503", state.formatArg)
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

    // ── S13: loadMore re-applies active filter on combined allItems ───────────

    @Test fun `loadMore appends allItems and re-applies active filter`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        val ch1  = hit("UC1",  contentType = "CHANNEL")
        val vid1 = hit("VID1", contentType = "VIDEO")
        val ch2  = hit("UC2",  contentType = "CHANNEL")
        val pl1  = hit("PL1",  contentType = "PLAYLIST")

        whenever(repo.search("test", YouTubeContentTypeDto.ALL, null))
            .thenReturn(successPage(listOf(ch1, vid1), nextPageToken = "tok1"))
        whenever(repo.search("test", YouTubeContentTypeDto.ALL, "tok1"))
            .thenReturn(successPage(listOf(ch2, pl1), nextPageToken = null))

        val vm = SuggestContentViewModel(repo)
        vm.onQueryChange("test")
        advanceTimeBy(310L)
        advanceUntilIdle()

        vm.onTypeChange(YouTubeContentTypeDto.CHANNEL)
        val afterFilter = vm.uiState.value as SuggestUiState.Results
        assertEquals(1, afterFilter.items.size)
        assertEquals(2, afterFilter.allItems.size)

        vm.loadMore()
        advanceUntilIdle()

        val final1 = vm.uiState.value as SuggestUiState.Results
        assertEquals(4, final1.allItems.size)
        assertEquals(2, final1.items.size)
        assertEquals("UC1", final1.items[0].youtubeId)
        assertEquals("UC2", final1.items[1].youtubeId)
        assertEquals(YouTubeContentTypeDto.CHANNEL, final1.activeFilter)
    }

    // ── S14: onTypeChange on non-Results states is a no-op ───────────────────

    @Test fun `onTypeChange on non-Results state does not crash and state is unchanged`() = runTest(dispatcher) {
        val repo: YouTubeSearchRepository = mock()
        val vm = SuggestContentViewModel(repo)

        assertTrue(vm.uiState.value is SuggestUiState.Idle)
        vm.onTypeChange(YouTubeContentTypeDto.CHANNEL)
        assertTrue(vm.uiState.value is SuggestUiState.Idle)

        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(SearchResult.Success(YouTubeSearchResponseDto(items = emptyList(), nextPageToken = null)))
        vm.onQueryChange("nothing")
        advanceTimeBy(310L)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SuggestUiState.Empty)
        vm.onTypeChange(YouTubeContentTypeDto.VIDEO)
        assertTrue(vm.uiState.value is SuggestUiState.Empty)

        whenever(repo.search(any(), any(), anyOrNull()))
            .thenReturn(SearchResult.NetworkError)
        vm.onQueryChange("error")
        advanceTimeBy(310L)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SuggestUiState.Error)
        vm.onTypeChange(YouTubeContentTypeDto.PLAYLIST)
        assertTrue(vm.uiState.value is SuggestUiState.Error)
    }
}
