package com.albunyaan.tube.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.SortOption
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.paging.ContentPagingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class ContentListViewModel(
    private val filterManager: FilterManager,
    private val repository: ContentPagingRepository,
    private val contentType: ContentType
) : ViewModel() {

    val filterState: StateFlow<FilterState> = filterManager.state

    val pagingData: Flow<PagingData<ContentItem>> = filterManager.state
        .flatMapLatest { filters -> repository.pager(contentType, filters).flow }
        .cachedIn(viewModelScope)

    fun setCategory(category: String?) {
        filterManager.setCategory(category)
    }

    fun setVideoLength(length: VideoLength) {
        filterManager.setVideoLength(length)
    }

    fun setPublishedDate(date: PublishedDate) {
        filterManager.setPublishedDate(date)
    }

    fun setSortOption(option: SortOption) {
        filterManager.setSortOption(option)
    }

    fun clearFilters() {
        viewModelScope.launch {
            filterManager.clearAll()
        }
    }

    class Factory(
        private val filterManager: FilterManager,
        private val repository: ContentPagingRepository,
        private val contentType: ContentType
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ContentListViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ContentListViewModel(filterManager, repository, contentType) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

