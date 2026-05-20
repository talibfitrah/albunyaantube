@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.albunyaan.tube.ui.me

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.BuildConfig
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
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MeViewModel @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val feed: MeFeedRepository,
    private val favorites: FavoritesRepository,
    private val accountRepository: AccountRepository,
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

    // ANDROID-PERSONAL-03 / Bug 2: track only the WEEK INDICES the user has
    // loaded so far. Each indexed week observes its content live via
    // [MeFeedRepository.observeWeek]; the [weeks] StateFlow below derives
    // from those per-week flows so a cache mutation (worker upsert, ATOM
    // refresh, deep-page fill) immediately re-emits the affected week's
    // content without the user having to background and re-enter the
    // fragment.
    private val loadedWeekIndices = MutableStateFlow<List<Int>>(emptyList())

    /**
     * Per-week content for the rendered list. Live-derived from
     * [loadedWeekIndices] cross [filter]: every loaded weekIndex has its
     * own [MeFeedRepository.observeWeek] flow, and [filterChannelId] is
     * threaded through so a chip selection re-scopes ALL rendered weeks.
     *
     * Bug 2 fix: this used to be a `MutableStateFlow<List<WeekContent>>`
     * populated by appending in [loadNextWeek]. That meant a newly-cached
     * row never reached an already-rendered week — the user had to leave
     * the fragment and come back to see it. With the flatMapLatest +
     * combine derivation, every loaded week re-evaluates whenever its
     * underlying cache changes.
     *
     * Empty weeks are filtered out via [filterNotNull]. If filter changes
     * and the previously-loaded indices have no content for the filtered
     * channel, the rendered list shrinks accordingly. The init-side
     * collector ([init]) handles re-seeding `loadedWeekIndices` so the
     * user sees the FIRST non-empty week of the filtered channel.
     */
    val weeks: StateFlow<List<WeekContent>> =
        combine(loadedWeekIndices, filter) { indices, filterId -> indices to filterId }
            .flatMapLatest { (indices, filterId) ->
                if (indices.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(indices.map { idx -> feed.observeWeek(idx, filterId) }) { contents ->
                        contents.filterNotNull().toList()
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList(),
            )

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

    // Single in-flight opportunistic fill job. The opportunistic
    // background fillWeekIfNeeded fired when `hit != null` is fire-and-
    // forget, so a fast scroll could spawn many concurrent
    // fillWeekIfNeeded calls — and concurrent runDeepPageFor for the
    // same channel race on the refreshStateDao.upsert (TOCTOU on the
    // continuation token, leading to a clobbered token and YouTube
    // returning the same page twice). Guard with a single in-flight
    // job: if one fill is already running, skip; the next loadNextWeek
    // call will re-trigger.
    private var opportunisticFillJob: Job? = null

    init {
        // Kick off the first week load on construction so the user sees
        // content as soon as the screen renders.
        loadNextWeek()

        // Bug 1 fix: when the user changes the channel-chip filter, reset
        // the loaded-indices list and re-trigger a fresh load. Without
        // this, the existing indices (computed for the unfiltered set)
        // may all be empty for the filtered channel, and the user can't
        // page forward because [loadNextWeek] uses
        // `loadedWeekIndices.lastOrNull()` to compute the next start
        // index. We .drop(1) to skip the initial null emission so the
        // VM construction doesn't double-trigger [loadNextWeek].
        viewModelScope.launch {
            filter.drop(1).collect {
                resetLoadedWeeksAndRestart()
            }
        }

        // ANDROID-PERSONAL-03 round 6 [field-bug]: react to subscription
        // changes so the ViewModel doesn't stay stuck in `reachedEnd=true`
        // forever after a fresh-install user opens the Me tab BEFORE
        // adding any subscription. The first [loadNextWeek] at init runs
        // with zero subs, [findNextNonEmptyWeekIndex] returns null, and
        // we set `reachedEnd=true`. When the user later adds subs and
        // the worker populates the cache, no callback would re-trigger
        // a load — the user sees an empty Me screen even though the
        // cache is full. Watching the subscription COUNT (cheap, distinct)
        // lets us reset state when the set transitions empty→populated
        // OR when channels are added/removed at any time.
        viewModelScope.launch {
            subscriptions.observeSubscribedChannels()
                .map { it.size }
                .distinctUntilChanged()
                .drop(1) // skip the initial emission — init's first
                         // loadNextWeek() already covers the startup case
                .collect { _ ->
                    resetLoadedWeeksAndRestart()
                }
        }
    }

    /**
     * Bug 1 fix helper: cancel any in-flight load, clear loaded indices,
     * and start a fresh load. Called when [filter] changes so the user
     * sees the FIRST non-empty week of the (newly) filtered channel from
     * scratch.
     */
    private fun resetLoadedWeeksAndRestart() {
        loadJob?.cancel()
        loadJob = null
        loadedWeekIndices.value = emptyList()
        reachedEndState.value = false
        isLoadingMoreWeeksState.value = false
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
        if (reachedEndState.value) {
            if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: skipped — reachedEnd=true")
            return
        }
        if (loadJob?.isActive == true) {
            if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: skipped — already loading")
            return
        }
        loadJob = viewModelScope.launch {
            isLoadingMoreWeeksState.value = true
            try {
                val startIndex = (loadedWeekIndices.value.lastOrNull() ?: -1) + 1
                if (startIndex > WeekBucket.MAX_WEEKS_BACK) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: startIndex=$startIndex > MAX (${WeekBucket.MAX_WEEKS_BACK}) → reachedEnd")
                    reachedEndState.value = true
                    return@launch
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: startIndex=$startIndex filter=${filter.value}")
                // Snapshot the active filter once so a flip mid-load won't
                // see a half-applied filter; the filter-change collector
                // cancels this job anyway, so the snapshot is just defence.
                val activeFilter = filter.value
                // ANDROID-PERSONAL-03 round 5: jump-to-next-non-empty-week.
                // The previous loop incremented i one week at a time, firing a
                // deep-page round at every empty week — so a channel whose
                // newest post is 26 weeks old took 25 sequential rounds to
                // surface. Now we ask the repository where the next non-empty
                // week is in the cache, fire ONE deep-page round if the cache
                // is empty in that range, then re-ask. The deep-paginator
                // returns ~30 items per channel, which usually populate
                // multiple far-apart weeks at once — and we jump directly to
                // the earliest one rather than walking through gaps.
                var hit = feed.findNextNonEmptyWeekIndex(
                    fromIndex = startIndex,
                    filterChannelId = activeFilter,
                )
                // ANDROID-PERSONAL-03 round 8 [field-bug]: opportunistic
                // background fill. With the new candidate logic (channels
                // whose oldest cached row is newer than the requested
                // bucket) it's possible for `hit` to be non-null (some
                // history-rich channel populates week N) while OTHER
                // channels' deep-paging hasn't reached week N yet. The
                // user would see the rich channel's videos but miss the
                // shallower channels' content for that week. Fire a
                // non-blocking fill round so those lagging channels
                // catch up; Room's invalidation will re-emit the week
                // and the UI will update without another scroll.
                //
                // Round 8 review (P0): guard with `opportunisticFillJob`
                // — concurrent fillWeekIfNeeded calls for the same
                // channel race on refreshStateDao.upsert and clobber the
                // saved continuation token. If a fill is already in
                // flight, skip; the next loadNextWeek will re-trigger.
                if (hit != null && opportunisticFillJob?.isActive != true) {
                    opportunisticFillJob = viewModelScope.launch {
                        feed.fillWeekIfNeeded(startIndex)
                    }
                }
                if (hit == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: cache empty for week>=$startIndex, looping fillWeekIfNeeded")
                    // Cache empty across [startIndex, MAX_WEEKS_BACK]. Fire
                    // deep-paging until either:
                    //  - content surfaces in week >= startIndex, OR
                    //  - a deep-page round produces zero new rows (all
                    //    candidate channels EndOfChannel-or-error).
                    //
                    // ANDROID-PERSONAL-03 round 8 [field-bug]: a single
                    // round of fillWeekIfNeeded fetches ONE NewPipe page
                    // (~100 items) per candidate channel. For a high-volume
                    // channel (e.g. Dr. Othman Alkamees, ~15 uploads/day),
                    // 100 items is ~7 days — all in week 0. When the user
                    // is past week 0 and filters to that channel, none of
                    // those 100 land in the requested window, so the prior
                    // single-shot logic immediately set reachedEnd=true
                    // even though `hasNext=true` was reported.
                    //
                    // The loop keeps advancing the saved continuation token
                    // until older content shows up. MAX_DEEP_PAGE_ITERATIONS
                    // is a safety bound: 30 pages × 100 items = 3000 items
                    // per channel per loadNextWeek call, generous for any
                    // realistic channel. The progress check (rows-changed)
                    // exits early when channels stop yielding new items —
                    // typically because they hit EndOfChannel and got
                    // sentinel-marked, removing them from candidates next
                    // round.
                    var iterations = 0
                    while (hit == null && iterations < MAX_DEEP_PAGE_ITERATIONS) {
                        val rowsBefore = feed.countCachedRowsForFilter(activeFilter)
                        feed.fillWeekIfNeeded(startIndex)
                        val rowsAfter = feed.countCachedRowsForFilter(activeFilter)
                        if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: iteration ${iterations + 1} added ${rowsAfter - rowsBefore} rows (filter=$activeFilter)")
                        if (rowsAfter == rowsBefore) {
                            // No new rows → all relevant channels are
                            // exhausted or erroring. Don't loop forever.
                            break
                        }
                        hit = feed.findNextNonEmptyWeekIndex(
                            fromIndex = startIndex,
                            filterChannelId = activeFilter,
                        )
                        iterations++
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: after $iterations deep-page iteration(s), hit=$hit")
                }
                if (hit == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: no content found even after deep-page loop → reachedEnd=true")
                    // No content in any week >= startIndex even after deep-paging.
                    // All eligible channels exhausted (or returned nothing useful).
                    reachedEndState.value = true
                    return@launch
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "loadNextWeek: appending week $hit (startIndex was $startIndex)")
                val content = feed.observeWeek(hit, activeFilter).first()
                if (content != null) {
                    // Bug 2 fix: append the index, NOT the snapshot. The
                    // weeks StateFlow re-emits live as the underlying
                    // observeWeek flow re-emits, so cache mutations
                    // arriving after this load reach the UI without
                    // requiring another loadNextWeek call.
                    loadedWeekIndices.value = loadedWeekIndices.value + hit
                } else {
                    // Race: cache changed between findNextNonEmptyWeekIndex and
                    // observeWeek (e.g., user unsubscribed mid-flow). Retry
                    // once on next user scroll rather than infinite-looping.
                    reachedEndState.value = false
                }
            } finally {
                isLoadingMoreWeeksState.value = false
            }
        }
    }

    @VisibleForTesting
    internal fun resetWeeksForTest() {
        loadJob?.cancel()
        loadJob = null
        loadedWeekIndices.value = emptyList()
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
        // Skip when nothing changes — a no-op click should not blank the
        // feed or fire a new deep-page round.
        if (filter.value == channelId) return

        // Clear loadedWeekIndices SYNCHRONOUSLY before flipping the filter.
        // Without this there is a brief window where the `weeks` combine
        // re-runs the still-loaded indices against the new filter; if the
        // newly filtered channel has no content in those weeks the UI
        // emits an empty list — visually identical to "no results", which
        // made users tap the chip a second time before the deep-page
        // round triggered by [resetLoadedWeeksAndRestart] could repopulate
        // the indices. Clearing first makes the empty render explicitly a
        // loading state, then the filter.drop(1) collector kicks off the
        // fresh load via [resetLoadedWeeksAndRestart].
        loadedWeekIndices.value = emptyList()
        reachedEndState.value = false
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

    /**
     * K1: one-shot read of the current role from the account state.
     * Returns the role string if the user is signed in and the account
     * is loaded, or an empty string otherwise. Used by [MeFragment]'s
     * MenuProvider (K2) to decide whether to show "Suggest content".
     */
    fun snapshotRole(): String {
        val s = accountRepository.accountState.value
        return (s as? AccountState.Loaded)?.role.orEmpty()
    }

    companion object {
        private const val TAG = "MeViewModel"

        /**
         * Hard upper bound on how many deep-page rounds [loadNextWeek] will
         * fire while searching for content older than `startIndex` weeks.
         *
         * 30 rounds × ~100 items per page ≈ 3000 items per channel per
         * scroll. The progress check (rows-added) usually exits much
         * earlier — this bound only protects against a pathological case
         * where NewPipe keeps returning items but they're all newer than
         * the requested window (e.g. a channel that just uploaded 3000
         * Shorts in 24 hours and the user is scrolled to week 8).
         */
        private const val MAX_DEEP_PAGE_ITERATIONS: Int = 30
    }
}
