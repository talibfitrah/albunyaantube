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
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * P3-T2: ViewModel with assisted injection for content type parameter
 */
class ContentListViewModel @AssistedInject constructor(
    private val filterManager: FilterManager,
    private val repository: ContentPagingRepository,
    @Assisted private val contentType: ContentType
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

    @AssistedFactory
    interface Factory {
        fun create(contentType: ContentType): ContentListViewModel
    }

    companion object {
        fun provideFactory(
            assistedFactory: Factory,
            contentType: ContentType
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return assistedFactory.create(contentType) as T
            }
        }
    }
}
