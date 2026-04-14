package com.albunyaan.tube.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FollowedChannelsRepository
import com.albunyaan.tube.data.shorts.ShortsFeedRepository
import com.albunyaan.tube.data.shorts.ShortsItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the custom shorts player.
 *
 * Holds the list of [ShortsItem]s, exposes the current page index, and delegates
 * like/follow actions to the [FavoritesRepository] / [FollowedChannelsRepository].
 *
 * When launched from a channel (channelId != null) the feed items come back from
 * [ShortsFeedRepository] without channel metadata (see ShortsFeedRepository — the
 * ContentItem.Video model has no channel fields), so we enrich the items once with
 * data from [ChannelDetailRepository.getChannelHeader]. If channelId is null the
 * items keep their empty channel fields and the UI should gracefully hide the
 * channel row.
 */
class ShortsPlayerViewModel @AssistedInject constructor(
    private val feed: ShortsFeedRepository,
    private val favorites: FavoritesRepository,
    private val follows: FollowedChannelsRepository,
    private val channelDetailRepo: ChannelDetailRepository,
    @Assisted("initialShortId") private val initialShortId: String?,
    @Assisted("channelId") private val channelId: String?
) : ViewModel() {

    private val _items = MutableStateFlow<List<ShortsItem>>(emptyList())
    val items: StateFlow<List<ShortsItem>> = _items.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var nextCursor: String? = null
    private var loading = false
    private var exhausted = false
    private var cachedChannelHeader: ChannelHeader? = null

    init {
        loadNextPage()
    }

    fun onPageChanged(index: Int) {
        _currentIndex.value = index
        if (!exhausted && !loading && index >= _items.value.size - PREFETCH_THRESHOLD) {
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
     * Signals a playback error for the given page. The fragment observes [loadError]
     * and advances the pager by one when it sees a `skip:<id>` payload.
     */
    fun onPlaybackError(index: Int) {
        val id = _items.value.getOrNull(index)?.id ?: return
        _loadError.value = "skip:$id"
    }

    private fun loadNextPage() {
        if (loading || exhausted) return
        loading = true
        viewModelScope.launch {
            runCatching {
                val header = ensureChannelHeader()
                val page = feed.loadFeedPage(nextCursor)
                val decorated = if (header != null) {
                    page.items.map { it.withChannelHeader(header) }
                } else {
                    page.items
                }
                val combined = _items.value + decorated
                val ordered = initialShortId?.let { id ->
                    val head = combined.firstOrNull { it.id == id }
                    if (head != null) listOf(head) + combined.filter { it.id != id } else combined
                } ?: combined
                _items.value = ordered
                nextCursor = page.nextCursor
                exhausted = page.nextCursor == null
            }.onFailure {
                _loadError.value = "load:${it.message}"
            }
            loading = false
        }
    }

    private suspend fun ensureChannelHeader(): ChannelHeader? {
        val id = channelId ?: return null
        cachedChannelHeader?.let { return it }
        return runCatching { channelDetailRepo.getChannelHeader(id) }
            .onSuccess { cachedChannelHeader = it }
            .getOrNull()
    }

    private fun ShortsItem.withChannelHeader(header: ChannelHeader): ShortsItem {
        val resolvedChannelId = header.id.ifBlank { this@ShortsPlayerViewModel.channelId ?: "" }
        return copy(
            channelId = resolvedChannelId,
            channelName = header.title,
            channelAvatarUrl = header.avatarUrl
        )
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
