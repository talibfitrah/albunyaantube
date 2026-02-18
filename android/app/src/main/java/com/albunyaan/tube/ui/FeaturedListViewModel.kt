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
import com.albunyaan.tube.util.DeviceConfig
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

    /** Category ID from navigation arguments, falls back to featured category */
    val categoryId: String
        get() {
            val argId = savedStateHandle.get<String>("categoryId")
            return if (argId.isNullOrEmpty()) FEATURED_CATEGORY_ID else argId
        }

    fun loadFeatured() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = FeaturedState.Loading
            try {
                val limit = DeviceConfig.getHomeDataLimit(app)
                Log.d(TAG, "Fetching content for category: $categoryId")
                val response = contentService.fetchContent(
                    type = ContentType.ALL,
                    cursor = null,
                    pageSize = limit,
                    filters = FilterState(category = categoryId)
                )
                val featured = response.data.take(limit)
                Log.d(TAG, "Loaded ${featured.size} items for category $categoryId")
                _state.value = FeaturedState.Success(featured)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error loading content for category $categoryId", e)
                _state.value = FeaturedState.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class FeaturedState {
        object Loading : FeaturedState()
        data class Success(val items: List<ContentItem>) : FeaturedState()
        data class Error(val message: String) : FeaturedState()
    }

    companion object {
        private const val TAG = "FeaturedListViewModel"
        const val FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"
    }
}
