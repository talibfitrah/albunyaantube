@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.albunyaan.tube.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MeViewModel @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val feed: MeFeedRepository,
    private val favorites: FavoritesRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<String?>(null)

    val state: StateFlow<MeFeedState> = combine(
        subscriptions.observeSubscribedChannels(),
        subscriptions.observeSavedPlaylists(),
        feed.observeFeed(),
        filter,
        // T10: favorites are observed alongside subs/playlists/feed so the
        // row reacts immediately to add/remove from anywhere (player heart,
        // long-press snackbar, full Favorites screen).
        favorites.getAllFavorites(),
    ) { channels, playlists, cached, filterId, favs ->
        buildState(channels, playlists, cached, filterId, favs)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MeFeedState.Loading,
    )

    /**
     * T11: paginated long-form videos for the Me-tab grid. Independent of
     * [state] — the fragment collects this into its [MeVideosPagingAdapter]
     * directly rather than reading from [MeFeedState.Content.videos]. The
     * [filter] flow drives re-pagination (the chip toggle re-creates the
     * Pager); the row is mapped to the [MeFeedVideo] UI shape and cached
     * in the view-model scope so config-changes don't refetch the first
     * page.
     */
    val pagedVideos: Flow<PagingData<MeFeedVideo>> = filter
        .flatMapLatest { filterId -> feed.pagedFeed(filterId) }
        .map { pagingData -> pagingData.map { row -> row.toUi() } }
        .cachedIn(viewModelScope)

    // ANDROID-PERSONAL-02 / T9: the prior `init { refreshFeed(force = false) }`
    // was removed in favour of WorkManager-driven refresh:
    //  - hourly periodic worker (armed at app cold start),
    //  - foreground burst on MeFragment.onResume (when stale),
    //  - pull-to-refresh enqueues a force=true one-shot.
    // The view-model is now cache-only — observeFeed emits whatever is in
    // the Room cache window, the worker mutates it on its own cadence.

    fun setFilter(channelId: String?) {
        filter.value = channelId
    }

    private fun buildState(
        channels: List<SubscribedChannel>,
        playlists: List<SavedPlaylist>,
        cached: List<ChannelVideoCache>,
        filterId: String?,
        favorites: List<FavoriteVideo>,
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
            // T9: SwipeRefreshLayout spinner is now driven directly by the
            // WorkInfo observation in MeFragment. The state-flow's
            // `refreshing` flag is kept (false) for adapter-binding stability
            // until T11 lands its own UI surfaces.
            refreshing = false,
            filterChannelId = filterId,
            // T10: ordering preserved as the DAO returns it (DESC by addedAt).
            // The adapter caps to MAX_TILES; we pass the full list through so
            // future UIs (full Favorites screen) can reuse the flow source.
            favorites = favorites,
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
