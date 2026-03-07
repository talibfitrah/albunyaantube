package com.albunyaan.tube.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.model.HomeSection
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.util.DeviceConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val app: Application,
    @Named("real") private val contentService: ContentService,
    private val filterManager: FilterManager
) : AndroidViewModel(app) {

    private val _homeState = MutableStateFlow<HomeState>(HomeState.Loading)
    val homeState: StateFlow<HomeState> = _homeState.asStateFlow()

    private var sections = mutableListOf<HomeSection>()
    private var nextCursor: String? = null
    private var hasMore = true
    private var isLoadingMore = false
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var currentCategory: String? = null

    val canLoadMore: Boolean
        get() = hasMore && !isLoadingMore

    init {
        observeCategoryFilter()
    }

    private fun observeCategoryFilter() {
        viewModelScope.launch {
            filterManager.state
                .map { it.category }
                .distinctUntilChanged()
                .collect { category ->
                    currentCategory = category
                    loadInitialFeed()
                }
        }
    }

    fun loadInitialFeed() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            sections.clear()
            nextCursor = null
            hasMore = true
            isLoadingMore = false
            _homeState.value = HomeState.Loading

            try {
                val contentLimit = DeviceConfig.getHomeDataLimit(app)
                Log.d(TAG, "Loading initial home feed (contentLimit=$contentLimit, category=$currentCategory)")
                val result = contentService.fetchHomeFeed(
                    cursor = null,
                    categoryLimit = CATEGORY_LIMIT,
                    contentLimit = contentLimit,
                    category = currentCategory
                )
                sections.addAll(result.sections)
                nextCursor = result.nextCursor
                hasMore = result.hasMore
                Log.d(TAG, "Initial feed loaded: ${result.sections.size} sections, hasMore=$hasMore")
                _homeState.value = if (sections.isEmpty()) {
                    HomeState.Empty
                } else {
                    HomeState.Success(sections.toList(), hasMore)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error loading initial home feed", e)
                _homeState.value = HomeState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadMoreSections() {
        if (!canLoadMore) return
        isLoadingMore = true
        loadMoreJob?.cancel()

        loadMoreJob = viewModelScope.launch {
            try {
                val contentLimit = DeviceConfig.getHomeDataLimit(app)
                Log.d(TAG, "Loading more sections (cursor=$nextCursor, contentLimit=$contentLimit, category=$currentCategory)")

                // Emit current sections with loading-more indicator
                _homeState.value = HomeState.Success(sections.toList(), hasMore, isLoadingMore = true)

                val result = contentService.fetchHomeFeed(
                    cursor = nextCursor,
                    categoryLimit = CATEGORY_LIMIT,
                    contentLimit = contentLimit,
                    category = currentCategory
                )
                val existingIds = sections.map { it.categoryId }.toSet()
                val newSections = result.sections.filter { it.categoryId !in existingIds }
                sections.addAll(newSections)
                nextCursor = result.nextCursor
                hasMore = result.hasMore && (newSections.isNotEmpty() || result.sections.isEmpty())
                isLoadingMore = false
                Log.d(TAG, "Loaded ${result.sections.size} more sections (added=${newSections.size}, total=${sections.size}, hasMore=$hasMore)")
                _homeState.value = HomeState.Success(sections.toList(), hasMore)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error loading more sections", e)
                isLoadingMore = false
                _homeState.value = HomeState.Success(sections.toList(), hasMore)
            }
        }
    }

    fun refresh() {
        loadInitialFeed()
    }

    sealed class HomeState {
        object Loading : HomeState()
        data class Success(
            val sections: List<HomeSection>,
            val hasMore: Boolean,
            val isLoadingMore: Boolean = false
        ) : HomeState()
        data class Error(val message: String) : HomeState()
        object Empty : HomeState()
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val CATEGORY_LIMIT = 5
    }
}
