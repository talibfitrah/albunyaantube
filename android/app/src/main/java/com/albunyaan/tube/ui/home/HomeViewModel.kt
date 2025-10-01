package com.albunyaan.tube.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.paging.ContentPagingRepository

class HomeViewModel(
    private val filterManager: FilterManager,
    private val repository: ContentPagingRepository
) : ViewModel() {

    val pagingData: Flow<PagingData<ContentItem>> = filterManager.state
        .flatMapLatest { filters: FilterState ->
            repository.homePager(filters).flow
        }
        .cachedIn(viewModelScope)

    val filterState: Flow<FilterState> = filterManager.state

    fun clearFilters() {
        filterManager.clearAll()
    }

    class Factory(
        private val filterManager: FilterManager,
        private val repository: ContentPagingRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(filterManager, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
