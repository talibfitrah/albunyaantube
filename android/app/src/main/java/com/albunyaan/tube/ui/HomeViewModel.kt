package com.albunyaan.tube.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class HomeViewModel @Inject constructor(
    @Named("real") private val contentService: ContentService
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

                android.util.Log.d("HomeViewModel", "Received ${response.data.size} total items")

                val allChannels = response.data.filterIsInstance<ContentItem.Channel>()
                val allPlaylists = response.data.filterIsInstance<ContentItem.Playlist>()
                val allVideos = response.data.filterIsInstance<ContentItem.Video>()

                android.util.Log.d("HomeViewModel", "Filtered: ${allChannels.size} channels, ${allPlaylists.size} playlists, ${allVideos.size} videos")

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

}
