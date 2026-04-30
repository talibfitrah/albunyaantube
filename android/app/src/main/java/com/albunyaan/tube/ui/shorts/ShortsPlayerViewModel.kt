package com.albunyaan.tube.ui.shorts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FollowedChannelsRepository
import com.albunyaan.tube.data.shorts.ShortsFeedRepository
import com.albunyaan.tube.data.shorts.ShortsItem
import com.albunyaan.tube.player.QualityTrackSelector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * ViewModel for the custom shorts player.
 *
 * Holds the list of [ShortsItem]s, exposes the current page index, and delegates
 * like/follow actions to the [FavoritesRepository] / [FollowedChannelsRepository].
 *
 * When launched from a channel (channelId != null) items come from the
 * channel-scoped feed ([ShortsFeedRepository.loadChannelShortsPage]) and are
 * decorated once with [ChannelDetailRepository.getChannelHeader]. Otherwise the
 * global UNDER_FOUR_MIN feed is used and channel fields stay empty.
 *
 * Transient playback / load failures are emitted via [events] as one-shot
 * [LoadEvent]s (a [SharedFlow]). This avoids the "can't emit same value twice"
 * and "fragment must clear the state" foot-guns of StateFlow<String?>.
 */
class ShortsPlayerViewModel @AssistedInject constructor(
    @ApplicationContext context: Context,
    private val feed: ShortsFeedRepository,
    private val favorites: FavoritesRepository,
    private val follows: FollowedChannelsRepository,
    private val channelDetailRepo: ChannelDetailRepository,
    private val bufferPolicy: com.albunyaan.tube.player.AdaptiveBufferPolicy,
    private val featureFlags: com.albunyaan.tube.player.PlaybackFeatureFlags,
    private val neverFreezeTrackSelectionFactory: com.albunyaan.tube.player.NeverFreezeTrackSelectionFactory,
    @Assisted("initialShortId") private val initialShortId: String?,
    @Assisted("channelId") private val channelId: String?
) : ViewModel() {

    /**
     * ExoPlayer owned by the ViewModel so it survives configuration changes
     * (e.g. rotation) and is released exactly once in [onCleared]. The fragment
     * attaches/detaches this player from PlayerViews via PlayerBinder.
     *
     * Mirrors the main PlayerFragment's setup so shorts get the same buffering
     * behaviour: AdaptiveBufferPolicy sizes buffers by device memory class
     * (smaller on low-end, larger on high-end) — substantially reducing
     * stalls on weak networks. Decoder fallback + audio-becoming-noisy +
     * NETWORK wake-mode mirror PlayerFragment.kt setupPlayer().
     */
    val player: ExoPlayer by lazy {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val trackSelector = if (featureFlags.isNeverFreezeAbrEnabled) {
            QualityTrackSelector(context, neverFreezeTrackSelectionFactory.create())
        } else {
            QualityTrackSelector.createForDiscreteQualities(context)
        }
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(bufferPolicy.buildLoadControl())
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
    }

    private var initialShortApplied = false

    private val _items = MutableStateFlow<List<ShortsItem>>(emptyList())
    val items: StateFlow<List<ShortsItem>> = _items.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _events = MutableSharedFlow<LoadEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<LoadEvent> = _events.asSharedFlow()

    private var nextCursor: String? = null
    private var exhausted = false
    private var cachedChannelHeader: ChannelHeader? = null

    /**
     * Serializes [loadNextPage] to prevent a concurrent double-fetch when two
     * [onPageChanged] calls race past the pre-launch check. tryLock() returns
     * false immediately if a load is already in flight, so the second call
     * is dropped (next prefetch trigger will try again on the new cursor).
     */
    private val loadMutex = Mutex()

    init {
        loadNextPage()
    }

    fun onPageChanged(index: Int) {
        _currentIndex.value = index
        if (!exhausted && index >= _items.value.size - PREFETCH_THRESHOLD) {
            loadNextPage()
        }
    }

    fun toggleLike(index: Int) {
        val item = _items.value.getOrNull(index) ?: return
        viewModelScope.launch {
            favorites.toggleFavorite(
                item.id,
                item.title,
                item.channelName,
                item.thumbnailUrl,
                item.durationSeconds
            )
        }
    }

    fun toggleFollow(index: Int) {
        val item = _items.value.getOrNull(index) ?: return
        if (item.channelId.isBlank()) return
        viewModelScope.launch {
            follows.toggleFollow(item.channelId, item.channelName, item.channelAvatarUrl)
        }
    }

    fun isLikedFlow(videoId: String): Flow<Boolean> = favorites.isFavorite(videoId)

    fun isFollowedFlow(channelId: String): Flow<Boolean> = follows.isFollowed(channelId)

    /**
     * Signals a playback error for the given page. The fragment collects
     * [events] and advances the pager by one when it observes a
     * [LoadEvent.SkipCurrent] carrying the offending short id.
     */
    fun onPlaybackError(index: Int) {
        val id = _items.value.getOrNull(index)?.id ?: return
        _events.tryEmit(LoadEvent.SkipCurrent(id))
    }

    private fun loadNextPage() {
        if (exhausted) return
        viewModelScope.launch {
            // tryLock serializes concurrent callers — the second caller
            // observes a locked mutex and bails out immediately. This replaces
            // the prior `loading` boolean which had a check/set race.
            if (!loadMutex.tryLock()) return@launch
            try {
                if (exhausted) return@launch
                runCatching {
                    val header = ensureChannelHeader()
                    val page = if (channelId != null) {
                        feed.loadChannelShortsPage(channelId, nextCursor)
                    } else {
                        feed.loadFeedPage(nextCursor)
                    }
                    val decorated = if (header != null) {
                        page.items.map { it.withChannelHeader(header) }
                    } else {
                        page.items
                    }
                    val combined = _items.value + decorated
                    val ordered = if (!initialShortApplied && initialShortId != null) {
                        val head = combined.firstOrNull { it.id == initialShortId }
                        initialShortApplied = true
                        if (head != null) listOf(head) + combined.filter { it.id != initialShortId } else combined
                    } else {
                        combined
                    }
                    _items.value = ordered
                    nextCursor = page.nextCursor
                    exhausted = page.nextCursor == null
                }.onFailure {
                    // runCatching catches CancellationException too — rethrow so
                    // user-initiated cancellation (fragment destroyed, VM cleared)
                    // doesn't surface a misleading "Job was cancelled" toast.
                    if (it is CancellationException) throw it
                    _events.tryEmit(LoadEvent.LoadError(it.message ?: "load failed"))
                }
            } finally {
                loadMutex.unlock()
            }
        }
    }

    private suspend fun ensureChannelHeader(): ChannelHeader? {
        val id = channelId ?: return null
        cachedChannelHeader?.let { return it }
        val header = try {
            channelDetailRepo.getChannelHeader(id)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            return null
        }
        cachedChannelHeader = header
        // Retroactively decorate items that were loaded before the header
        // resolved (e.g. header failed on page 1 and succeeded on page 2).
        // Without this, page-1 items would keep blank channelName forever.
        val current = _items.value
        if (current.any { it.channelName.isBlank() }) {
            _items.value = current.map { item ->
                if (item.channelName.isBlank()) item.withChannelHeader(header) else item
            }
        }
        return header
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private fun ShortsItem.withChannelHeader(header: ChannelHeader): ShortsItem {
        val resolvedChannelId = header.id.ifBlank { this@ShortsPlayerViewModel.channelId ?: "" }
        return copy(
            channelId = resolvedChannelId,
            channelName = header.title,
            channelAvatarUrl = header.avatarUrl
        )
    }

    sealed interface LoadEvent {
        data class SkipCurrent(val shortId: String) : LoadEvent
        data class LoadError(val message: String) : LoadEvent
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("initialShortId") initialShortId: String?,
            @Assisted("channelId") channelId: String?
        ): ShortsPlayerViewModel
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 3
    }
}
