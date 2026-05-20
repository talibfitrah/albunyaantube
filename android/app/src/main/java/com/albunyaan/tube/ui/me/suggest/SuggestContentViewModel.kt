package com.albunyaan.tube.ui.me.suggest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.search.SearchResult
import com.albunyaan.tube.data.search.YouTubeSearchRepository
import com.albunyaan.tube.data.search.dto.SearchHitDto
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SuggestContentViewModel @Inject constructor(
    private val repo: YouTubeSearchRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    private val _uiState = MutableStateFlow<SuggestUiState>(SuggestUiState.Idle)
    val uiState: StateFlow<SuggestUiState> = _uiState.asStateFlow()

    init {
        // Only query changes drive backend searches. Type changes are applied
        // client-side by onTypeChange() without hitting the network.
        viewModelScope.launch {
            query.debounce(300L).distinctUntilChanged()
                .flatMapLatest { q ->
                    if (q.isBlank()) {
                        flowOf<SuggestUiState>(SuggestUiState.Idle)
                    } else {
                        val (searchType, searchQuery) = resolveQuery(q)
                        val previousFilter = (_uiState.value as? SuggestUiState.Results)?.activeFilter
                            ?: YouTubeContentTypeDto.ALL
                        flow<SuggestUiState> {
                            emit(SuggestUiState.Loading)
                            val result = mapSearchResult(repo.search(searchQuery, searchType, null), searchType, searchQuery)
                            emit(if (result is SuggestUiState.Results && previousFilter != YouTubeContentTypeDto.ALL) {
                                result.copy(
                                    items = applyFilter(result.allItems, previousFilter),
                                    activeFilter = previousFilter,
                                )
                            } else result)
                        }
                    }
                }
                .collect { _uiState.value = it }
        }
    }

    /**
     * If the input looks like a YouTube URL, extract the content type and ID
     * and use those for a targeted backend search. Otherwise search as-is with
     * type=ALL so the server returns mixed results that chips can filter locally.
     */
    private fun resolveQuery(q: String): Pair<YouTubeContentTypeDto, String> =
        parseYouTubeUrl(q) ?: (YouTubeContentTypeDto.ALL to q)

    private fun parseYouTubeUrl(input: String): Pair<YouTubeContentTypeDto, String>? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return try {
            val uri = java.net.URI(trimmed)
            val host = uri.host ?: return null
            when {
                host == "youtu.be" -> {
                    val id = uri.path?.trimStart('/')?.takeIf { it.isNotBlank() }
                    id?.let { YouTubeContentTypeDto.VIDEO to it }
                }
                host == "youtube.com" || host.endsWith(".youtube.com") -> {
                    val params = parseQueryParams(uri.rawQuery)
                    val segs   = uri.path?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
                    val v      = params["v"]
                    val list   = params["list"]
                    val channelIdx = segs.indexOf("channel")
                    val channelId  = if (channelIdx >= 0 && channelIdx + 1 < segs.size)
                        segs[channelIdx + 1] else null
                    val shortsIdx = segs.indexOf("shorts")
                    val shortsId  = if (shortsIdx >= 0 && shortsIdx + 1 < segs.size)
                        segs[shortsIdx + 1] else null
                    val handle = segs.firstOrNull { it.startsWith("@") }
                    when {
                        v != null         -> YouTubeContentTypeDto.VIDEO    to v
                        list != null      -> YouTubeContentTypeDto.PLAYLIST to list
                        channelId != null -> YouTubeContentTypeDto.CHANNEL  to channelId
                        shortsId != null  -> YouTubeContentTypeDto.VIDEO    to shortsId
                        handle != null    -> YouTubeContentTypeDto.CHANNEL  to handle
                        else              -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()
    }

    private fun mapSearchResult(
        r: SearchResult,
        searchType: YouTubeContentTypeDto,
        q: String,
    ): SuggestUiState = when (r) {
        is SearchResult.Success -> {
            val allItems = r.page.items
            if (allItems.isEmpty()) SuggestUiState.Empty
            else SuggestUiState.Results(
                items = allItems,
                allItems = allItems,
                activeFilter = YouTubeContentTypeDto.ALL,
                nextPageToken = r.page.nextPageToken,
                searchType = searchType,
                query = q,
            )
        }
        SearchResult.Forbidden       -> SuggestUiState.Error("Not allowed")
        is SearchResult.RateLimited  -> SuggestUiState.RateLimited(r.retryAfterSec)
        SearchResult.NetworkError    -> SuggestUiState.Error("Network error")
        is SearchResult.Unknown      -> SuggestUiState.Error("Server error ${r.code}")
    }

    private fun applyFilter(items: List<SearchHitDto>, filter: YouTubeContentTypeDto): List<SearchHitDto> =
        if (filter == YouTubeContentTypeDto.ALL) items
        else items.filter { it.contentType == filter.name }

    fun onQueryChange(q: String) { query.value = q }

    fun onTypeChange(t: YouTubeContentTypeDto) {
        val current = _uiState.value as? SuggestUiState.Results ?: return
        _uiState.value = current.copy(
            items = applyFilter(current.allItems, t),
            activeFilter = t,
        )
    }

    fun loadMore() {
        val current = _uiState.value as? SuggestUiState.Results ?: return
        if (current.loadingMore || current.nextPageToken == null) return
        _uiState.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            val r = repo.search(current.query, current.searchType, current.nextPageToken)
            // After suspend, confirm we're still on the same generation.
            val latest = _uiState.value as? SuggestUiState.Results ?: return@launch
            if (latest.query != current.query
                    || latest.searchType != current.searchType
                    || latest.nextPageToken != current.nextPageToken) {
                return@launch
            }
            _uiState.value = when (r) {
                is SearchResult.Success -> {
                    val newAllItems = latest.allItems + r.page.items
                    latest.copy(
                        items = applyFilter(newAllItems, latest.activeFilter),
                        allItems = newAllItems,
                        nextPageToken = r.page.nextPageToken,
                        loadingMore = false,
                    )
                }
                else -> latest.copy(loadingMore = false)
            }
        }
    }
}
