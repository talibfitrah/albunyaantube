package com.albunyaan.tube.data.me

import com.albunyaan.tube.data.local.ChannelFeedRefreshState
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
) {

    companion object {
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
    }

    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val refreshMutex = Mutex()

    /**
     * Cache-backed feed stream. The 14-day cutoff is recomputed every time
     * the upstream subscription list changes, so a screen left open for hours
     * will not show stale items past the window (F4).
     */
    fun observeFeed(): Flow<List<ChannelVideoCache>> =
        subscriptions.observeSubscribedChannels()
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
            val all = subscriptions.getSubscribedChannels()
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

        // [1] TTL freshness gate. force=true bypasses it (pull-to-refresh).
        val fresh = previous != null && (now - previous.lastSuccessfulFetchAt) < CACHE_TTL_MS
        if (fresh && !force) return

        // [2] Per-channel backoff gate. force=true bypasses it (pull-to-refresh).
        // backoff is its own state — when active we don't fetch, don't update
        // any field, just return. The next non-backed-off tick resumes normally.
        val backoffActive = previous?.backoffUntilMs != null &&
            now < previous.backoffUntilMs &&
            !force
        if (backoffActive) return

        // [3] Conditional GET — pass cached ETag + Last-Modified.
        val result: ChannelFeedFetcher.FetchResult = try {
            withTimeout(PER_CHANNEL_TIMEOUT_MS) {
                fetcher.fetchLatest(
                    channelUrl = channel.channelUrl,
                    priorEtag = previous?.etag,
                    priorLastModified = previous?.lastModified,
                )
            }
        } catch (toc: TimeoutCancellationException) {
            // Per-channel timeout is a soft, network-flake failure — record
            // lastErrorMessage + lastAttemptAt and keep the existing cache.
            // T9: timeouts do NOT increment consecutiveErrorCount. They are
            // ambient network jitter, not a server-side rejection signal —
            // escalating backoff on every transient timeout would push every
            // user onto a 24h cooldown after a couple of bad mobile packets.
            // Counters/etag/lastModified/backoffUntilMs are preserved from prior.
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
            return
        } catch (ce: CancellationException) {
            // F6: never swallow cancellation — it must propagate to the
            // enclosing coroutineScope so the semaphore permit is released
            // and the parent job observes the cancel.
            throw ce
        } catch (t: Throwable) {
            // T9: hard error path. Increment consecutiveErrorCount and
            // (when the message looks like 429 or 5xx) compute a new
            // backoffUntilMs along the appropriate ladder.
            val errCount = (previous?.consecutiveErrorCount ?: 0) + 1
            val msg = t.message ?: t::class.java.simpleName
            val newBackoffUntilMs: Long? = when {
                HTTP_429_REGEX.containsMatchIn(msg) -> {
                    val step = (errCount - 1).coerceAtMost(ATOM_429_BACKOFFS.lastIndex)
                    now + ATOM_429_BACKOFFS[step]
                }
                HTTP_5XX_REGEX.containsMatchIn(msg) -> {
                    val step = (errCount - 1).coerceAtMost(ATOM_5XX_BACKOFFS.lastIndex)
                    now + ATOM_5XX_BACKOFFS[step]
                }
                else -> previous?.backoffUntilMs // unknown error — preserve prior
            }
            refreshStateDao.upsert(
                ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = previous?.lastSuccessfulFetchAt ?: 0L,
                    lastAttemptAt = now,
                    lastErrorMessage = msg,
                    etag = previous?.etag,
                    lastModified = previous?.lastModified,
                    consecutiveErrorCount = errCount,
                    consecutiveEmptyCount = previous?.consecutiveEmptyCount ?: 0,
                    backoffUntilMs = newBackoffUntilMs,
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
                    ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                        etag = nextEtag,
                        lastModified = nextLastModified,
                        consecutiveErrorCount = 0,
                        consecutiveEmptyCount = 0,
                        backoffUntilMs = null,
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
                    return
                }

                if (items.isEmpty()) {
                    // Real-empty path (outside protection): wipe cache, but
                    // still treat as a successful fetch — the channel is
                    // legitimately empty (dormant / unsubscribed-from-uploads /
                    // first-fetch-with-no-uploads). T9: error → 0, empty++.
                    cache.replaceForChannel(channel.channelId, emptyList())
                    refreshStateDao.upsert(
                        ChannelFeedRefreshState(
                            channelId = channel.channelId,
                            lastSuccessfulFetchAt = now,
                            lastAttemptAt = now,
                            lastErrorMessage = null,
                            etag = result.etag,
                            lastModified = result.lastModified,
                            consecutiveErrorCount = 0,
                            consecutiveEmptyCount = (previous?.consecutiveEmptyCount ?: 0) + 1,
                            backoffUntilMs = null,
                        )
                    )
                    return
                }

                cache.replaceForChannel(channel.channelId, items)
                refreshStateDao.upsert(
                    ChannelFeedRefreshState(
                        channelId = channel.channelId,
                        lastSuccessfulFetchAt = now,
                        lastAttemptAt = now,
                        lastErrorMessage = null,
                        etag = result.etag,
                        lastModified = result.lastModified,
                        consecutiveErrorCount = 0,
                        consecutiveEmptyCount = 0,
                        backoffUntilMs = null,
                    )
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

    // Test seam. Real code uses System.currentTimeMillis().
    @Volatile
    internal var currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() }
    private fun currentTimeMillis(): Long = currentTimeMillisProvider()
}
