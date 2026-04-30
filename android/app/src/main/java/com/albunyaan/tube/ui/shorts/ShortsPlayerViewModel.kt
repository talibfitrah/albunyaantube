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
    @Assisted("channelId") private val channelId: String?,
    @Assisted("initialShortTitle") private val initialShortTitle: String? = null,
    @Assisted("initialChannelName") private val initialChannelName: String? = null,
    @Assisted("initialThumbnailUrl") private val initialThumbnailUrl: String? = null,
    @Assisted("initialChannelAvatarUrl") private val initialChannelAvatarUrl: String? = null,
    @Assisted("initialDurationSeconds") private val initialDurationSeconds: Int = 0,
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
    /** Track selector kept at class scope so the kebab quality picker can call
     *  applyQualityConstraint / clear after the player is built. Initialised
     *  lazily alongside [player]. */
    private lateinit var trackSelector: QualityTrackSelector

    /** User-applied quality cap (height in px). 0 = AUTO (no cap). Surfaced
     *  to the kebab picker so it pre-checks the active row. */
    private var userQualityCapHeight: Int = 0

    val player: ExoPlayer by lazy {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val selector = if (featureFlags.isNeverFreezeAbrEnabled) {
            QualityTrackSelector(context, neverFreezeTrackSelectionFactory.create())
        } else {
            QualityTrackSelector.createForDiscreteQualities(context)
        }
        trackSelector = selector
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(bufferPolicy.buildLoadControl())
            .setTrackSelector(selector)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
    }

    /**
     * Apply a user-selected quality cap to the active player track selector.
     * `heightPx == 0` clears the cap (auto / ABR-driven).
     *
     * Uses CAP so the player picks the highest track ≤ heightPx and ABR can
     * step down on network congestion. CAP doesn't lock the rendition, so a
     * stall won't get stuck at the picked height — useful for shorts which
     * loop and would otherwise drain battery on a single buffering rendition.
     */
    fun applyQualityCap(heightPx: Int) {
        // Touch player so trackSelector is initialised even if caller hits
        // this before the first playback.
        player
        if (heightPx <= 0) {
            userQualityCapHeight = 0
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .build()
        } else {
            userQualityCapHeight = heightPx
            trackSelector.applyQualityConstraint(
                heightPx,
                com.albunyaan.tube.data.extractor.QualityConstraintMode.CAP
            )
        }
    }

    /** Currently applied user quality cap; 0 means AUTO. */
    fun getUserQualityCap(): Int = userQualityCapHeight

    /**
     * Standard quality ladder for the kebab picker. Hard-coded list (highest →
     * lowest) so the dialog renders even before the resolved-streams metadata
     * lands. Heights that the active stream doesn't actually publish are still
     * harmless — CAP_STRICT just picks the next-lower available track.
     */
    fun getQualityOptions(): List<Pair<Int, String>> = listOf(
        2160 to "2160p",
        1440 to "1440p",
        1080 to "1080p",
        720 to "720p",
        480 to "480p",
        360 to "360p",
        240 to "240p",
        144 to "144p",
    )

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
        seedInitialShortIfAvailable()
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
                    val combined = appendDistinct(_items.value, decorated)
                    val ordered = if (!initialShortApplied && initialShortId != null) {
                        val head = combined.firstOrNull { it.id == initialShortId }
                        if (head != null) {
                            initialShortApplied = true
                            listOf(head) + combined.filter { it.id != initialShortId }
                        } else {
                            if (page.nextCursor == null) initialShortApplied = true
                            combined
                        }
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
        if (current.any { it.channelName.isBlank() || it.channelAvatarUrl.isNullOrBlank() || it.channelId.isBlank() }) {
            _items.value = current.map { item ->
                if (item.channelName.isBlank() || item.channelAvatarUrl.isNullOrBlank() || item.channelId.isBlank()) {
                    item.withChannelHeader(header)
                } else {
                    item
                }
            }
        }
        return header
    }

    private fun seedInitialShortIfAvailable() {
        val id = initialShortId?.takeIf { it.isNotBlank() } ?: return
        val title = initialShortTitle?.takeIf { it.isNotBlank() } ?: return
        _items.value = listOf(
            ShortsItem(
                id = id,
                title = title,
                channelId = channelId.orEmpty(),
                channelName = initialChannelName.orEmpty(),
                channelAvatarUrl = initialChannelAvatarUrl,
                thumbnailUrl = initialThumbnailUrl,
                durationSeconds = initialDurationSeconds.coerceAtLeast(0),
            )
        )
        initialShortApplied = true
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private fun ShortsItem.withChannelHeader(header: ChannelHeader): ShortsItem {
        val resolvedChannelId = header.id.ifBlank { this@ShortsPlayerViewModel.channelId ?: "" }
        return copy(
            channelId = resolvedChannelId,
            channelName = header.title.ifBlank { channelName },
            channelAvatarUrl = header.avatarUrl ?: channelAvatarUrl
        )
    }

    private fun appendDistinct(current: List<ShortsItem>, incoming: List<ShortsItem>): List<ShortsItem> {
        if (incoming.isEmpty()) return current
        val seen = current.asSequence().map { it.id }.toMutableSet()
        return current + incoming.filter { seen.add(it.id) }
    }

    sealed interface LoadEvent {
        data class SkipCurrent(val shortId: String) : LoadEvent
        data class LoadError(val message: String) : LoadEvent
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("initialShortId") initialShortId: String?,
            @Assisted("channelId") channelId: String?,
            @Assisted("initialShortTitle") initialShortTitle: String?,
            @Assisted("initialChannelName") initialChannelName: String?,
            @Assisted("initialThumbnailUrl") initialThumbnailUrl: String?,
            @Assisted("initialChannelAvatarUrl") initialChannelAvatarUrl: String?,
            @Assisted("initialDurationSeconds") initialDurationSeconds: Int,
        ): ShortsPlayerViewModel
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 3
    }
}
