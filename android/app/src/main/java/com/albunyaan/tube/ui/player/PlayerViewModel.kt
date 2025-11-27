package com.albunyaan.tube.ui.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.R
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SubtitleTrack
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.policy.EulaManager
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * P3-T4: PlayerViewModel with Hilt DI
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: PlayerRepository,
    private val downloadRepository: DownloadRepository,
    private val eulaManager: EulaManager,
    @Named("real") private val contentService: ContentService
) : ViewModel() {

    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val queue = mutableListOf<UpNextItem>()
    private var currentItem: UpNextItem? = null
    private var resolveJob: Job? = null
    private var latestDownloads: List<DownloadEntry> = emptyList()

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
        observeDownloads()
        observeEulaAcceptance()
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

    fun downloadCurrent(): Boolean {
        val state = _state.value
        if (!state.isEulaAccepted) {
            return false
        }
        val item = state.currentItem ?: return false
        val request = DownloadRequest(
            id = item.streamId + "_" + System.currentTimeMillis(),
            title = item.title,
            videoId = item.streamId,
            audioOnly = _state.value.audioOnly
        )
        downloadRepository.enqueue(request)
        return true
    }

    private fun observeDownloads() {
        viewModelScope.launch(dispatcher) {
            downloadRepository.downloads.collect { entries ->
                latestDownloads = entries
                updateState { state ->
                    state.copy(currentDownload = findDownloadFor(state.currentItem, entries))
                }
            }
        }
    }

    private fun observeEulaAcceptance() {
        viewModelScope.launch(dispatcher) {
            eulaManager.isAccepted.collect { accepted ->
                updateState { it.copy(isEulaAccepted = accepted) }
            }
        }
    }

    fun acceptEula() {
        viewModelScope.launch(dispatcher) {
            eulaManager.setAccepted(true)
        }
    }

    /**
     * Get available quality options for current stream
     */
    fun getAvailableQualities(): List<QualityOption> {
        val streamState = _state.value.streamState
        if (streamState !is StreamState.Ready) return emptyList()

        val videoTracks = streamState.selection.resolved.videoTracks
        return videoTracks.mapNotNull { track ->
            track.qualityLabel?.let { label ->
                QualityOption(label, track)
            }
        }.sortedByDescending { it.track.height ?: 0 }
    }

    /**
     * Select a specific video quality
     */
    fun selectQuality(track: VideoTrack) {
        val streamState = _state.value.streamState
        if (streamState !is StreamState.Ready) return

        val audio = streamState.selection.audio
        val newSelection = PlaybackSelection(
            streamId = streamState.streamId,
            video = track,
            audio = audio,
            resolved = streamState.selection.resolved
        )

        updateState { it.copy(streamState = StreamState.Ready(streamState.streamId, newSelection)) }
        publishAnalytics(PlaybackAnalyticsEvent.QualityChanged(track.qualityLabel ?: "Unknown"))
    }

    /**
     * Get available subtitle/caption tracks
     */
    fun getAvailableSubtitles(): List<SubtitleTrack> {
        val streamState = _state.value.streamState
        if (streamState !is StreamState.Ready) return emptyList()

        return streamState.selection.resolved.subtitleTracks
    }

    /**
     * Get currently selected subtitle track
     */
    fun getSelectedSubtitle(): SubtitleTrack? {
        return _state.value.selectedSubtitle
    }

    /**
     * Select a subtitle track (or null to disable subtitles)
     */
    fun selectSubtitle(track: SubtitleTrack?) {
        updateState { it.copy(selectedSubtitle = track) }
        track?.let {
            publishAnalytics(PlaybackAnalyticsEvent.SubtitleChanged(it.languageName))
        } ?: run {
            publishAnalytics(PlaybackAnalyticsEvent.SubtitleChanged("Off"))
        }
    }

    /**
     * Load and play a specific video by ID
     */
    fun loadVideo(videoId: String, title: String = "Video") {
        viewModelScope.launch(dispatcher) {
            updateState { it.copy(streamState = StreamState.Loading) }

            try {
                // Fetch video metadata from backend
                val response = contentService.fetchContent(
                    type = ContentType.VIDEOS,
                    cursor = null,
                    pageSize = 100,
                    filters = FilterState()
                )

                val video = response.data
                    .filterIsInstance<ContentItem.Video>()
                    .firstOrNull { it.id == videoId }

                if (video != null) {
                    val item = UpNextItem(
                        id = video.id,
                        title = video.title,
                        channelName = "Albunyaan", // TODO: Add channel name to Video model
                        durationSeconds = video.durationSeconds,
                        streamId = video.id,
                        thumbnailUrl = video.thumbnailUrl,
                        description = video.description,
                        viewCount = video.viewCount
                    )
                    currentItem = item
                    queue.clear()
                    applyQueueState()
                    publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(item, PlaybackStartReason.USER_SELECTED))
                    resolveStreamFor(item, PlaybackStartReason.USER_SELECTED)
                } else {
                    // Fallback to basic item if video not found
                    val item = UpNextItem(
                        id = videoId,
                        title = title,
                        channelName = "Albunyaan",
                        durationSeconds = 0,
                        streamId = videoId
                    )
                    currentItem = item
                    queue.clear()
                    applyQueueState()
                    publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(item, PlaybackStartReason.USER_SELECTED))
                    resolveStreamFor(item, PlaybackStartReason.USER_SELECTED)
                }
            } catch (e: Exception) {
                // Fallback to basic item on error
                val item = UpNextItem(
                    id = videoId,
                    title = title,
                    channelName = "Albunyaan",
                    durationSeconds = 0,
                    streamId = videoId
                )
                currentItem = item
                queue.clear()
                applyQueueState()
                publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(item, PlaybackStartReason.USER_SELECTED))
                resolveStreamFor(item, PlaybackStartReason.USER_SELECTED)
            }
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
                excludedItems = excluded,
                currentDownload = findDownloadFor(currentItem, latestDownloads)
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
                upNext = queue.toList(),
                currentDownload = findDownloadFor(currentItem, latestDownloads)
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

    private fun findDownloadFor(item: UpNextItem?, entries: List<DownloadEntry>): DownloadEntry? {
        return item?.let { current ->
            entries.firstOrNull { it.request.videoId == current.streamId }
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
    val currentDownload: DownloadEntry? = null,
    val isEulaAccepted: Boolean = false,
    val streamState: StreamState = StreamState.Idle,
    val selectedSubtitle: SubtitleTrack? = null,
    val lastAnalyticsEvent: PlaybackAnalyticsEvent? = null
)

data class UpNextItem(
    val id: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val isExcluded: Boolean = false,
    val exclusionReason: String? = null,
    val streamId: String,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val viewCount: Long? = null
)

sealed class StreamState {
    object Idle : StreamState()
    object Loading : StreamState()
    data class Ready(val streamId: String, val selection: PlaybackSelection) : StreamState()
    data class Error(@StringRes val messageRes: Int) : StreamState()
}

data class QualityOption(
    val label: String,
    val track: VideoTrack
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

    data class StreamResolved(val streamId: String, val qualityLabel: String?) : PlaybackAnalyticsEvent()

    data class StreamFailed(val streamId: String) : PlaybackAnalyticsEvent()

    data class QualityChanged(val qualityLabel: String) : PlaybackAnalyticsEvent()

    data class SubtitleChanged(val languageName: String) : PlaybackAnalyticsEvent()
}

enum class PlaybackStartReason(@StringRes val labelRes: Int) {
    AUTO(R.string.player_start_reason_auto),
    USER_SELECTED(R.string.player_start_reason_user_selected),
    RESUME(R.string.player_start_reason_resume)
}

/**
 * Smart quality selection based on available tracks.
 * Selects 720p as default for balance between quality and loading speed.
 * Users can manually switch to higher quality if needed.
 */
private fun ResolvedStreams.toDefaultSelection(): PlaybackSelection? {
    // Smart quality selection: prefer 720p for faster loading, fallback to best available
    val preferredVideo = videoTracks.firstOrNull { it.height == 720 }
        ?: videoTracks.firstOrNull { it.height == 480 }
        ?: videoTracks.maxWithOrNull(compareBy<VideoTrack> { it.height ?: 0 }
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
