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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
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
 */
@Singleton
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

    fun observeFeed(): Flow<List<ChannelVideoCache>> {
        val cutoff = currentTimeMillis() - FEED_WINDOW_MS
        return cache.observeRecent(cutoff).distinctUntilChanged()
    }

    suspend fun refresh(force: Boolean = false): Unit = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val channels = subscriptions.getSubscribedChannels()
            .asSequence()
            .sortedByDescending { it.subscribedAt }
            .take(MAX_CHANNELS_PER_REFRESH)
            .toList()

        if (channels.isEmpty()) return@withContext

        coroutineScope {
            channels.mapIndexed { index, channel ->
                async {
                    if (index > 0) delay(STAGGER_MS)
                    semaphore.withPermit { refreshOne(channel, now, force) }
                }
            }.awaitAll()
        }
    }

    private suspend fun refreshOne(channel: SubscribedChannel, now: Long, force: Boolean) {
        val previous = refreshStateDao.get(channel.channelId)
        val fresh = previous != null && (now - previous.lastSuccessfulFetchAt) < CACHE_TTL_MS
        if (fresh && !force) return

        try {
            val items = fetcher.fetchLatest(channel.channelUrl)
                .filter { it.uploadedAt != null && it.videoId.isNotEmpty() }
                .sortedByDescending { it.uploadedAt }
                .take(MAX_ITEMS_PER_CHANNEL)
                .map { it.toCacheRow(channel, now) }

            cache.replaceForChannel(channel.channelId, items)
            refreshStateDao.upsert(
                ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = now,
                    lastAttemptAt = now,
                    lastErrorMessage = null,
                )
            )
        } catch (t: Throwable) {
            refreshStateDao.upsert(
                ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = previous?.lastSuccessfulFetchAt ?: 0L,
                    lastAttemptAt = now,
                    lastErrorMessage = t.message ?: t::class.java.simpleName,
                )
            )
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
    internal var currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() }
    private fun currentTimeMillis(): Long = currentTimeMillisProvider()
}
