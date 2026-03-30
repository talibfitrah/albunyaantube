package com.albunyaan.tube.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.HomeSection
import com.albunyaan.tube.data.source.ContentService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class FeaturedListViewModel @Inject constructor(
    private val app: Application,
    @Named("real") private val contentService: ContentService,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<FeaturedState>(FeaturedState.Loading)
    val state: StateFlow<FeaturedState> = _state.asStateFlow()

    private var loadJob: Job? = null

    // Flat list mode state
    private var flatNextCursor: String? = null
    private val flatItems = mutableListOf<ContentItem>()

    // Sections mode state
    private var sectionsNextCursor: String? = null
    private val allSections = mutableListOf<HomeSection>()

    /** Tracks whether the last load-more attempt failed. Prevents auto-retry loops on tablets/TV. */
    private var lastLoadFailed = false

    val canLoadMore: Boolean
        get() = when (_state.value) {
            is FeaturedState.Sections -> sectionsNextCursor != null && !lastLoadFailed
            is FeaturedState.FlatList -> flatNextCursor != null && !lastLoadFailed
            else -> false
        }

    /** Clear the load-error flag. Called when the user manually scrolls, indicating intent to retry. */
    fun clearLoadError() {
        lastLoadFailed = false
    }

    val categoryId: String
        get() {
            val argId = savedStateHandle.get<String>("categoryId")
            return if (argId.isNullOrEmpty()) FEATURED_CATEGORY_ID else argId
        }

    fun loadFeatured() {
        loadJob?.cancel()
        flatItems.clear()
        flatNextCursor = null
        allSections.clear()
        sectionsNextCursor = null
        lastLoadFailed = false

        loadJob = viewModelScope.launch {
            _state.value = FeaturedState.Loading
            try {
                // Probe the home feed endpoint — if it returns multiple sections,
                // this category has subcategories and we show sections mode.
                val homeFeed = contentService.fetchHomeFeed(
                    cursor = null,
                    categoryLimit = SECTION_PAGE_SIZE,
                    contentLimit = CONTENT_PER_SECTION,
                    category = categoryId
                )

                val hasSubcategories = homeFeed.sections.any { it.categoryId != categoryId }
                if (hasSubcategories) {
                    // Backend expanded subcategories → sections mode
                    allSections.addAll(homeFeed.sections)
                    sectionsNextCursor = homeFeed.nextCursor
                    Log.d(TAG, "Sections mode: ${allSections.size} sections, hasMore=${sectionsNextCursor != null}")
                    _state.value = FeaturedState.Sections(allSections.toList(), isLoadingMore = false)
                } else {
                    // Single or no sections → flat list mode
                    loadFlatList()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error probing home feed for category $categoryId, falling back to flat list", e)
                try {
                    loadFlatList()
                } catch (e2: Exception) {
                    if (e2 is CancellationException) throw e2
                    _state.value = FeaturedState.Error(e2.message ?: "Unknown error")
                }
            }
        }
    }

    private suspend fun loadFlatList() {
        Log.d(TAG, "Flat list mode for category: $categoryId")
        val response = contentService.fetchContent(
            type = ContentType.ALL,
            cursor = null,
            pageSize = FLAT_PAGE_SIZE,
            filters = FilterState(category = categoryId)
        )
        flatItems.addAll(response.data)
        flatNextCursor = response.pageInfo?.nextCursor
        Log.d(TAG, "Loaded ${flatItems.size} items, hasMore=${flatNextCursor != null}")
        _state.value = FeaturedState.FlatList(flatItems.toList(), isLoadingMore = false)
    }

    fun loadMore() {
        when (_state.value) {
            is FeaturedState.Sections -> loadMoreSections()
            is FeaturedState.FlatList -> loadMoreFlat()
            else -> {}
        }
    }

    private fun loadMoreSections() {
        val cursor = sectionsNextCursor ?: return
        val current = _state.value
        if (current is FeaturedState.Sections && current.isLoadingMore) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = FeaturedState.Sections(allSections.toList(), isLoadingMore = true)
            try {
                val homeFeed = contentService.fetchHomeFeed(
                    cursor = cursor,
                    categoryLimit = SECTION_PAGE_SIZE,
                    contentLimit = CONTENT_PER_SECTION,
                    category = categoryId
                )
                allSections.addAll(homeFeed.sections)
                sectionsNextCursor = homeFeed.nextCursor
                _state.value = FeaturedState.Sections(allSections.toList(), isLoadingMore = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error loading more sections, retaining cursor for retry", e)
                lastLoadFailed = true
                _state.value = FeaturedState.Sections(allSections.toList(), isLoadingMore = false)
            }
        }
    }

    private fun loadMoreFlat() {
        val cursor = flatNextCursor ?: return
        val current = _state.value
        if (current is FeaturedState.FlatList && current.isLoadingMore) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = FeaturedState.FlatList(flatItems.toList(), isLoadingMore = true)
            try {
                val response = contentService.fetchContent(
                    type = ContentType.ALL,
                    cursor = cursor,
                    pageSize = FLAT_PAGE_SIZE,
                    filters = FilterState(category = categoryId)
                )
                flatItems.addAll(response.data)
                flatNextCursor = response.pageInfo?.nextCursor
                _state.value = FeaturedState.FlatList(flatItems.toList(), isLoadingMore = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error loading more flat items, retaining cursor for retry", e)
                lastLoadFailed = true
                _state.value = FeaturedState.FlatList(flatItems.toList(), isLoadingMore = false)
            }
        }
    }

    sealed class FeaturedState {
        object Loading : FeaturedState()
        data class Sections(val sections: List<HomeSection>, val isLoadingMore: Boolean) : FeaturedState()
        data class FlatList(val items: List<ContentItem>, val isLoadingMore: Boolean) : FeaturedState()
        data class Error(val message: String) : FeaturedState()
    }

    companion object {
        private const val TAG = "FeaturedListViewModel"
        private const val FLAT_PAGE_SIZE = 50
        private const val SECTION_PAGE_SIZE = 10
        private const val CONTENT_PER_SECTION = 20
        const val FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"
    }
}
