package com.albunyaan.tube.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
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
    private var nextCursor: String? = null
    private val allItems = mutableListOf<ContentItem>()

    /** True when there are more pages to fetch */
    val canLoadMore: Boolean
        get() = nextCursor != null

    /** Category ID from navigation arguments, falls back to featured category */
    val categoryId: String
        get() {
            val argId = savedStateHandle.get<String>("categoryId")
            return if (argId.isNullOrEmpty()) FEATURED_CATEGORY_ID else argId
        }

    fun loadFeatured() {
        loadJob?.cancel()
        allItems.clear()
        nextCursor = null
        loadJob = viewModelScope.launch {
            _state.value = FeaturedState.Loading
            try {
                Log.d(TAG, "Fetching content for category: $categoryId")
                val response = contentService.fetchContent(
                    type = ContentType.ALL,
                    cursor = null,
                    pageSize = PAGE_SIZE,
                    filters = FilterState(category = categoryId)
                )
                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                Log.d(TAG, "Loaded ${allItems.size} items, hasMore=$canLoadMore")
                _state.value = FeaturedState.Success(allItems.toList(), isLoadingMore = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error loading content for category $categoryId", e)
                _state.value = FeaturedState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadMore() {
        val cursor = nextCursor ?: return
        // Avoid duplicate loads
        val current = _state.value
        if (current is FeaturedState.Success && current.isLoadingMore) return

        loadJob = viewModelScope.launch {
            _state.value = FeaturedState.Success(allItems.toList(), isLoadingMore = true)
            try {
                Log.d(TAG, "Loading more for category: $categoryId, cursor: $cursor")
                val response = contentService.fetchContent(
                    type = ContentType.ALL,
                    cursor = cursor,
                    pageSize = PAGE_SIZE,
                    filters = FilterState(category = categoryId)
                )
                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                Log.d(TAG, "Total items: ${allItems.size}, hasMore=$canLoadMore")
                _state.value = FeaturedState.Success(allItems.toList(), isLoadingMore = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error loading more for category $categoryId", e)
                // Clear cursor so UI stops retrying the same failing page
                nextCursor = null
                // Keep existing items visible, just stop loading indicator
                _state.value = FeaturedState.Success(allItems.toList(), isLoadingMore = false)
            }
        }
    }

    sealed class FeaturedState {
        object Loading : FeaturedState()
        data class Success(val items: List<ContentItem>, val isLoadingMore: Boolean) : FeaturedState()
        data class Error(val message: String) : FeaturedState()
    }

    companion object {
        private const val TAG = "FeaturedListViewModel"
        private const val PAGE_SIZE = 50
        const val FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"
    }
}
