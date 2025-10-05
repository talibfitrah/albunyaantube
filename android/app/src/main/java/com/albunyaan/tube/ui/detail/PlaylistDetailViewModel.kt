package com.albunyaan.tube.ui.detail

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

class PlaylistDetailViewModel(
    private val contentService: ContentService,
    private val playlistId: String
) : ViewModel() {

    private val _playlistState = MutableStateFlow<PlaylistState>(PlaylistState.Loading)
    val playlistState: StateFlow<PlaylistState> = _playlistState.asStateFlow()

    private val _videosState = MutableStateFlow<VideosState>(VideosState.Loading)
    val videosState: StateFlow<VideosState> = _videosState.asStateFlow()

    init {
        loadPlaylistDetails()
    }

    private fun loadPlaylistDetails() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading playlist details for: $playlistId")
                _playlistState.value = PlaylistState.Loading

                // Fetch all playlists to find the one we need
                val response = contentService.fetchContent(
                    type = ContentType.PLAYLISTS,
                    cursor = null,
                    pageSize = 100,
                    filters = FilterState()
                )

                val playlist = response.items
                    .filterIsInstance<ContentItem.Playlist>()
                    .firstOrNull { it.id == playlistId }

                if (playlist != null) {
                    _playlistState.value = PlaylistState.Success(playlist)
                    Log.d(TAG, "Playlist loaded: ${playlist.title}")
                    
                    // Auto-load videos in this playlist
                    loadPlaylistVideos()
                } else {
                    _playlistState.value = PlaylistState.Error("Playlist not found: $playlistId")
                    Log.e(TAG, "Playlist not found: $playlistId")
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to load playlist: ${e.message}"
                _playlistState.value = PlaylistState.Error(errorMsg)
                Log.e(TAG, errorMsg, e)
            }
        }
    }

    private fun loadPlaylistVideos() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading videos for playlist: $playlistId")
                _videosState.value = VideosState.Loading

                // Fetch videos (in real implementation, would filter by playlist)
                val response = contentService.fetchContent(
                    type = ContentType.VIDEOS,
                    cursor = null,
                    pageSize = 20,
                    filters = FilterState()
                )
                
                val videos = response.items.filterIsInstance<ContentItem.Video>()
                _videosState.value = VideosState.Success(videos)
                Log.d(TAG, "Loaded ${videos.size} videos for playlist")
            } catch (e: Exception) {
                val errorMsg = "Failed to load videos: ${e.message}"
                _videosState.value = VideosState.Error(errorMsg)
                Log.e(TAG, errorMsg, e)
            }
        }
    }

    fun refreshPlaylist() {
        loadPlaylistDetails()
    }

    sealed class PlaylistState {
        object Loading : PlaylistState()
        data class Success(val playlist: ContentItem.Playlist) : PlaylistState()
        data class Error(val message: String) : PlaylistState()
    }

    sealed class VideosState {
        object Loading : VideosState()
        data class Success(val videos: List<ContentItem.Video>) : VideosState()
        data class Error(val message: String) : VideosState()
    }

    class Factory(
        private val contentService: ContentService,
        private val playlistId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlaylistDetailViewModel::class.java)) {
                return PlaylistDetailViewModel(contentService, playlistId) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "PlaylistDetailViewModel"
    }
}
