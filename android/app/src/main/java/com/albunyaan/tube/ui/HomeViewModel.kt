package com.albunyaan.tube.ui

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

class HomeViewModel(
    private val contentService: ContentService
) : ViewModel() {

    private val _homeContent = MutableStateFlow<HomeContentState>(HomeContentState.Loading)
    val homeContent: StateFlow<HomeContentState> = _homeContent.asStateFlow()

    init {
        loadHomeContent()
    }

    fun loadHomeContent() {
        viewModelScope.launch {
            _homeContent.value = HomeContentState.Loading
            try {
                android.util.Log.d("HomeViewModel", "Fetching HOME content...")
                val response = contentService.fetchContent(
                    type = ContentType.HOME,
                    cursor = null,
                    pageSize = 20,
                    filters = FilterState()
                )

                android.util.Log.d("HomeViewModel", "Received ${response.items.size} total items")

                val allChannels = response.items.filterIsInstance<ContentItem.Channel>()
                val allPlaylists = response.items.filterIsInstance<ContentItem.Playlist>()
                val allVideos = response.items.filterIsInstance<ContentItem.Video>()

                android.util.Log.d("HomeViewModel", "Before filtering: ${allChannels.size} channels, ${allPlaylists.size} playlists, ${allVideos.size} videos")

                allChannels.forEach { channel ->
                    android.util.Log.d("HomeViewModel", "Channel: id=${channel.id}, name=${channel.name}, category=${channel.category}")
                }
                allPlaylists.forEach { playlist ->
                    android.util.Log.d("HomeViewModel", "Playlist: id=${playlist.id}, title=${playlist.title}, category=${playlist.category}")
                }

                val channels = allChannels.take(3)
                val playlists = allPlaylists.take(3)
                val videos = allVideos.take(3)

                android.util.Log.d("HomeViewModel", "After take(3): ${channels.size} channels, ${playlists.size} playlists, ${videos.size} videos")

                _homeContent.value = HomeContentState.Success(
                    channels = channels,
                    playlists = playlists,
                    videos = videos
                )
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error loading content", e)
                _homeContent.value = HomeContentState.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class HomeContentState {
        object Loading : HomeContentState()
        data class Success(
            val channels: List<ContentItem.Channel>,
            val playlists: List<ContentItem.Playlist>,
            val videos: List<ContentItem.Video>
        ) : HomeContentState()
        data class Error(val message: String) : HomeContentState()
    }

    class Factory(
        private val contentService: ContentService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                return HomeViewModel(contentService) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
