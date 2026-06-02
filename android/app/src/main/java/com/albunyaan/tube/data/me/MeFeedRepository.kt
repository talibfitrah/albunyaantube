package com.albunyaan.tube.data.me

import android.util.Log
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.local.ChannelFeedRefreshState
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.PlaylistVideoLink
import com.albunyaan.tube.data.local.PlaylistVideoLinkDao
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Orchestrates per-channel feed fetches for subscribed channels.
 *
 * Contract:
 *  - [observeFeed] is cache-only — it emits whatever is in the cache window
 *    (last [FEED_WINDOW_MS]) without triggering network work. Callers trigger
 *    refresh explicitly via [refresh].
 *  - [refresh] never starves YouTube: at most [MAX_CONCURRENT] concurrent
 *    fetches, staggered by [STAGGER_MS], capped at [MAX_CHANNELS_PER_REFRESH]
 *    channels per call, and only channels whose last successful fetch is
 *    older than [CACHE_TTL_MS] are refetched (unless force=true).
 *  - A single channel's failure never aborts another channel's fetch.
 *  - Concurrent [refresh] calls are serialised via [refreshMutex] so the
 *    250 ms stagger is never collapsed by overlapping invocations (F3).
 */
@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeFeedRepository @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val cache: ChannelVideoCacheDao,
    private val refreshStateDao: ChannelFeedRefreshStateDao,
    private val fetcher: ChannelFeedFetcher,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    // T12 (spec §10 P10): per-channel outcome events. Emit-only — the
    // repository does not read events back. Tests can pass a fresh
    // `MeRefreshTelemetry()` (`@Inject constructor()` makes it trivially
    // default-constructible) — its events flow drops on overflow so a
    // test that doesn't subscribe cannot stall.
    private val telemetry: MeRefreshTelemetry,
    // ANDROID-PERSONAL-03 / T4: NewPipe deep-paginator and Moshi for
    // serialising the page-token cookies. Both are nullable so test
    // fixtures that don't exercise the deep-paging path can pass null
    // — fillWeekIfNeeded then becomes a no-op.
    private val deepPaginator: ChannelDeepPaginator? = null,
    moshi: Moshi? = null,
    // Me-tab playlist videos: nullable so existing test fixtures that
    // exercise only the subscribed-channel path keep compiling without
    // having to wire a fake PlaylistDetailRepository. When either is
    // null, [refreshPlaylistVideos] is a no-op and the union-aware feed
    // queries fall back to the channel-only paths.
    private val playlistRepository: PlaylistDetailRepository? = null,
    private val playlistVideoLinkDao: PlaylistVideoLinkDao? = null,
    // B3: required for observeAwaiting(); always Hilt-injected in production.
    private val favoritesRepository: FavoritesRepository,
) {

    /**
     * Adapter for the `Map<String, String>` cookie JSON column. Built lazily
     * from the injected Moshi so we don't pay reflection cost when the
     * deep-paging path is never exercised. Tests that don't pass Moshi get a
     * minimal default-built adapter.
     */
    private val cookiesJsonAdapter: JsonAdapter<Map<String, String>> by lazy {
        val m = moshi ?: Moshi.Builder().build()
        m.adapter(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
        )
    }

    /**
     * ANDROID-PERSONAL-03 round 8 [field-bug]: serialise the full
     * [ChannelDeepPaginator.SerializedPage] payload (cookies, body, id, ids)
     * into the existing `deepPageCookiesJson` column. The previous adapter
     * only persisted cookies, dropping the `body` byte[] where YouTube's
     * playlist continuation token actually lives — every saved page would
     * round-trip with an empty token and NewPipe would return an empty
     * Page → misclassified as EndOfChannel.
     */
    private val deepPageStateJsonAdapter: JsonAdapter<DeepPageState> by lazy {
        // ANDROID-PERSONAL-03 round 8 review fix: tests don't inject Moshi,
        // so the fallback path also needs KotlinJsonAdapterFactory to
        // deserialize the Kotlin data class via reflection. Without it,
        // Moshi's default Java reflection silently produces a DeepPageState
        // with all-null fields, and round-tripped continuation cookies/
        // body get dropped.
        val m = moshi ?: Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        m.adapter(DeepPageState::class.java).serializeNulls()
    }

    /**
     * Serialised slice of [ChannelDeepPaginator.SerializedPage] excluding
     * `url` (which is persisted separately in `deepPageUrl`). `body` is
     * stored Base64-encoded so it survives JSON. All fields are nullable so
     * the JSON object can omit them. Adapter is reflection-based via the
     * [com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory] installed
     * by [com.albunyaan.tube.di.NetworkModule].
     */
    internal data class DeepPageState(
        val id: String?,
        val ids: List<String>?,
        val cookies: Map<String, String>?,
        val bodyB64: String?,
    )

    companion object {
        private const val TAG = "MeFeedRepository"
        const val CACHE_TTL_MS: Long = 30L * 60L * 1_000L
        const val FEED_WINDOW_MS: Long = 14L * 24L * 60L * 60L * 1_000L
        const val MAX_CONCURRENT: Int = 4
        const val STAGGER_MS: Long = 250L
        const val MAX_CHANNELS_PER_REFRESH: Int = 50
        const val MAX_ITEMS_PER_CHANNEL: Int = 30

        // Stage-5 round-2 [P2]: cap per-channel fetch so the refresh Mutex
        // cannot be held indefinitely by a hung NewPipe call on a slow
        // network. 15 s is generous for a well-behaved YouTube page + both
        // tab fetches and still keeps the worst-case Me-open within 15 s.
        const val PER_CHANNEL_TIMEOUT_MS: Long = 15_000L

        /**
         * ANDROID-PERSONAL-03 round 5 [field-bug]: NewPipe channel deep-paging
         * goes through [com.albunyaan.tube.data.extractor.RateLimitedDownloader]
         * with `Priority.USER_FOREGROUND` (not PLAYER, so it IS gated).
         * The token bucket is 20 tokens / 30 s refill, so worst case the 11th
         * concurrent caller waits ~15 s for a token before the HTTP call
         * even fires. Plus actual extraction time (~3-5 s). [PER_CHANNEL_TIMEOUT_MS]
         * = 15 s expires the entire window — including the rate-limiter wait —
         * before NewPipe gets a chance, so users see "loading… retry" loops on
         * channel scrapes that always succeeded pre-T7.
         *
         * 60 s gives the rate limiter room to drain + the HTTP call room to
         * complete. Auto-retry behaviour unchanged: a real network timeout
         * still returns [ChannelDeepPaginator.DeepPageResult.Error], the
         * channel is NOT marked exhausted, and the next [fillWeekIfNeeded]
         * iteration retries.
         */
        const val DEEP_PAGE_TIMEOUT_MS: Long = 60_000L

        // T9: per-channel exponential backoff (ATOM refresh, spec §5/§6).
        // 429 ladder: 1h → 4h → 24h. Each consecutive 429 advances one
        // step. Index = (consecutiveErrorCount - 1).coerceAtMost(2).
        internal val ATOM_429_BACKOFFS: List<Long> = listOf(
            60L * 60L * 1_000L,         // 1h
            4L * 60L * 60L * 1_000L,    // 4h
            24L * 60L * 60L * 1_000L,   // 24h
        )

        // T9: 5xx ladder is gentler — server errors are usually transient.
        // 5min → 30min → 2h.
        internal val ATOM_5XX_BACKOFFS: List<Long> = listOf(
            5L * 60L * 1_000L,          // 5 min
            30L * 60L * 1_000L,         // 30 min
            2L * 60L * 60L * 1_000L,    // 2h
        )

        // T9: error-message regex used to recognise 5xx responses thrown
        // by AtomChannelFeedFetcher as `IOException("HTTP ${code}")`.
        internal val HTTP_5XX_REGEX: Regex = Regex("""HTTP 5\d{2}""")
        internal val HTTP_429_REGEX: Regex = Regex("""HTTP 429\b|\b429\b""")

        /**
         * ANDROID-PERSONAL-03 / T4: sentinel value persisted in
         * `deepPageUrl` once a channel has been deep-paged to exhaustion
         * (NewPipe returned [ChannelDeepPaginator.DeepPageResult.EndOfChannel]).
         * On subsequent ticks [fillWeekIfNeeded] short-circuits this
         * channel — there's no point asking NewPipe for older items
         * that don't exist.
         *
         * `https://yt-eof` is not a valid YouTube continuation URL, so
         * collisions with real continuation tokens are impossible.
         */
        internal const val DEEP_PAGE_EOF_SENTINEL: String = "https://yt-eof"
    }

    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val refreshMutex = Mutex()

    /**
     * Cache-backed feed stream. The 14-day cutoff is recomputed every time
     * the upstream subscription list changes, so a screen left open for hours
     * will not show stale items past the window (F4).
     */
    fun observeFeed(): Flow<List<ChannelVideoCache>> =
        subscriptions.observeApprovedSubscribedChannels()
            .flatMapLatest { subs ->
                if (subs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    // Stage-5 round-2 [P1]: bound the IN-list size. Room
                    // expands `IN (:channelIds)` to one positional parameter
                    // per id, and SQLite's default SQLITE_MAX_VARIABLE_NUMBER
                    // is 999 on older Android. We also only refresh this many
                    // channels per call, so matching the cap here keeps the
                    // feed in sync with what refresh() can actually populate.
                    val channelIds = subs.asSequence()
                        .sortedByDescending { it.subscribedAt }
                        .take(MAX_CHANNELS_PER_REFRESH)
                        .map { it.channelId }
                        .toList()
                    val cutoff = currentTimeMillis() - FEED_WINDOW_MS
                    cache.observeRecentForChannels(channelIds, cutoff)
                }
            }
            .distinctUntilChanged()

    /**
     * B3: Combines the three AWAITING streams into a single [AwaitingImports]
     * flow. Emits whenever any of the three sets changes.
     */
    fun observeAwaiting(): Flow<AwaitingImports> =
        combine(
            subscriptions.observeAwaitingSubscribedChannels(),
            subscriptions.observeAwaitingSavedPlaylists(),
            favoritesRepository.observeAwaitingFavorites(),
        ) { channels, playlists, videos ->
            AwaitingImports(channels = channels, playlists = playlists, videos = videos)
        }

    /**
     * ANDROID-PERSONAL-03 / T4: per-week observation for the Me-tab feed.
     *
     * Returns a flow of [WeekContent] for the half-open `[start, end)`
     * window of [WeekBucket.forIndex(weekIndex, now)], scoped to the user's
     * subscribed channels. Emits null when the bucket is entirely empty so
     * the ViewModel can skip the week without rendering an empty
     * placeholder.
     *
     * Channel scoping mirrors [observeFeed] — newest-subscribed first,
     * capped at [MAX_CHANNELS_PER_REFRESH] so Room's `IN (...)` IN-list
     * stays under SQLite's positional parameter limit.
     *
     * Items are split into:
     *   - [WeekContent.shorts] (`isShort = true`)
     *   - [WeekContent.videos] (`isShort = false`)
     * both newest-first.
     *
     * Note that this is cache-only: the bucket window is recomputed every
     * time the upstream subscription list changes, so a screen left open
     * across week boundaries is automatically re-bucketed (the underlying
     * `now` is read each time the flow downstream collects).
     */
    fun observeWeek(
        weekIndex: Int,
        filterChannelId: String? = null,
    ): Flow<WeekContent?> =
        combine(
            subscriptions.observeApprovedSubscribedChannels(),
            subscriptions.observeApprovedSavedPlaylists(),
        ) { subs, playlists -> subs to playlists }
            .flatMapLatest<Pair<List<SubscribedChannel>, List<SavedPlaylist>>, WeekContent?> { (subs, playlists) ->
                // No subscriptions AND no saved playlists → nothing to show.
                if (subs.isEmpty() && playlists.isEmpty()) {
                    flowOf<WeekContent?>(null)
                } else {
                    // ANDROID-PERSONAL-03 / Bug 1: when [filterChannelId] is
                    // non-null, restrict the cache query to that single
                    // channel. The DAO's `IN (:channelIds)` query handles a
                    // single-element list trivially. We still validate that
                    // the requested channel is in the user's subscription
                    // set — silently dropping a stale filter prevents a
                    // ghost-channel chip (unsubscribed mid-flow) from
                    // surfacing items that shouldn't be visible.
                    val channelIds: List<String> = if (filterChannelId != null) {
                        if (subs.any { it.channelId == filterChannelId }) {
                            listOf(filterChannelId)
                        } else {
                            return@flatMapLatest flowOf<WeekContent?>(null)
                        }
                    } else {
                        subs.asSequence()
                            .sortedByDescending { it.subscribedAt }
                            .take(MAX_CHANNELS_PER_REFRESH)
                            .map { it.channelId }
                            .toList()
                    }
                    // Channel filter is channel-only by design — a chip tap
                    // scopes to that channel's uploads and explicitly does
                    // NOT pull in unrelated playlist videos that happen to
                    // be from the same creator. The union path only runs
                    // for the unfiltered view.
                    val playlistIds: List<String> =
                        if (filterChannelId == null) playlists.map { it.playlistId } else emptyList()
                    val bucket = WeekBucket.forIndex(weekIndex, currentTimeMillis())
                    flow<WeekContent?> {
                        val rowsFlow = if (playlistIds.isEmpty()) {
                            cache.observeRangeForChannels(channelIds, bucket.startMs, bucket.endMs)
                        } else {
                            cache.observeRangeForChannelsOrPlaylists(
                                channelIds,
                                playlistIds,
                                bucket.startMs,
                                bucket.endMs,
                            )
                        }
                        rowsFlow.collect { rows ->
                                val shorts = rows.asSequence()
                                    .filter { it.isShort }
                                    .map { it.toUi() }
                                    .toList()
                                val videos = rows.asSequence()
                                    .filterNot { it.isShort }
                                    .map { it.toUi() }
                                    .toList()
                                if (shorts.isEmpty() && videos.isEmpty()) {
                                    emit(null)
                                } else {
                                    emit(
                                        WeekContent(
                                            weekIndex = weekIndex,
                                            startMs = bucket.startMs,
                                            endMs = bucket.endMs,
                                            shorts = shorts,
                                            videos = videos,
                                        )
                                    )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()

    /**
     * ANDROID-PERSONAL-03 / T4: NewPipe deep-paging fill for a week bucket.
     *
     * Triggered by the ViewModel when [observeWeek] returns null and we're
     * still within [WeekBucket.MAX_WEEKS_BACK]. For every subscribed channel
     * with no cached items in this week's window AND that hasn't yet hit
     * EndOfChannel (i.e. has a non-null deepPageUrl OR has never been
     * deep-paged), this calls [ChannelDeepPaginator.fetchNextPage] in
     * parallel (capped by [MAX_CONCURRENT]) and:
     *   - upserts the returned items into [ChannelVideoCache]
     *   - persists the new deepPageUrl + deepPageCookiesJson on the
     *     refresh-state row
     *   - if [DeepPageResult.EndOfChannel], clears deepPageUrl so future
     *     ticks short-circuit
     *
     * Completes once all per-channel paging calls finish (success or
     * error). Errors are recorded on the refresh-state row but never
     * propagate — a single channel's failure must never abort another.
     *
     * No-op if [deepPaginator] was not injected (test fixtures).
     */
    /**
     * ANDROID-PERSONAL-03 round 5: scan the cache and return the smallest
     * weekIndex >= [fromIndex] (and <= [maxIndex]) that has at least one
     * cached item across the user's subscribed channels.
     *
     * Why: a channel that last posted ~6 months ago has its content in
     * (e.g.) week 26. The naive `loadNextWeek` walked weeks 0, 1, 2…
     * one-by-one, firing a deep-page round at each empty week, so
     * surfacing that channel's content took 25+ sequential rounds. With
     * this helper, after [fillWeekIfNeeded] populates the cache, the
     * ViewModel can jump directly to the earliest week that now has
     * content — skipping all the empty intermediate weeks.
     *
     * Returns null if no cached item maps to a week in `[fromIndex, maxIndex]`.
     */
    suspend fun findNextNonEmptyWeekIndex(
        fromIndex: Int,
        maxIndex: Int = WeekBucket.MAX_WEEKS_BACK,
        filterChannelId: String? = null,
    ): Int? = withContext(ioDispatcher) {
        if (fromIndex > maxIndex) return@withContext null
        val now = currentTimeMillis()
        val all = subscriptions.getApprovedSubscribedChannels()
        // Playlist videos only count toward the unfiltered scan — a channel
        // chip filters strictly to channel uploads.
        val playlists: List<SavedPlaylist> =
            if (filterChannelId == null) subscriptions.getApprovedSavedPlaylists() else emptyList()
        if (all.isEmpty() && playlists.isEmpty()) return@withContext null
        // ANDROID-PERSONAL-03 / Bug 1: when filtering, scope the scan to the
        // single channel. If the filter target is no longer subscribed
        // (race), return null so the caller treats it as "no content".
        val channelIds = if (filterChannelId != null) {
            if (all.any { it.channelId == filterChannelId }) {
                listOf(filterChannelId)
            } else {
                return@withContext null
            }
        } else {
            all.asSequence()
                .sortedByDescending { it.subscribedAt }
                .take(MAX_CHANNELS_PER_REFRESH)
                .map { it.channelId }
                .toList()
        }
        val playlistIds = playlists.map { it.playlistId }
        // Pull the full window: from start-of-maxIndex+1 to start-of-fromIndex.
        // Items uploaded inside that range are exactly the ones whose week
        // bucket falls in [fromIndex, maxIndex].
        val windowEnd = WeekBucket.forIndex(fromIndex, now).endMs   // most recent
        val windowStart = WeekBucket.forIndex(maxIndex, now).startMs // oldest
        val rows = if (playlistIds.isEmpty()) {
            cache
                .observeRangeForChannels(channelIds, windowStart, windowEnd)
                .first()
        } else {
            cache
                .observeRangeForChannelsOrPlaylists(channelIds, playlistIds, windowStart, windowEnd)
                .first()
        }
        if (rows.isEmpty()) return@withContext null
        // The most recent uploadedAt determines the EARLIEST non-empty bucket.
        val newest = rows.maxOfOrNull { it.uploadedAt ?: 0L } ?: return@withContext null
        if (newest <= 0L) return@withContext null
        // Must use ISO week arithmetic (via WeekBucket) — rolling 7-day division
        // diverges from ISO Monday boundaries for items late in the previous week.
        WeekBucket.weekIndexOf(newest, now).coerceIn(fromIndex, maxIndex)
    }

    /**
     * Total cached row count, optionally scoped to a single channel filter.
     *
     * Used by [com.albunyaan.tube.ui.me.MeViewModel.loadNextWeek] as a
     * progress signal when looping deep-page rounds: if the count doesn't
     * change between rounds, no new rows landed (every candidate channel
     * is at EndOfChannel or errored), so the loop exits instead of
     * spinning forever.
     */
    suspend fun countCachedRowsForFilter(filterChannelId: String?): Int = withContext(ioDispatcher) {
        val all = subscriptions.getApprovedSubscribedChannels()
        val playlists: List<SavedPlaylist> =
            if (filterChannelId == null) subscriptions.getApprovedSavedPlaylists() else emptyList()
        if (all.isEmpty() && playlists.isEmpty()) return@withContext 0
        val channelIds = if (filterChannelId != null) {
            if (all.any { it.channelId == filterChannelId }) listOf(filterChannelId) else return@withContext 0
        } else {
            all.asSequence()
                .sortedByDescending { it.subscribedAt }
                .take(MAX_CHANNELS_PER_REFRESH)
                .map { it.channelId }
                .toList()
        }
        val playlistIds = playlists.map { it.playlistId }
        // ANDROID-PERSONAL-03 round 8 review [P1]: SQL COUNT(*) instead of
        // materialising every row through Room's converter just to call
        // `.size`. Fires up to 60× per loadNextWeek call, so the constant
        // factor matters on power-user caches (~3000 rows).
        if (playlistIds.isEmpty()) {
            cache.countForChannels(channelIds)
        } else {
            cache.countForChannelsOrPlaylists(channelIds, playlistIds)
        }
    }

    suspend fun fillWeekIfNeeded(weekIndex: Int): Unit = withContext(ioDispatcher) {
        val paginator = deepPaginator ?: run {
            if (BuildConfig.DEBUG) Log.d(TAG, "fillWeekIfNeeded(week=$weekIndex): paginator is null, skipping")
            return@withContext
        }
        val now = currentTimeMillis()
        val bucket = WeekBucket.forIndex(weekIndex, now)

        val all = subscriptions.getApprovedSubscribedChannels()
        if (all.isEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "fillWeekIfNeeded(week=$weekIndex): no subscriptions")
            return@withContext
        }

        val channelIds = all.asSequence()
            .sortedByDescending { it.subscribedAt }
            .take(MAX_CHANNELS_PER_REFRESH)
            .map { it.channelId }
            .toList()
        val cachedInWindow = cache
            .observeRangeForChannels(channelIds, bucket.startMs, bucket.endMs)
            .first()
        val channelsWithItems: Set<String> = cachedInWindow.asSequence()
            .map { it.channelId }
            .toSet()
        // ANDROID-PERSONAL-03 round 8 [field-bug]: the prior candidate
        // logic stopped at "no items in this week's bucket". That broke
        // high-volume channels (e.g. Dr. Othman, ~15 uploads/day) once
        // history-rich channels (e.g. Sheikh Kishk, 10 months cached)
        // populated weeks 2/3/4: Sheikh Kishk's content carried the
        // visible week, so `findNextNonEmptyWeekIndex` returned non-null
        // and `fillWeekIfNeeded` never fired — Dr. Othman's cache stopped
        // growing.
        //
        // Refined rule (union of both conditions):
        //   1. channel has NO item in the current bucket, AND
        //   2. its oldest cached row is newer than the bucket's start
        //      (deep-paging hasn't reached this week yet) OR it has no
        //      cached rows at all (needs first page).
        // EOF channels are always skipped. Channels that already have an
        // item in the bucket are NOT candidates — that ATOM/cache hit
        // already covers the visible week.
        //
        // ANDROID-PERSONAL-03 round 8 review [P1]: SQL `MIN(uploadedAt)
        // GROUP BY channelId` via [ChannelVideoCacheDao.oldestPerChannel]
        // instead of loading every cache row to compute one min per
        // channel. Channels with zero rows are absent from the result
        // map.
        val oldestPerChannel: Map<String, Long?> = run {
            val byId = cache.oldestPerChannel(channelIds).associateBy { it.channelId }
            channelIds.associateWith { id -> byId[id]?.oldestMs }
        }
        val refreshStates: Map<String, ChannelFeedRefreshState> = channelIds
            .associateWith { id -> refreshStateDao.get(id) ?: return@associateWith null }
            .filterValues { it != null }
            .mapValues { it.value!! }
        val candidateChannels = all
            .filter { it.channelId in channelIds && it.channelId !in channelsWithItems }
            .filter { ch ->
                val st = refreshStates[ch.channelId]
                // Skip channels we've previously paged to exhaustion.
                st?.deepPageUrl != DEEP_PAGE_EOF_SENTINEL
            }
            .filter { ch ->
                // Channels with no cached rows yet OR whose oldest cached
                // row is newer than the requested bucket need more pages.
                val oldest = oldestPerChannel[ch.channelId]
                oldest == null || oldest > bucket.startMs
            }
        if (BuildConfig.DEBUG) {
            val eofCount = refreshStates.values.count { it.deepPageUrl == DEEP_PAGE_EOF_SENTINEL }
            Log.d(
                TAG,
                "fillWeekIfNeeded(week=$weekIndex): subs=${all.size} hasItemsInWindow=${channelsWithItems.size} candidates=${candidateChannels.size} eofChannels=$eofCount"
            )
            candidateChannels.forEach { ch ->
                val st = refreshStates[ch.channelId]
                Log.d(
                    TAG,
                    "  → candidate ${ch.name} (${ch.channelId}): hasToken=${st?.deepPageUrl != null && st.deepPageUrl != DEEP_PAGE_EOF_SENTINEL}"
                )
            }
        }
        if (candidateChannels.isEmpty()) return@withContext

        coroutineScope {
            candidateChannels.map { channel ->
                async {
                    semaphore.withPermit {
                        runDeepPageFor(paginator, channel, bucket, now)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun runDeepPageFor(
        paginator: ChannelDeepPaginator,
        channel: SubscribedChannel,
        bucket: WeekBucket,
        now: Long,
    ) {
        val previous = refreshStateDao.get(channel.channelId)
        val token: ChannelDeepPaginator.SerializedPage? = previous?.deepPageUrl
            ?.takeIf { it.isNotEmpty() && it != DEEP_PAGE_EOF_SENTINEL }
            ?.let { url ->
                val state = previous.deepPageCookiesJson
                    ?.let { runCatching { deepPageStateJsonAdapter.fromJson(it) }.getOrNull() }
                ChannelDeepPaginator.SerializedPage(
                    url = url,
                    id = state?.id,
                    ids = state?.ids,
                    cookies = state?.cookies,
                    body = state?.bodyB64?.let {
                        runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
                    },
                )
            }

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "runDeepPageFor(${channel.name}): tokenUrl=${token?.url?.take(80)}"
            )
        }
        val result = try {
            // Round 5 fix: deep-paging uses DEEP_PAGE_TIMEOUT_MS (60s),
            // NOT the ATOM-sized PER_CHANNEL_TIMEOUT_MS (15s). The
            // RateLimitedDownloader sits in the middle of NewPipe's HTTP
            // call and can hold the call for >15 s waiting for a token.
            withTimeout(DEEP_PAGE_TIMEOUT_MS) {
                paginator.fetchNextPage(channel.channelUrl, token)
            }
        } catch (ce: CancellationException) {
            if (!currentCoroutineContext().isActive) throw ce
            // Timeout / inner cancellation: leave previous state intact, do
            // not increment any counters. Match the semantics of refreshOne.
            if (BuildConfig.DEBUG) Log.w(TAG, "runDeepPageFor(${channel.name}): timeout/cancelled")
            return
        } catch (t: Throwable) {
            // Defensive — fetchNextPage already maps throwables to
            // DeepPageResult.Error, but a misbehaving paginator could still
            // raise. Don't propagate.
            if (BuildConfig.DEBUG) Log.w(TAG, "runDeepPageFor(${channel.name}): threw ${t.message}")
            return
        }

        when (result) {
            is ChannelDeepPaginator.DeepPageResult.Page -> {
                val itemsTotal = result.items.size
                val cacheRows = result.items
                    .filter { it.uploadedAt != null && it.videoId.isNotEmpty() }
                    .map { it.toCacheRow(channel, now) }
                if (BuildConfig.DEBUG) {
                    val droppedNoDate = result.items.count { it.uploadedAt == null }
                    Log.d(
                        TAG,
                        "runDeepPageFor(${channel.name}): Page items=$itemsTotal cached=${cacheRows.size} droppedNoUploadedAt=$droppedNoDate hasNext=${result.nextPage != null}"
                    )
                }
                if (cacheRows.isNotEmpty()) {
                    // Use upsertAll, never delete-then-insert. We're appending
                    // older history; primary-key REPLACE handles overlap
                    // (same videoId from ATOM as from NewPipe → keeps the
                    // newer fetch's metadata) without wiping ATOM's
                    // most-recent rows.
                    cache.upsertAll(cacheRows)
                }
                val nextUrl = result.nextPage?.url ?: DEEP_PAGE_EOF_SENTINEL
                val nextStateJson = result.nextPage?.let { np ->
                    val state = DeepPageState(
                        id = np.id?.takeIf { it.isNotEmpty() },
                        ids = np.ids?.takeIf { it.isNotEmpty() },
                        cookies = np.cookies?.takeIf { it.isNotEmpty() },
                        bodyB64 = np.body?.let {
                            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
                        },
                    )
                    // If everything is null/empty, persist null instead of "{}".
                    if (state.id == null && state.ids == null && state.cookies == null && state.bodyB64 == null) {
                        null
                    } else {
                        deepPageStateJsonAdapter.toJson(state)
                    }
                }
                refreshStateDao.upsert(
                    (previous ?: ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                    )).copy(
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                        deepPageUrl = nextUrl,
                        deepPageCookiesJson = nextStateJson,
                    )
                )
            }
            ChannelDeepPaginator.DeepPageResult.EndOfChannel -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "runDeepPageFor(${channel.name}): EndOfChannel — marking exhausted")
                }
                refreshStateDao.upsert(
                    (previous ?: ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                    )).copy(
                        lastAttemptAt = now,
                        deepPageUrl = DEEP_PAGE_EOF_SENTINEL,
                        deepPageCookiesJson = null,
                    )
                )
            }
            is ChannelDeepPaginator.DeepPageResult.Error -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "runDeepPageFor(${channel.name}): Error reason='${result.reason}'")
                }
                // Don't escalate — deep-paging failures are non-fatal. The
                // ATOM refresher manages the 429/5xx ladder. Just record
                // the attempt timestamp so callers see "we tried".
                refreshStateDao.upsert(
                    (previous ?: ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = previous?.lastSuccessfulFetchAt ?: 0L,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                    )).copy(
                        lastAttemptAt = now,
                    )
                )
            }
        }
    }

    /**
     * Refresh a slice of subscribed channels.
     *
     * - [force]: when true, bypasses TTL freshness gate AND per-channel
     *   backoff. Pull-to-refresh sets this; periodic worker leaves it false.
     * - [perTickBudget]: cap on the number of channels processed per call.
     *   The periodic worker uses a small budget (e.g. 5) to spread the
     *   30-channel pool across hourly ticks; the foreground burst /
     *   pull-to-refresh uses a larger budget (e.g. 30) to surface results
     *   quickly. Defaults to [MAX_CHANNELS_PER_REFRESH] for backward
     *   compatibility with existing tests.
     *
     * T9: channels are sorted by **oldest successful fetch first** (round
     * robin) so the worker gives every channel an equal share of the
     * refresh budget instead of starving the tail of the subscription
     * list.
     */
    suspend fun refresh(
        force: Boolean = false,
        perTickBudget: Int = MAX_CHANNELS_PER_REFRESH,
    ): Unit = withContext(ioDispatcher) {
        // Serialise overlapping refresh() calls so the per-index STAGGER_MS
        // delay is a true inter-fetch spacing, not collapsed by concurrent
        // callers each starting from index=0 (F3).
        refreshMutex.withLock {
            val now = currentTimeMillis()
            val all = subscriptions.getApprovedSubscribedChannels()
            if (all.isEmpty()) return@withLock

            // T9: oldest-fetch-first round-robin slice. A single batch query
            // pulls all `(channelId, lastSuccessfulFetchAt)` rows; channels
            // not in the refresh-state table rank lowest (treated as 0L) so
            // freshly subscribed channels are picked up on the next tick.
            val ages: Map<String, Long> = refreshStateDao.getAllLastSuccessfulFetchAt()
                .associate { it.channelId to it.lastSuccessfulFetchAt }
            val channels = all
                .sortedBy { ages[it.channelId] ?: 0L }
                .take(perTickBudget)

            if (channels.isEmpty()) return@withLock

            coroutineScope {
                channels.mapIndexed { index, channel ->
                    async {
                        // F-CR2 (CodeRabbit): true per-index stagger.
                        // Previously delay(STAGGER_MS) made every non-zero index
                        // wake up at the same 250 ms mark and then race for
                        // semaphore permits in a burst. Index-scaled delay
                        // spreads launch times across 0, 250, 500, 750, …
                        // so YouTube sees an actually paced request stream.
                        if (index > 0) delay(index.toLong() * STAGGER_MS)
                        semaphore.withPermit { refreshOne(channel, now, force) }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun refreshOne(channel: SubscribedChannel, now: Long, force: Boolean) {
        val previous = refreshStateDao.get(channel.channelId)
        // T12: latency clock starts here so freshness/backoff short-circuits
        // also report a (tiny) latency. monotonic.
        val startNs = System.nanoTime()

        // [1] TTL freshness gate. force=true bypasses it (pull-to-refresh).
        val fresh = previous != null && (now - previous.lastSuccessfulFetchAt) < CACHE_TTL_MS
        if (fresh && !force) {
            telemetry.emit(
                MeRefreshTelemetry.Event.MeChannelFetched(
                    timestampMs = now,
                    channelId = channel.channelId,
                    itemsCount = 0,
                    latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                    outcome = MeRefreshTelemetry.ChannelOutcome.FRESHNESS_SKIPPED,
                )
            )
            return
        }

        // [2] Per-channel backoff gate. force=true bypasses it (pull-to-refresh).
        // backoff is its own state — when active we don't fetch, don't update
        // any field, just return. The next non-backed-off tick resumes normally.
        val backoffActive = previous?.backoffUntilMs != null &&
            now < previous.backoffUntilMs &&
            !force
        if (backoffActive) {
            telemetry.emit(
                MeRefreshTelemetry.Event.MeChannelFetched(
                    timestampMs = now,
                    channelId = channel.channelId,
                    itemsCount = 0,
                    latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                    outcome = MeRefreshTelemetry.ChannelOutcome.BACKOFF_SKIPPED,
                )
            )
            return
        }

        // [3] Conditional GET — pass cached ETag + Last-Modified.
        val result: ChannelFeedFetcher.FetchResult = try {
            withTimeout(PER_CHANNEL_TIMEOUT_MS) {
                fetcher.fetchLatest(
                    channelUrl = channel.channelUrl,
                    priorEtag = previous?.etag,
                    priorLastModified = previous?.lastModified,
                )
            }
        } catch (ce: CancellationException) {
            // ANDROID-PERSONAL-02 [Bug 3]: distinguish OUTER cancellation
            // (worker timeout / scope cancellation) from the INNER
            // [withTimeout(PER_CHANNEL_TIMEOUT_MS)] firing.
            //
            // Why this matters: the prior code caught
            // [TimeoutCancellationException] *before* [CancellationException],
            // which meant any TCE — even one synthesised by an outer
            // [withTimeout] in [RefreshSubscriptionsWorker.doWork] — was
            // absorbed into the soft "per-channel timeout" branch. The
            // worker would then continue iterating channels for as long as
            // its outer timeout permitted, defeating the cancellation
            // contract.
            //
            // Now: if the surrounding scope is no longer active (parent was
            // cancelled), re-throw immediately so structured concurrency
            // observes the cancel and the semaphore permit is released
            // (F6). Only when we're STILL active is the CE the inner
            // withTimeout firing — that's the genuine per-channel network
            // jitter case the soft-timeout branch was designed for.
            if (!currentCoroutineContext().isActive) throw ce
            if (ce is TimeoutCancellationException) {
                // Per-channel timeout is a soft, network-flake failure —
                // record lastErrorMessage + lastAttemptAt and keep the
                // existing cache. T9: timeouts do NOT increment
                // consecutiveErrorCount. They are ambient network jitter,
                // not a server-side rejection signal — escalating backoff
                // on every transient timeout would push every user onto a
                // 24h cooldown after a couple of bad mobile packets.
                // Counters/etag/lastModified/backoffUntilMs are preserved.
                refreshStateDao.upsert(
                    previous?.copy(
                        lastAttemptAt = now,
                        lastErrorMessage = "timeout after ${PER_CHANNEL_TIMEOUT_MS}ms",
                    ) ?: ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = 0L,
                        lastAttemptAt = now,
                        lastErrorMessage = "timeout after ${PER_CHANNEL_TIMEOUT_MS}ms",
                    )
                )
                telemetry.emit(
                    MeRefreshTelemetry.Event.MeChannelFetched(
                        timestampMs = now,
                        channelId = channel.channelId,
                        itemsCount = 0,
                        latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                        outcome = MeRefreshTelemetry.ChannelOutcome.TIMEOUT,
                    )
                )
                return
            }
            // Defensive: a non-TCE CancellationException with the scope
            // still active is unexpected (e.g. a bare `throw ce` from a
            // misbehaving fetcher). F6 says never swallow cancellation —
            // re-throw and let the parent decide.
            throw ce
        } catch (t: Throwable) {
            // T9: hard error path. Increment consecutiveErrorCount and
            // (when the message looks like 429 or 5xx) compute a new
            // backoffUntilMs along the appropriate ladder.
            val errCount = (previous?.consecutiveErrorCount ?: 0) + 1
            val msg = t.message ?: t::class.java.simpleName
            val matchedLadder: Boolean
            val newBackoffUntilMs: Long? = when {
                HTTP_429_REGEX.containsMatchIn(msg) -> {
                    matchedLadder = true
                    val step = (errCount - 1).coerceAtMost(ATOM_429_BACKOFFS.lastIndex)
                    now + ATOM_429_BACKOFFS[step]
                }
                HTTP_5XX_REGEX.containsMatchIn(msg) -> {
                    matchedLadder = true
                    val step = (errCount - 1).coerceAtMost(ATOM_5XX_BACKOFFS.lastIndex)
                    now + ATOM_5XX_BACKOFFS[step]
                }
                else -> {
                    matchedLadder = false
                    previous?.backoffUntilMs // unknown error — preserve prior
                }
            }
            refreshStateDao.upsert(
                (previous ?: ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = 0L,
                    lastAttemptAt = now,
                    lastErrorMessage = msg,
                )).copy(
                    lastAttemptAt = now,
                    lastErrorMessage = msg,
                    consecutiveErrorCount = errCount,
                    backoffUntilMs = newBackoffUntilMs,
                    // Preserve etag, lastModified, deepPageUrl, deepPageCookiesJson via copy()
                )
            )
            telemetry.emit(
                MeRefreshTelemetry.Event.MeChannelFetched(
                    timestampMs = now,
                    channelId = channel.channelId,
                    itemsCount = 0,
                    latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                    outcome = if (matchedLadder) {
                        MeRefreshTelemetry.ChannelOutcome.ERROR_BACKOFF
                    } else {
                        MeRefreshTelemetry.ChannelOutcome.ERROR_NEUTRAL
                    },
                )
            )
            return
        }

        when (result) {
            is ChannelFeedFetcher.FetchResult.NotModified -> {
                // Server confirmed nothing changed. Don't touch the cache.
                // T9: success path — bump TTL clock, reset both counters,
                // clear backoffUntilMs. Preserve prior ETag/Last-Modified
                // when the 304 came back without one (servers often omit
                // validators on 304).
                val nextEtag = result.etag ?: previous?.etag
                val nextLastModified = result.lastModified ?: previous?.lastModified
                refreshStateDao.upsert(
                    (previous ?: ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                    )).copy(
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                        etag = nextEtag,
                        lastModified = nextLastModified,
                        consecutiveErrorCount = 0,
                        consecutiveEmptyCount = 0,
                        backoffUntilMs = null,
                        // deepPageUrl + deepPageCookiesJson preserved via copy()
                    )
                )
                telemetry.emit(
                    MeRefreshTelemetry.Event.MeChannelFetched(
                        timestampMs = now,
                        channelId = channel.channelId,
                        itemsCount = 0,
                        latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                        outcome = MeRefreshTelemetry.ChannelOutcome.NOT_MODIFIED,
                    )
                )
                return
            }

            is ChannelFeedFetcher.FetchResult.Items -> {
                val items: List<ChannelVideoCache> = result.items
                    .filter { it.uploadedAt != null && it.videoId.isNotEmpty() }
                    .sortedByDescending { it.uploadedAt }
                    .take(MAX_ITEMS_PER_CHANNEL)
                    .map { it.toCacheRow(channel, now) }

                // F1: an empty result is almost always a transient extractor
                // quirk (rate limit, shorts-only channel on a week with no
                // posts, server glitch). Treat it as "no new data" — don't
                // wipe the prior cached window. We still advance
                // lastAttemptAt so the TTL clock runs.
                //
                // Stage-5 round-2 refinement [P1]: cap this protection to
                // the feed window. If the last successful fetch is older
                // than FEED_WINDOW_MS, the cached rows are outside what
                // observeFeed can emit anyway — no user-visible data to
                // preserve — so allow the wipe. Otherwise a channel that
                // legitimately emptied (deleted all videos / went dormant)
                // would keep stale cache rows forever.
                //
                // T9 counter rules:
                //  - protected-empty: errorCount → 0, emptyCount += 1,
                //    keep backoff/etag/lastModified
                //  - real-empty (outside protection): both counters reset
                //    differently — error → 0, empty += 1 still
                //  - non-empty: both counters reset to 0, backoff cleared
                if (items.isEmpty() && previous != null && previous.lastSuccessfulFetchAt > 0L &&
                    (now - previous.lastSuccessfulFetchAt) < FEED_WINDOW_MS
                ) {
                    refreshStateDao.upsert(
                        previous.copy(
                            lastAttemptAt = now,
                            lastErrorMessage = null,
                            // Persist any new validators the server returned
                            // even on an empty body — they let us 304 next.
                            etag = result.etag ?: previous.etag,
                            lastModified = result.lastModified ?: previous.lastModified,
                            consecutiveErrorCount = 0,
                            consecutiveEmptyCount = previous.consecutiveEmptyCount + 1,
                        )
                    )
                    telemetry.emit(
                        MeRefreshTelemetry.Event.MeChannelFetched(
                            timestampMs = now,
                            channelId = channel.channelId,
                            itemsCount = 0,
                            latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                            outcome = MeRefreshTelemetry.ChannelOutcome.EMPTY_PROTECTED,
                        )
                    )
                    return
                }

                if (items.isEmpty()) {
                    // Real-empty path (outside protection): the channel is
                    // legitimately empty (dormant / unsubscribed-from-uploads /
                    // first-fetch-with-no-uploads). T9: error → 0, empty++.
                    //
                    // Round 8 [field-bug]: do NOT wipe the cache here — that
                    // would lose any deep-paged history. ATOM emptiness is
                    // about the most-recent 15 uploads only; older deep-
                    // paged rows remain valid. Skipping the wipe means a
                    // dormant channel keeps showing the user's prior scroll
                    // history until they unsubscribe.
                    refreshStateDao.upsert(
                        (previous ?: ChannelFeedRefreshState(
                            channelId = channel.channelId,
                            lastSuccessfulFetchAt = now,
                            lastAttemptAt = now,
                            lastErrorMessage = null,
                        )).copy(
                            lastSuccessfulFetchAt = now,
                            lastAttemptAt = now,
                            lastErrorMessage = null,
                            etag = result.etag,
                            lastModified = result.lastModified,
                            consecutiveErrorCount = 0,
                            consecutiveEmptyCount = (previous?.consecutiveEmptyCount ?: 0) + 1,
                            backoffUntilMs = null,
                            // deepPageUrl + deepPageCookiesJson preserved via copy()
                        )
                    )
                    telemetry.emit(
                        MeRefreshTelemetry.Event.MeChannelFetched(
                            timestampMs = now,
                            channelId = channel.channelId,
                            itemsCount = 0,
                            latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                            outcome = MeRefreshTelemetry.ChannelOutcome.EMPTY_REAL,
                        )
                    )
                    return
                }

                // Round 8 [field-bug]: ATOM writes go through upsertAll —
                // never delete-then-insert — so deep-paged history is not
                // wiped. ATOM returns the 15 most-recent uploads; if those
                // overlap with deep-paged rows by videoId, primary-key
                // REPLACE updates them with ATOM's fresher metadata
                // (title/thumbnail/views). Older deep-paged rows survive
                // untouched.
                cache.upsertAll(items)
                refreshStateDao.upsert(
                    (previous ?: ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                    )).copy(
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                        etag = result.etag,
                        lastModified = result.lastModified,
                        consecutiveErrorCount = 0,
                        consecutiveEmptyCount = 0,
                        backoffUntilMs = null,
                        // deepPageUrl + deepPageCookiesJson preserved via copy()
                    )
                )
                telemetry.emit(
                    MeRefreshTelemetry.Event.MeChannelFetched(
                        timestampMs = now,
                        channelId = channel.channelId,
                        itemsCount = items.size,
                        latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                        outcome = MeRefreshTelemetry.ChannelOutcome.NEW_ITEMS,
                    )
                )

                // ANDROID-PERSONAL-03 round 6 [field-bug]: when a channel is
                // refreshed for the FIRST TIME ever (no prior refresh-state
                // row), ATOM only returns the 15 most recent uploads. For
                // channels that post shorts heavily (e.g. Mufti Menk's
                // last 12 days are 15 shorts, no long-form), the user sees
                // just shorts and assumes the feature is broken. Fire ONE
                // NewPipe Videos-tab page in the same refresh tick so the
                // cache also has ~30 long-form items for the new channel.
                // After this initial pull, subsequent refreshes only do
                // ATOM (cheap, gated by 304); deep-paging continues on
                // demand via [fillWeekIfNeeded] when the user scrolls.
                if (previous == null) {
                    val paginator = deepPaginator
                    if (paginator != null) {
                        runDeepPageFor(
                            paginator = paginator,
                            channel = channel,
                            bucket = WeekBucket.forIndex(0, now),
                            now = now,
                        )
                    }
                }
            }
        }
    }

    /**
     * Fetch the first page of each saved playlist via NewPipe and upsert
     * the resulting videos into [cache] alongside [PlaylistVideoLink] rows
     * pointing at them. After this completes, the unfiltered Me-tab feed
     * surfaces playlist content alongside subscribed-channel uploads,
     * bucketed by the video's own upload date (Path A — no per-app
     * "first seen in playlist" tracking).
     *
     * No-op when [playlistRepository] or [playlistVideoLinkDao] are not
     * injected (test fixtures only wire the channel path).
     *
     * Concurrency: each playlist fetch acquires the same [semaphore] used
     * by the channel refresh path so the combined NewPipe load stays
     * bounded at [MAX_CONCURRENT]. Per-index [STAGGER_MS] delay matches
     * the channel-refresh pattern. Per-playlist failures are logged and
     * skipped — never abort the batch.
     */
    suspend fun refreshPlaylistVideos(): Unit = withContext(ioDispatcher) {
        val repo = playlistRepository ?: return@withContext
        val linkDao = playlistVideoLinkDao ?: return@withContext
        val saved = subscriptions.getApprovedSavedPlaylists()
        if (saved.isEmpty()) return@withContext

        coroutineScope {
            saved.mapIndexed { index, playlist ->
                async {
                    if (index > 0) delay(index * STAGGER_MS)
                    semaphore.withPermit {
                        refreshSinglePlaylist(repo, linkDao, playlist)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun refreshSinglePlaylist(
        repo: PlaylistDetailRepository,
        linkDao: PlaylistVideoLinkDao,
        playlist: SavedPlaylist,
    ) {
        try {
            val page = withTimeout(PER_CHANNEL_TIMEOUT_MS) {
                repo.getItems(playlist.playlistId, page = null, itemOffset = 1)
            }
            val withTimestamp = page.items.filter { it.uploadedAtMillis != null }
            if (withTimestamp.isEmpty()) {
                linkDao.replaceForPlaylist(playlist.playlistId, emptyList())
                return
            }
            val now = currentTimeMillis()
            val cacheRows = withTimestamp.map { item ->
                ChannelVideoCache(
                    videoId = item.videoId,
                    channelId = item.channelId.orEmpty(),
                    channelName = item.channelName.orEmpty(),
                    title = item.title,
                    thumbnailUrl = item.thumbnailUrl,
                    durationSeconds = item.durationSeconds?.toLong(),
                    viewCount = item.viewCount,
                    uploadedAt = item.uploadedAtMillis,
                    // NewPipe doesn't expose a definitive isShort flag on
                    // playlist entries — duration < 60s is the heuristic.
                    isShort = item.durationSeconds?.let { it in 1..59 } ?: false,
                    fetchedAt = now,
                )
            }
            val links = withTimestamp.map { item ->
                PlaylistVideoLink(
                    playlistId = playlist.playlistId,
                    videoId = item.videoId,
                )
            }
            // Insert-only on the cache: a video already populated by the
            // channel-refresh path keeps the authoritative channelId /
            // channelName / isShort metadata. Playlist refresh only adds
            // videos the channel path hasn't seen.
            cache.insertIgnoreAll(cacheRows)
            linkDao.replaceForPlaylist(playlist.playlistId, links)
        } catch (ce: CancellationException) {
            // The inner withTimeout above throws TimeoutCancellationException
            // (a subclass of CancellationException) when a single playlist
            // exceeds PER_CHANNEL_TIMEOUT_MS. Propagating that would abort
            // the whole refreshPlaylistVideos batch via async/awaitAll —
            // contradicting the per-playlist isolation contract. Re-throw
            // only when the outer scope is actually being cancelled.
            if (!currentCoroutineContext().isActive) throw ce
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "refreshSinglePlaylist(${playlist.playlistId}): timeout")
            }
        } catch (e: Exception) {
            // Narrowed from `Throwable` to `Exception` (cubic R1 finding):
            // Throwable would swallow `Error` subclasses (OutOfMemoryError,
            // StackOverflowError, LinkageError) that should propagate up
            // and bring the process down. Inside async { ... } an Error
            // masquerading as a per-playlist failure can corrupt JVM
            // state without surfacing. `Exception` covers the legitimate
            // per-playlist failure modes (IOException, ParsingException,
            // NewPipe extraction errors) while letting Errors bubble.
            if (BuildConfig.DEBUG) {
                Log.w(
                    TAG,
                    "refreshSinglePlaylist(${playlist.playlistId}): failed — ${e.message}"
                )
            }
        }
    }

    private fun ChannelFeedFetcher.ChannelFeedItem.toCacheRow(
        channel: SubscribedChannel,
        now: Long,
    ): ChannelVideoCache = ChannelVideoCache(
        videoId = videoId,
        channelId = channel.channelId,
        channelName = channel.name,
        title = title,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = viewCount,
        uploadedAt = uploadedAt,
        isShort = isShort,
        fetchedAt = now,
    )

    /**
     * ANDROID-PERSONAL-03 / T4: convert a Room cache row to the UI model.
     * Mirrors the converter previously held inline in [MeViewModel] before
     * the per-week refactor moved the cache → UI mapping into the repository
     * (so observeWeek can return WeekContent already in UI shape).
     */
    private fun ChannelVideoCache.toUi(): MeFeedVideo = MeFeedVideo(
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

    // Test seam. Real code uses System.currentTimeMillis().
    @Volatile
    internal var currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() }
    private fun currentTimeMillis(): Long = currentTimeMillisProvider()
}
