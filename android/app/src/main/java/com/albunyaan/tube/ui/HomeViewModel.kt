package com.albunyaan.tube.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val app: Application,
    @Named("real") private val contentService: ContentService
) : AndroidViewModel(app) {

    // Featured section shows mixed content types (channels, playlists, videos) from the Featured category
    private val _featuredState = MutableStateFlow<SectionState<ContentItem>>(SectionState.Loading)
    val featuredState: StateFlow<SectionState<ContentItem>> = _featuredState.asStateFlow()

    private val _channelsState = MutableStateFlow<SectionState<ContentItem.Channel>>(SectionState.Loading)
    val channelsState: StateFlow<SectionState<ContentItem.Channel>> = _channelsState.asStateFlow()

    private val _playlistsState = MutableStateFlow<SectionState<ContentItem.Playlist>>(SectionState.Loading)
    val playlistsState: StateFlow<SectionState<ContentItem.Playlist>> = _playlistsState.asStateFlow()

    private val _videosState = MutableStateFlow<SectionState<ContentItem.Video>>(SectionState.Loading)
    val videosState: StateFlow<SectionState<ContentItem.Video>> = _videosState.asStateFlow()

    // Track loading jobs to prevent race conditions on refresh
    private var featuredJob: Job? = null
    private var channelsJob: Job? = null
    private var playlistsJob: Job? = null
    private var videosJob: Job? = null

    init {
        loadHomeContent()
    }

    fun loadHomeContent() {
        // Cancel any previous loading jobs to prevent race conditions
        featuredJob?.cancel()
        channelsJob?.cancel()
        playlistsJob?.cancel()
        videosJob?.cancel()

        // Fetch each content type independently with device-appropriate limits
        loadFeatured()
        loadChannels()
        loadPlaylists()
        loadVideos()
    }

    fun loadFeatured() {
        featuredJob?.cancel()
        featuredJob = viewModelScope.launch {
            _featuredState.value = SectionState.Loading
            try {
                android.util.Log.d("HomeViewModel", "Fetching FEATURED content...")
                val limit = DeviceConfig.getHomeDataLimit(app)
                // Fetch all content types filtered by the Featured category
                val response = contentService.fetchContent(
                    type = ContentType.ALL,
                    cursor = null,
                    pageSize = limit,
                    filters = FilterState(category = FEATURED_CATEGORY_ID)
                )

                // Defensive take(limit) in case backend ignores pageSize hint
                val featured = response.data.take(limit)
                android.util.Log.d("HomeViewModel", "Loaded ${featured.size} featured items")
                _featuredState.value = SectionState.Success(featured)
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error loading featured", e)
                _featuredState.value = SectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadChannels() {
        channelsJob?.cancel()
        channelsJob = viewModelScope.launch {
            _channelsState.value = SectionState.Loading
            try {
                android.util.Log.d("HomeViewModel", "Fetching CHANNELS content...")
                val limit = DeviceConfig.getHomeDataLimit(app)
                val response = contentService.fetchContent(
                    type = ContentType.CHANNELS,
                    cursor = null,
                    pageSize = limit,
                    filters = FilterState()
                )

                // Defensive take(limit) in case backend ignores pageSize hint
                val channels = response.data.filterIsInstance<ContentItem.Channel>().take(limit)
                android.util.Log.d("HomeViewModel", "Loaded ${channels.size} channels")
                _channelsState.value = SectionState.Success(channels)
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error loading channels", e)
                _channelsState.value = SectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadPlaylists() {
        playlistsJob?.cancel()
        playlistsJob = viewModelScope.launch {
            _playlistsState.value = SectionState.Loading
            try {
                android.util.Log.d("HomeViewModel", "Fetching PLAYLISTS content...")
                val limit = DeviceConfig.getHomeDataLimit(app)
                val response = contentService.fetchContent(
                    type = ContentType.PLAYLISTS,
                    cursor = null,
                    pageSize = limit,
                    filters = FilterState()
                )

                // Defensive take(limit) in case backend ignores pageSize hint
                val playlists = response.data.filterIsInstance<ContentItem.Playlist>().take(limit)
                android.util.Log.d("HomeViewModel", "Loaded ${playlists.size} playlists")
                _playlistsState.value = SectionState.Success(playlists)
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error loading playlists", e)
                _playlistsState.value = SectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadVideos() {
        videosJob?.cancel()
        videosJob = viewModelScope.launch {
            _videosState.value = SectionState.Loading
            try {
                android.util.Log.d("HomeViewModel", "Fetching VIDEOS content...")
                val limit = DeviceConfig.getHomeDataLimit(app)
                val response = contentService.fetchContent(
                    type = ContentType.VIDEOS,
                    cursor = null,
                    pageSize = limit,
                    filters = FilterState()
                )

                // Sort videos by upload recency (newer = smaller uploadedDaysAgo)
                // Defensive take(limit) after sorting in case backend ignores pageSize hint
                val videos = response.data.filterIsInstance<ContentItem.Video>()
                    .sortedBy { it.uploadedDaysAgo }
                    .take(limit)
                android.util.Log.d("HomeViewModel", "Loaded ${videos.size} videos")
                _videosState.value = SectionState.Success(videos)
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error loading videos", e)
                _videosState.value = SectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class SectionState<out T> {
        object Loading : SectionState<Nothing>()
        data class Success<T>(val items: List<T>) : SectionState<T>()
        data class Error(val message: String) : SectionState<Nothing>()
    }

    companion object {
        // Featured category ID - content assigned to this category appears in the Featured section
        const val FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"
    }
}
