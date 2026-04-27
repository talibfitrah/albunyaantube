@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.albunyaan.tube.ui.me

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.data.me.WeekBucket
import com.albunyaan.tube.data.me.WeekContent
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    // ANDROID-PERSONAL-03 / T5: per-week state. Each [WeekContent] in the
    // list represents one rendered, non-empty week. [loadNextWeek] appends
    // additional weeks as the user scrolls; empty weeks are skipped entirely
    // (no placeholder). Bounded at [WeekBucket.MAX_WEEKS_BACK] (1 year of
    // history) so an infinite-scroll loop can't run forever.
    private val weeksState = MutableStateFlow<List<WeekContent>>(emptyList())
    val weeks: StateFlow<List<WeekContent>> = weeksState.asStateFlow()

    // Loading flag for the load-more sentinel. Used by [MeFragment] to
    // show / hide a footer spinner and to debounce re-entrant
    // [loadNextWeek] calls.
    private val isLoadingMoreWeeksState = MutableStateFlow(false)
    val isLoadingMoreWeeks: StateFlow<Boolean> = isLoadingMoreWeeksState.asStateFlow()

    // Set once we've walked from the last loaded weekIndex past
    // [WeekBucket.MAX_WEEKS_BACK] without finding a non-empty week. Used
    // to suppress further [loadNextWeek] calls without expensive scans.
    private val reachedEndState = MutableStateFlow(false)
    val reachedEnd: StateFlow<Boolean> = reachedEndState.asStateFlow()

    // Single in-flight job to prevent overlapping loads — the fragment's
    // scroll listener can fire many times in rapid succession.
    private var loadJob: Job? = null

    init {
        // Kick off the first week load on construction so the user sees
        // content as soon as the screen renders.
        loadNextWeek()
    }

    /**
     * ANDROID-PERSONAL-03 / T5: append the next non-empty week to [weeks].
     *
     * Walks forward starting at `(last loaded weekIndex) + 1`:
     *  - if [MeFeedRepository.observeWeek] emits non-null, append it and stop.
     *  - if it emits null and we're still under [WeekBucket.MAX_WEEKS_BACK],
     *    call [MeFeedRepository.fillWeekIfNeeded] and check again.
     *  - if still null, advance to the next week.
     *  - if we walk past [WeekBucket.MAX_WEEKS_BACK] without finding any,
     *    set [reachedEnd] and stop.
     *
     * Serialises via [loadJob] — concurrent fragment scroll listener
     * pings get coalesced into a single load.
     */
    fun loadNextWeek() {
        if (reachedEndState.value) return
        // Don't restart if a load is already running.
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            isLoadingMoreWeeksState.value = true
            try {
                val startIndex = (weeksState.value.lastOrNull()?.weekIndex ?: -1) + 1
                var i = startIndex
                while (i <= WeekBucket.MAX_WEEKS_BACK) {
                    // First try a cache-only read.
                    var content = feed.observeWeek(i).first()
                    if (content == null) {
                        // Cache miss — try to fill via NewPipe deep paging.
                        feed.fillWeekIfNeeded(i)
                        content = feed.observeWeek(i).first()
                    }
                    if (content != null) {
                        weeksState.value = weeksState.value + content
                        return@launch
                    }
                    i += 1
                }
                // Walked past the cap without finding a non-empty week.
                reachedEndState.value = true
            } finally {
                isLoadingMoreWeeksState.value = false
            }
        }
    }

    @VisibleForTesting
    internal fun resetWeeksForTest() {
        loadJob?.cancel()
        loadJob = null
        weeksState.value = emptyList()
        reachedEndState.value = false
        isLoadingMoreWeeksState.value = false
    }

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
