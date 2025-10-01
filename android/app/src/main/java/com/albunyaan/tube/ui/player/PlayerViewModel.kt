package com.albunyaan.tube.ui.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.R
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.player.PlayerRepository
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val repository: PlayerRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val queue = mutableListOf<UpNextItem>()
    private var currentItem: UpNextItem? = null
    private var resolveJob: Job? = null

    private val _analyticsEvents = MutableSharedFlow<PlaybackAnalyticsEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val analyticsEvents: SharedFlow<PlaybackAnalyticsEvent> = _analyticsEvents

    val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateState { it.copy(hasVideoTrack = videoSize != VideoSize.UNKNOWN) }
        }
    }

    init {
        hydrateQueue()
    }

    fun setAudioOnly(audioOnly: Boolean) {
        if (_state.value.audioOnly == audioOnly) return
        updateState { it.copy(audioOnly = audioOnly) }
        publishAnalytics(PlaybackAnalyticsEvent.AudioOnlyToggled(audioOnly))
    }

    fun playItem(item: UpNextItem) {
        val current = currentItem
        if (current?.id == item.id) return
        val removed = queue.remove(item)
        if (!removed) return
        current?.let { queue.add(0, it) }
        currentItem = item
        applyQueueState()
        publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(item, PlaybackStartReason.USER_SELECTED))
        resolveStreamFor(item, PlaybackStartReason.USER_SELECTED)
    }

    fun markCurrentComplete() {
        val finished = currentItem ?: return
        publishAnalytics(PlaybackAnalyticsEvent.PlaybackCompleted(finished))
        currentItem = if (queue.isNotEmpty()) queue.removeAt(0) else null
        applyQueueState()
        currentItem?.let {
            publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(it, PlaybackStartReason.AUTO))
            resolveStreamFor(it, PlaybackStartReason.AUTO)
        } ?: run {
            updateState { state -> state.copy(streamState = StreamState.Idle) }
        }
    }

    private fun hydrateQueue() {
        val stubItems = stubUpNextItems()
        val (playable, excluded) = stubItems.partition { !it.isExcluded }
        queue.clear()
        queue.addAll(playable)
        currentItem = if (queue.isNotEmpty()) queue.removeAt(0) else null
        updateState {
            it.copy(
                currentItem = currentItem,
                upNext = queue.toList(),
                excludedItems = excluded
            )
        }
        publishAnalytics(
            PlaybackAnalyticsEvent.QueueHydrated(
                totalItems = stubItems.size,
                excludedItems = excluded.size,
                firstItem = currentItem
            )
        )
        currentItem?.let {
            publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(it, PlaybackStartReason.AUTO))
            resolveStreamFor(it, PlaybackStartReason.AUTO)
        }
    }

    private fun applyQueueState() {
        updateState { state ->
            state.copy(
                currentItem = currentItem,
                upNext = queue.toList()
            )
        }
    }

    private fun resolveStreamFor(item: UpNextItem, reason: PlaybackStartReason) {
        resolveJob?.cancel()
        updateState { it.copy(streamState = StreamState.Loading) }
        resolveJob = viewModelScope.launch(dispatcher) {
            val resolved = try {
                repository.resolveStreams(item.streamId)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_error)) }
                publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                return@launch
            }

            if (resolved == null) {
                updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_unavailable)) }
                publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                return@launch
            }

            val selection = resolved.toDefaultSelection()
            if (selection == null) {
                updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_unavailable)) }
                publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                return@launch
            }

            publishAnalytics(PlaybackAnalyticsEvent.StreamResolved(item.streamId, selection.video?.qualityLabel))
            updateState { it.copy(streamState = StreamState.Ready(resolved.streamId, selection)) }
        }
    }

    private fun publishAnalytics(event: PlaybackAnalyticsEvent) {
        _analyticsEvents.tryEmit(event)
        updateState { it.copy(lastAnalyticsEvent = event) }
    }

    private fun updateState(transform: (PlayerState) -> PlayerState) {
        _state.value = transform(_state.value)
    }

    class Factory(
        private val repository: PlayerRepository,
        private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PlayerViewModel(repository, dispatcher) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class PlayerState(
    val audioOnly: Boolean = false,
    val hasVideoTrack: Boolean = true,
    val currentItem: UpNextItem? = null,
    val upNext: List<UpNextItem> = emptyList(),
    val excludedItems: List<UpNextItem> = emptyList(),
    val streamState: StreamState = StreamState.Idle,
    val lastAnalyticsEvent: PlaybackAnalyticsEvent? = null
)

data class UpNextItem(
    val id: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val isExcluded: Boolean = false,
    val exclusionReason: String? = null,
    val streamId: String
)

sealed class StreamState {
    object Idle : StreamState()
    object Loading : StreamState()
    data class Ready(val streamId: String, val selection: PlaybackSelection) : StreamState()
    data class Error(@StringRes val messageRes: Int) : StreamState()
}

sealed class PlaybackAnalyticsEvent {
    data class QueueHydrated(
        val totalItems: Int,
        val excludedItems: Int,
        val firstItem: UpNextItem?
    ) : PlaybackAnalyticsEvent()

    data class PlaybackStarted(
        val item: UpNextItem,
        val reason: PlaybackStartReason
    ) : PlaybackAnalyticsEvent()

    data class PlaybackCompleted(val item: UpNextItem) : PlaybackAnalyticsEvent()

    data class AudioOnlyToggled(val enabled: Boolean) : PlaybackAnalyticsEvent()

    data class StreamResolved(val streamId: String, val qualityLabel: String?) : PlaybackAnalyticsEvent()

    data class StreamFailed(val streamId: String) : PlaybackAnalyticsEvent()
}

enum class PlaybackStartReason(@StringRes val labelRes: Int) {
    AUTO(R.string.player_start_reason_auto),
    USER_SELECTED(R.string.player_start_reason_user_selected),
    RESUME(R.string.player_start_reason_resume)
}

private fun ResolvedStreams.toDefaultSelection(): PlaybackSelection? {
    val preferredVideo = videoTracks.maxWithOrNull(compareBy<VideoTrack> { it.height ?: 0 }
        .thenBy { it.bitrate ?: 0 })
    val preferredAudio = (audioTracks.maxByOrNull { it.bitrate ?: 0 }
        ?: preferredVideo?.let {
            AudioTrack(
                url = it.url,
                mimeType = it.mimeType,
                bitrate = it.bitrate,
                codec = null
            )
        }) ?: return null
    return PlaybackSelection(streamId, preferredVideo, preferredAudio, this)
}

private fun stubUpNextItems(): List<UpNextItem> = listOf(
    UpNextItem(
        id = "intro_foundations",
        title = "Foundations Orientation",
        channelName = "Albunyaan Institute",
        durationSeconds = 630,
        streamId = "M7lc1UVf-VE"
    ),
    UpNextItem(
        id = "tafsir_baqara",
        title = "Tafsir Series - Al-Baqarah",
        channelName = "Albunyaan Institute",
        durationSeconds = 900,
        streamId = "aqz-KE-bpKQ"
    ),
    UpNextItem(
        id = "youth_circle",
        title = "Youth Circle Q&A",
        channelName = "Albunyaan Youth",
        durationSeconds = 780,
        streamId = "ysz5S6PUM-U"
    ),
    UpNextItem(
        id = "community_roundtable",
        title = "Community Roundtable",
        channelName = "Community Submissions",
        durationSeconds = 540,
        isExcluded = true,
        exclusionReason = "Awaiting moderator approval",
        streamId = "E7wJTI-1dvQ"
    ),
    UpNextItem(
        id = "family_series",
        title = "Family Series: Parenting Essentials",
        channelName = "Albunyaan Family",
        durationSeconds = 840,
        streamId = "dQw4w9WgXcQ"
    )
)
