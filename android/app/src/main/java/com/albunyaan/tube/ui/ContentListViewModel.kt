package com.albunyaan.tube.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ContentListViewModel(
    private val contentService: ContentService,
    private val contentType: ContentType
) : ViewModel() {

    private val _content = MutableStateFlow<ContentState>(ContentState.Loading)
    val content: StateFlow<ContentState> = _content.asStateFlow()

    init {
        loadContent()
    }

    fun loadContent() {
        viewModelScope.launch {
            _content.value = ContentState.Loading
            try {
                Log.d(TAG, "Fetching content for type=$contentType")
                val response = contentService.fetchContent(
                    type = contentType,
                    cursor = null,
                    pageSize = 50,
                    filters = FilterState()
                )

                Log.d(TAG, "Received ${response.data.size} items for type=$contentType")
                _content.value = ContentState.Success(response.data)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading content for type=$contentType", e)
                _content.value = ContentState.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class ContentState {
        object Loading : ContentState()
        data class Success(val items: List<ContentItem>) : ContentState()
        data class Error(val message: String) : ContentState()
    }

    class Factory(
        private val contentService: ContentService,
        private val contentType: ContentType
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ContentListViewModel::class.java)) {
                return ContentListViewModel(contentService, contentType) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "ContentListViewModel"
    }
}

