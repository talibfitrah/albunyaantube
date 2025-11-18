package com.albunyaan.tube.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Named

@HiltViewModel(assistedFactory = ChannelDetailViewModel.Factory::class)
class ChannelDetailViewModel @AssistedInject constructor(
    @Named("real") private val contentService: ContentService,
    @Assisted private val channelId: String
) : ViewModel() {

    private val _channelState = MutableStateFlow<ChannelState>(ChannelState.Loading)
    val channelState: StateFlow<ChannelState> = _channelState.asStateFlow()

    private val _tabContent = MutableStateFlow<Map<ChannelTab, TabContentState>>(emptyMap())
    val tabContent: StateFlow<Map<ChannelTab, TabContentState>> = _tabContent.asStateFlow()

    init {
        loadChannelDetails()
    }

    private fun loadChannelDetails() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading channel details for: $channelId")
                _channelState.value = ChannelState.Loading

                // Fetch all channels to find the one we need
                val response = contentService.fetchContent(
                    type = ContentType.CHANNELS,
                    cursor = null,
                    pageSize = 100,
                    filters = FilterState()
                )

                val channel = response.data
                    .filterIsInstance<ContentItem.Channel>()
                    .firstOrNull { it.id == channelId }

                if (channel != null) {
                    _channelState.value = ChannelState.Success(channel)
                    Log.d(TAG, "Channel loaded: ${channel.name}")
                    
                    // Pre-load videos tab
                    loadTabContent(ChannelTab.VIDEOS)
                } else {
                    _channelState.value = ChannelState.Error("Channel not found: $channelId")
                    Log.e(TAG, "Channel not found: $channelId")
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to load channel: ${e.message}"
                _channelState.value = ChannelState.Error(errorMsg)
                Log.e(TAG, errorMsg, e)
            }
        }
    }

    fun loadTabContent(tab: ChannelTab) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading tab content for: $tab")
                _tabContent.value = _tabContent.value + (tab to TabContentState.Loading)

                when (tab) {
                    ChannelTab.VIDEOS -> {
                        // Fetch videos for this channel
                        val response = contentService.fetchContent(
                            type = ContentType.VIDEOS,
                            cursor = null,
                            pageSize = 20,
                            filters = FilterState()
                        )
                        
                        val videos = response.data.filterIsInstance<ContentItem.Video>()
                        _tabContent.value = _tabContent.value + (tab to TabContentState.Success(videos))
                        Log.d(TAG, "Loaded ${videos.size} videos for channel")
                    }
                    ChannelTab.PLAYLISTS -> {
                        // Fetch playlists for this channel
                        val response = contentService.fetchContent(
                            type = ContentType.PLAYLISTS,
                            cursor = null,
                            pageSize = 20,
                            filters = FilterState()
                        )
                        
                        val playlists = response.data.filterIsInstance<ContentItem.Playlist>()
                        _tabContent.value = _tabContent.value + (tab to TabContentState.Success(playlists))
                        Log.d(TAG, "Loaded ${playlists.size} playlists for channel")
                    }
                    else -> {
                        // Live, Shorts, Posts - not yet implemented
                        _tabContent.value = _tabContent.value + (tab to TabContentState.Success(emptyList()))
                        Log.d(TAG, "Tab $tab not yet implemented")
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to load tab content: ${e.message}"
                _tabContent.value = _tabContent.value + (tab to TabContentState.Error(errorMsg))
                Log.e(TAG, errorMsg, e)
            }
        }
    }

    sealed class ChannelState {
        object Loading : ChannelState()
        data class Success(val channel: ContentItem.Channel) : ChannelState()
        data class Error(val message: String) : ChannelState()
    }

    sealed class TabContentState {
        object Loading : TabContentState()
        data class Success(val items: List<ContentItem>) : TabContentState()
        data class Error(val message: String) : TabContentState()
    }

    @AssistedFactory
    interface Factory {
        fun create(channelId: String): ChannelDetailViewModel
    }

    companion object {
        private const val TAG = "ChannelDetailViewModel"
    }
}
