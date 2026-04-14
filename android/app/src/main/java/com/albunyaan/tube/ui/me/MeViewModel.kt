package com.albunyaan.tube.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MeViewModel @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val feed: MeFeedRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<MeFeedState> = combine(
        subscriptions.observeSubscribedChannels(),
        subscriptions.observeSavedPlaylists(),
        feed.observeFeed(),
        filter,
        refreshing,
    ) { channels, playlists, cached, filterId, isRefreshing ->
        buildState(channels, playlists, cached, filterId, isRefreshing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MeFeedState.Loading,
    )

    init {
        refreshFeed(force = false)
    }

    fun setFilter(channelId: String?) {
        filter.value = channelId
    }

    fun refreshFeed(force: Boolean) {
        viewModelScope.launch {
            refreshing.value = true
            try {
                feed.refresh(force = force)
            } finally {
                refreshing.value = false
            }
        }
    }

    private fun buildState(
        channels: List<SubscribedChannel>,
        playlists: List<SavedPlaylist>,
        cached: List<ChannelVideoCache>,
        filterId: String?,
        isRefreshing: Boolean,
    ): MeFeedState {
        if (channels.isEmpty() && playlists.isEmpty()) return MeFeedState.Empty

        val chips = buildList(capacity = channels.size + playlists.size) {
            channels.forEach {
                add(ChipItem.Channel(it.channelId, it.name, it.avatarUrl, it.channelUrl))
            }
            playlists.forEach {
                add(ChipItem.Playlist(it.playlistId, it.name, it.thumbnailUrl, it.playlistUrl))
            }
        }

        val scoped = if (filterId == null) cached else cached.filter { it.channelId == filterId }
        val shorts = scoped.asSequence().filter { it.isShort }.map { it.toUi() }.toList()
        val videos = scoped.asSequence().filterNot { it.isShort }.map { it.toUi() }.toList()

        return MeFeedState.Content(
            chips = chips,
            shorts = shorts,
            videos = videos,
            refreshing = isRefreshing,
            filterChannelId = filterId,
        )
    }

    private fun ChannelVideoCache.toUi() = MeFeedVideo(
        videoId = videoId,
        channelId = channelId,
        channelName = channelName,
        title = title,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = viewCount,
        uploadedAt = uploadedAt ?: 0L,
        isShort = isShort,
    )
}
