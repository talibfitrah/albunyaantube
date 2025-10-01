package com.albunyaan.tube.ui.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.albunyaan.tube.R
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class PlayerViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val queue = mutableListOf<UpNextItem>()
    private var currentItem: UpNextItem? = null

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
    }

    fun markCurrentComplete() {
        val finished = currentItem ?: return
        publishAnalytics(PlaybackAnalyticsEvent.PlaybackCompleted(finished))
        currentItem = if (queue.isNotEmpty()) queue.removeAt(0) else null
        applyQueueState()
        currentItem?.let {
            publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(it, PlaybackStartReason.AUTO))
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

    private fun publishAnalytics(event: PlaybackAnalyticsEvent) {
        _analyticsEvents.tryEmit(event)
        updateState { it.copy(lastAnalyticsEvent = event) }
    }

    private fun updateState(transform: (PlayerState) -> PlayerState) {
        _state.value = transform(_state.value)
    }
}

data class PlayerState(
    val audioOnly: Boolean = false,
    val hasVideoTrack: Boolean = true,
    val currentItem: UpNextItem? = null,
    val upNext: List<UpNextItem> = emptyList(),
    val excludedItems: List<UpNextItem> = emptyList(),
    val lastAnalyticsEvent: PlaybackAnalyticsEvent? = null
)

data class UpNextItem(
    val id: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val isExcluded: Boolean = false,
    val exclusionReason: String? = null
)

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
}

enum class PlaybackStartReason(@StringRes val labelRes: Int) {
    AUTO(R.string.player_start_reason_auto),
    USER_SELECTED(R.string.player_start_reason_user_selected),
    RESUME(R.string.player_start_reason_resume)
}

private fun stubUpNextItems(): List<UpNextItem> = listOf(
    UpNextItem(
        id = "intro_foundations",
        title = "Foundations Orientation",
        channelName = "Albunyaan Institute",
        durationSeconds = 630
    ),
    UpNextItem(
        id = "tafsir_baqara",
        title = "Tafsir Series - Al-Baqarah",
        channelName = "Albunyaan Institute",
        durationSeconds = 900
    ),
    UpNextItem(
        id = "youth_circle",
        title = "Youth Circle Q&A",
        channelName = "Albunyaan Youth",
        durationSeconds = 780
    ),
    UpNextItem(
        id = "community_roundtable",
        title = "Community Roundtable",
        channelName = "Community Submissions",
        durationSeconds = 540,
        isExcluded = true,
        exclusionReason = "Awaiting moderator approval"
    ),
    UpNextItem(
        id = "family_series",
        title = "Family Series: Parenting Essentials",
        channelName = "Albunyaan Family",
        durationSeconds = 840
    )
)
