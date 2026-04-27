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

    suspend fun refresh(force: Boolean = false): Unit = withContext(ioDispatcher) {
        // Serialise overlapping refresh() calls so the per-index STAGGER_MS
        // delay is a true inter-fetch spacing, not collapsed by concurrent
        // callers each starting from index=0 (F3).
        refreshMutex.withLock {
            val now = currentTimeMillis()
            val channels = subscriptions.getSubscribedChannels()
                .asSequence()
                .sortedByDescending { it.subscribedAt }
                .take(MAX_CHANNELS_PER_REFRESH)
                .toList()

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
        val fresh = previous != null && (now - previous.lastSuccessfulFetchAt) < CACHE_TTL_MS
        if (fresh && !force) return

        // ANDROID-PERSONAL-02 / T2: pass the cached ETag + Last-Modified into
        // the fetcher so most ticks return HTTP 304. The fetcher returns a
        // sealed FetchResult; we branch on it below.
        val result: ChannelFeedFetcher.FetchResult = try {
            withTimeout(PER_CHANNEL_TIMEOUT_MS) {
                fetcher.fetchLatest(
                    channelUrl = channel.channelUrl,
                    priorEtag = previous?.etag,
                    priorLastModified = previous?.lastModified,
                )
            }
        } catch (toc: TimeoutCancellationException) {
            // Per-channel timeout is a soft failure — record it but keep the
            // existing cache and let the next refresh try again. Do NOT let
            // it propagate as CancellationException to the parent scope.
            //
            // T2 → T9 breadcrumb: preserve the v3 fields (etag, lastModified,
            // counters, backoffUntilMs) from the prior state so T9's full
            // backoff logic has the correct starting point. T9 will manage
            // counter increments and backoff escalation; T2 only ensures
            // we never zero them out in failure paths.
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
            refreshStateDao.upsert(
                previous?.copy(
                    lastAttemptAt = now,
                    lastErrorMessage = t.message ?: t::class.java.simpleName,
                ) ?: ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = 0L,
                    lastAttemptAt = now,
                    lastErrorMessage = t.message ?: t::class.java.simpleName,
                )
            )
            return
        }

        when (result) {
            is ChannelFeedFetcher.FetchResult.NotModified -> {
                // Server confirmed nothing changed. Don't touch the cache.
                // Bump lastSuccessfulFetchAt so the TTL clock runs, reset
                // the v3 counters (304 = success), preserve the prior ETag
                // when the 304 came back without one, and preserve
                // backoffUntilMs (T9 owns it).
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
                        backoffUntilMs = previous?.backoffUntilMs,
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
                        backoffUntilMs = previous?.backoffUntilMs,
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
