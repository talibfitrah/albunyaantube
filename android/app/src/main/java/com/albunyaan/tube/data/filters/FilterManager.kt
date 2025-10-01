package com.albunyaan.tube.data.filters

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Skeleton manager for filter state. Future responsibilities:
 * - Persist `FilterState` via DataStore.
 * - Provide suspend functions to update filters and notify paging repositories via Flow.
 * - Bridge saved state to UI (chips, dropdowns).
 */
class FilterManager {

    private val _state = MutableStateFlow(FilterState())
    val state: StateFlow<FilterState> = _state

    fun setCategory(category: String?) {
        _state.value = _state.value.copy(category = category)
    }

    fun setVideoLength(length: VideoLength) {
        _state.value = _state.value.copy(videoLength = length)
    }

    fun setPublishedDate(date: PublishedDate) {
        _state.value = _state.value.copy(publishedDate = date)
    }

    fun setSortOption(sort: SortOption) {
        _state.value = _state.value.copy(sortOption = sort)
    }

    fun clearAll() {
        _state.value = FilterState()
    }
}
