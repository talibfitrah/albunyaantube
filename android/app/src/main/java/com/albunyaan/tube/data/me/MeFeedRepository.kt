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
                    val channelIds = subs.map { it.channelId }
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
                        if (index > 0) delay(STAGGER_MS)
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

        val items: List<ChannelVideoCache> = try {
            fetcher.fetchLatest(channel.channelUrl)
                .filter { it.uploadedAt != null && it.videoId.isNotEmpty() }
                .sortedByDescending { it.uploadedAt }
                .take(MAX_ITEMS_PER_CHANNEL)
                .map { it.toCacheRow(channel, now) }
        } catch (ce: CancellationException) {
            // F6: never swallow cancellation — it must propagate to the
            // enclosing coroutineScope so the semaphore permit is released
            // and the parent job observes the cancel.
            throw ce
        } catch (t: Throwable) {
            refreshStateDao.upsert(
                ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = previous?.lastSuccessfulFetchAt ?: 0L,
                    lastAttemptAt = now,
                    lastErrorMessage = t.message ?: t::class.java.simpleName,
                )
            )
            return
        }

        // F1: an empty result is almost always a transient extractor quirk
        // (rate limit, shorts-only channel on a week with no posts, server
        // glitch). Treat it as "no new data" — don't wipe the prior cached
        // window. We still advance lastAttemptAt so the TTL clock runs.
        if (items.isEmpty() && previous != null && previous.lastSuccessfulFetchAt > 0L) {
            refreshStateDao.upsert(
                previous.copy(
                    lastAttemptAt = now,
                    lastErrorMessage = null,
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
            )
        )
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
