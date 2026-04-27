package com.albunyaan.tube.data.me

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MeFeedRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var subs: SubscriptionRepository
    private lateinit var fetcher: RecordingFetcher
    private lateinit var repo: MeFeedRepository
    private var clockMillis: Long = 10_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        subs = SubscriptionRepository(
            db = db,
            channels = db.subscribedChannelDao(),
            playlists = db.savedPlaylistDao(),
            cache = db.channelVideoCacheDao(),
            refreshState = db.channelFeedRefreshStateDao(),
        )
        fetcher = RecordingFetcher()
        repo = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = fetcher,
            ioDispatcher = Dispatchers.Unconfined,
        ).also { it.currentTimeMillisProvider = { clockMillis } }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun subscribe(id: String) {
        subs.subscribe(SubscribedChannel(id, "https://yt/$id", "name-$id", null, clockMillis))
    }

    @Test
    fun `fresh cache skips fetcher unless forced`() = runTest {
        subscribe("UC1")
        fetcher.responses["https://yt/UC1"] = listOf(item("v1", uploadedAt = clockMillis - 1_000L))

        repo.refresh(force = false)
        assertEquals(1, fetcher.callsFor("https://yt/UC1"))

        // Second call within TTL: fetcher should not be called again.
        repo.refresh(force = false)
        assertEquals(1, fetcher.callsFor("https://yt/UC1"))

        // Force: fetcher is called again.
        repo.refresh(force = true)
        assertEquals(2, fetcher.callsFor("https://yt/UC1"))
    }

    @Test
    fun `stale cache triggers fetcher after TTL`() = runTest {
        subscribe("UC1")
        fetcher.responses["https://yt/UC1"] = listOf(item("v1", uploadedAt = clockMillis - 1_000L))
        repo.refresh(force = false)
        assertEquals(1, fetcher.callsFor("https://yt/UC1"))

        clockMillis += MeFeedRepository.CACHE_TTL_MS + 1_000L
        repo.refresh(force = false)
        assertEquals(2, fetcher.callsFor("https://yt/UC1"))
    }

    @Test
    fun `fetcher failure is recorded but does not propagate`() = runTest {
        subscribe("UC1")
        subscribe("UC2")
        fetcher.responses["https://yt/UC2"] = listOf(item("v2", uploadedAt = clockMillis - 1_000L))
        fetcher.errors["https://yt/UC1"] = IllegalStateException("boom")

        repo.refresh(force = false)

        val state1 = db.channelFeedRefreshStateDao().get("UC1")
        assertNotNull(state1)
        assertEquals("boom", state1!!.lastErrorMessage)
        assertEquals(0L, state1.lastSuccessfulFetchAt)

        val state2 = db.channelFeedRefreshStateDao().get("UC2")
        assertNotNull(state2)
        assertNull(state2!!.lastErrorMessage)
        assertEquals(clockMillis, state2.lastSuccessfulFetchAt)
    }

    @Test
    fun `items with null uploadedAt are dropped from cache`() = runTest {
        subscribe("UC1")
        fetcher.responses["https://yt/UC1"] = listOf(
            item("vgood", uploadedAt = clockMillis - 1_000L),
            item("vnull", uploadedAt = null),
        )
        repo.refresh(force = false)
        val rows = db.channelVideoCacheDao().getForChannel("UC1")
        assertEquals(listOf("vgood"), rows.map { it.videoId })
    }

    @Test
    fun `observeFeed only returns items within 14-day window`() = runTest {
        subscribe("UC1")
        val recent = clockMillis - (2L * 24L * 60L * 60L * 1_000L)        // 2 days ago
        val ancient = clockMillis - (20L * 24L * 60L * 60L * 1_000L)     // 20 days ago
        fetcher.responses["https://yt/UC1"] = listOf(
            item("vnew", uploadedAt = recent),
            item("vold", uploadedAt = ancient),
        )
        repo.refresh(force = false)
        val feed = repo.observeFeed().first()
        assertEquals(listOf("vnew"), feed.map { it.videoId })
    }

    @Test
    fun `empty subscription list is a no-op`() = runTest {
        repo.refresh(force = false)
        assertTrue(fetcher.totalCalls == 0)
    }

    @Test
    fun `refresh never holds more than MAX_CONCURRENT permits at once`() = runTest {
        val gateHeld = java.util.concurrent.atomic.AtomicInteger(0)
        val maxSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val channelCount = 12

        repeat(channelCount) { i ->
            subscribe("UC$i")
            fetcher.responses["https://yt/UC$i"] = listOf(item("v$i", clockMillis - 1_000L))
        }

        // Wrap the fetcher so each call records concurrent holders then yields.
        val gatingFetcher = object : ChannelFeedFetcher {
            override suspend fun fetchLatest(
                channelUrl: String,
                priorEtag: String?,
                priorLastModified: String?,
            ): ChannelFeedFetcher.FetchResult {
                val inFlight = gateHeld.incrementAndGet()
                maxSeen.updateAndGet { prev -> maxOf(prev, inFlight) }
                try {
                    kotlinx.coroutines.delay(1L)
                    return ChannelFeedFetcher.FetchResult.Items(
                        items = fetcher.responses[channelUrl] ?: emptyList(),
                        etag = null,
                        lastModified = null,
                    )
                } finally {
                    gateHeld.decrementAndGet()
                }
            }
        }
        val bounded = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = gatingFetcher,
            ioDispatcher = Dispatchers.Unconfined,
        ).also { it.currentTimeMillisProvider = { clockMillis } }

        bounded.refresh(force = false)

        assertTrue(
            "concurrency cap breached: max=$maxSeen vs ${MeFeedRepository.MAX_CONCURRENT}",
            maxSeen.get() <= MeFeedRepository.MAX_CONCURRENT,
        )
    }

    @Test
    fun `CR2 stagger spreads launch times across index multiples`() = runTest {
        // Subscribe enough channels to observe distinct launch times.
        val n = 5
        repeat(n) { i ->
            subs.subscribe(SubscribedChannel("UC$i", "https://yt/UC$i", "ch-$i", null, 1_000L + i))
        }
        // Record the time-from-start at which each fetch first runs.
        val started = mutableListOf<Long>()
        val recorder = object : ChannelFeedFetcher {
            override suspend fun fetchLatest(
                channelUrl: String,
                priorEtag: String?,
                priorLastModified: String?,
            ): ChannelFeedFetcher.FetchResult {
                synchronized(started) { started += currentTime }
                return ChannelFeedFetcher.FetchResult.Items(emptyList(), null, null)
            }
        }
        val staggered = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = recorder,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).also { it.currentTimeMillisProvider = { clockMillis } }

        staggered.refresh(force = true)
        advanceUntilIdle()

        // After the fix, each successive launch is delayed by index * STAGGER_MS.
        // Sort to be deterministic across scheduler ordering.
        val sorted = started.sorted()
        assertEquals(n, sorted.size)
        // First fetch starts at t=0 in virtual time; subsequent fetches are at
        // approximately STAGGER_MS, 2*STAGGER_MS, 3*STAGGER_MS, 4*STAGGER_MS.
        // Allow some slack for semaphore + Room writes.
        val s = MeFeedRepository.STAGGER_MS
        for (i in 1 until n) {
            val gapFromStart = sorted[i] - sorted[0]
            val expected = i * s
            assertTrue(
                "fetch #$i expected ≈${expected}ms after start, was ${gapFromStart}ms",
                gapFromStart >= expected - s / 2,
            )
        }
    }

    @Test
    fun `Stage5r2 empty fetch DOES wipe cache when previous success is older than FEED_WINDOW`() = runTest {
        subscribe("UC1")
        fetcher.responses["https://yt/UC1"] = listOf(
            item("v1", clockMillis - 1_000L),
            item("v2", clockMillis - 2_000L),
        )
        repo.refresh(force = false)
        assertEquals(2, db.channelVideoCacheDao().getForChannel("UC1").size)

        // Advance past the feed window — now the cached rows are outside
        // what observeFeed can show anyway. An empty response should clear
        // them so the cache reflects reality (dormant/emptied channel).
        clockMillis += MeFeedRepository.FEED_WINDOW_MS + 1_000L
        fetcher.responses["https://yt/UC1"] = emptyList()
        repo.refresh(force = false)

        assertTrue(
            "dormant channel (last success > FEED_WINDOW ago) must clear cache on empty fetch",
            db.channelVideoCacheDao().getForChannel("UC1").isEmpty(),
        )
    }

    @Test
    fun `Stage5r2 observeFeed caps channelIds to MAX_CHANNELS_PER_REFRESH`() = runTest {
        // Seed N channels where N > cap.
        val count = MeFeedRepository.MAX_CHANNELS_PER_REFRESH + 10
        repeat(count) { i ->
            subs.subscribe(SubscribedChannel("UC$i", "https://yt/UC$i", "ch-$i", null, 1_000L + i))
        }
        // observeFeed must not crash with SQLiteException: "too many SQL variables".
        val feed = repo.observeFeed().first()
        assertEquals(emptyList<Any>(), feed)
    }

    @Test
    fun `F1 empty fetcher result does NOT wipe previously cached rows`() = runTest {
        subscribe("UC1")
        // Priming refresh populates cache with 2 items.
        fetcher.responses["https://yt/UC1"] = listOf(
            item("v1", clockMillis - 1_000L),
            item("v2", clockMillis - 2_000L),
        )
        repo.refresh(force = false)
        assertEquals(2, db.channelVideoCacheDao().getForChannel("UC1").size)

        // Simulate a transient empty response after the TTL expires.
        clockMillis += MeFeedRepository.CACHE_TTL_MS + 1_000L
        fetcher.responses["https://yt/UC1"] = emptyList()
        repo.refresh(force = false)

        val stored = db.channelVideoCacheDao().getForChannel("UC1")
        assertEquals("empty response must not clear existing cache", 2, stored.size)

        val state = db.channelFeedRefreshStateDao().get("UC1")
        assertNotNull(state)
        assertNull(state!!.lastErrorMessage)
    }

    @Test
    fun `F4 observeFeed only returns rows for still-subscribed channels`() = runTest {
        subscribe("UC1")
        subscribe("UC2")
        val recent = clockMillis - (1L * 24L * 60L * 60L * 1_000L)
        fetcher.responses["https://yt/UC1"] = listOf(item("v1", recent))
        fetcher.responses["https://yt/UC2"] = listOf(item("v2", recent))
        repo.refresh(force = false)

        val beforeUnsub = repo.observeFeed().first().map { it.videoId }.sorted()
        assertEquals(listOf("v1", "v2"), beforeUnsub)

        subs.unsubscribe("UC2")

        val afterUnsub = repo.observeFeed().first().map { it.videoId }
        assertEquals("UC2's videos must disappear from observeFeed immediately on unsubscribe",
            listOf("v1"), afterUnsub)

        // Also verify the transactional unsubscribe cleaned up the cache and
        // refresh-state rows, not just the subscribed_channels row.
        assertTrue(db.channelVideoCacheDao().getForChannel("UC2").isEmpty())
        assertNull(db.channelFeedRefreshStateDao().get("UC2"))
    }

    @Test
    fun `F3 concurrent refresh calls do not overlap and both runs complete`() = runTest {
        // CodeRabbit Round-3 follow-up: a Semaphore-bound-only assertion does
        // not prove the refreshMutex actually serialises refresh() calls — the
        // Semaphore alone would satisfy it. Tighten the test to prove two
        // things: (a) every fetch from one refresh call completes strictly
        // before any fetch from the other begins (true mutex non-overlap),
        // and (b) both refresh(force=true) runs fully execute all channels
        // (total fetches = 2 * N, proving the second call was not starved).
        val n = 3
        repeat(n) { i ->
            subs.subscribe(SubscribedChannel("UC$i", "https://yt/UC$i", "ch-$i", null, 1_000L + i))
        }

        // Each fetch tags itself with a monotonically-increasing sequence id
        // captured at entry and exit. Caller identity per refresh() isn't
        // directly observable, so we reason from the sequence: once refresh
        // call #1 releases its channels' N fetches as a contiguous block,
        // call #2's N fetches form the next contiguous block. Interleaving
        // would be evidence the mutex didn't hold.
        val sequenced = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val counter = java.util.concurrent.atomic.AtomicLong(0)
        val recorder = object : ChannelFeedFetcher {
            override suspend fun fetchLatest(
                channelUrl: String,
                priorEtag: String?,
                priorLastModified: String?,
            ): ChannelFeedFetcher.FetchResult {
                val start = counter.getAndIncrement()
                kotlinx.coroutines.delay(1L)
                val end = counter.getAndIncrement()
                sequenced += start to end
                return ChannelFeedFetcher.FetchResult.Items(emptyList(), null, null)
            }
        }
        val bounded = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = recorder,
            ioDispatcher = Dispatchers.Unconfined,
        ).also { it.currentTimeMillisProvider = { clockMillis } }

        coroutineScope {
            awaitAll(
                async { bounded.refresh(force = true) },
                async { bounded.refresh(force = true) },
            )
        }

        // (b) both runs fully executed.
        assertEquals("both refresh calls must run all channels", 2 * n, sequenced.size)

        // (a) mutex non-overlap — sort fetches by start time and partition
        //     into two halves of size n. Within each half the fetches may
        //     interleave (that's the Semaphore(4) budget), but the maximum
        //     end-time of the first half must precede the minimum start-time
        //     of the second half. If the mutex didn't hold, the second run
        //     would interleave with the first and this invariant would fail.
        val sorted = sequenced.sortedBy { it.first }
        val firstHalf = sorted.take(n)
        val secondHalf = sorted.drop(n)
        val maxEndFirst = firstHalf.maxOf { it.second }
        val minStartSecond = secondHalf.minOf { it.first }
        assertTrue(
            "refresh runs overlap: first-half max-end=$maxEndFirst, second-half min-start=$minStartSecond",
            maxEndFirst < minStartSecond,
        )
    }

    @Test
    fun `F3 concurrent refresh respects MAX_CONCURRENT within a single run`() = runTest {
        subscribe("UC1")
        subscribe("UC2")
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maxSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val gating = object : ChannelFeedFetcher {
            override suspend fun fetchLatest(
                channelUrl: String,
                priorEtag: String?,
                priorLastModified: String?,
            ): ChannelFeedFetcher.FetchResult {
                val cur = active.incrementAndGet()
                maxSeen.updateAndGet { prev -> maxOf(prev, cur) }
                return try {
                    kotlinx.coroutines.delay(1L)
                    ChannelFeedFetcher.FetchResult.Items(emptyList(), null, null)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val bounded = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = gating,
            ioDispatcher = Dispatchers.Unconfined,
        ).also { it.currentTimeMillisProvider = { clockMillis } }

        bounded.refresh(force = true)

        assertTrue(
            "in-flight fetcher count must not exceed MAX_CONCURRENT (saw max=$maxSeen)",
            maxSeen.get() <= MeFeedRepository.MAX_CONCURRENT,
        )
    }

    @Test
    fun `F6 CancellationException is not swallowed by the catch-all`() = runTest {
        subscribe("UC1")
        val cancelled = object : ChannelFeedFetcher {
            override suspend fun fetchLatest(
                channelUrl: String,
                priorEtag: String?,
                priorLastModified: String?,
            ): ChannelFeedFetcher.FetchResult {
                throw kotlinx.coroutines.CancellationException("parent cancelled")
            }
        }
        val repo2 = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = cancelled,
            ioDispatcher = Dispatchers.Unconfined,
        ).also { it.currentTimeMillisProvider = { clockMillis } }

        var observed: Throwable? = null
        try {
            repo2.refresh(force = true)
        } catch (t: Throwable) {
            observed = t
        }
        assertTrue(
            "CancellationException must propagate (got ${observed?.javaClass?.simpleName})",
            observed is kotlinx.coroutines.CancellationException,
        )
        // And the catch-all must not have written an error-state row.
        assertNull(db.channelFeedRefreshStateDao().get("UC1"))
    }

    @Test
    fun `per-channel cache is capped to MAX_ITEMS_PER_CHANNEL`() = runTest {
        subscribe("UC1")
        val overflow = (1..(MeFeedRepository.MAX_ITEMS_PER_CHANNEL + 10)).map {
            item("v$it", uploadedAt = clockMillis - it * 1_000L)
        }
        fetcher.responses["https://yt/UC1"] = overflow
        repo.refresh(force = false)

        val stored = db.channelVideoCacheDao().getForChannel("UC1")
        assertEquals(MeFeedRepository.MAX_ITEMS_PER_CHANNEL, stored.size)
    }

    private fun item(id: String, uploadedAt: Long?, isShort: Boolean = false) =
        ChannelFeedFetcher.ChannelFeedItem(
            videoId = id,
            title = "title-$id",
            thumbnailUrl = null,
            durationSeconds = null,
            viewCount = null,
            uploadedAt = uploadedAt,
            isShort = isShort,
        )

    private class RecordingFetcher : ChannelFeedFetcher {
        val responses = mutableMapOf<String, List<ChannelFeedFetcher.ChannelFeedItem>>()
        val errors = mutableMapOf<String, Throwable>()
        private val callCounts = mutableMapOf<String, Int>()

        val totalCalls: Int get() = callCounts.values.sum()
        fun callsFor(url: String): Int = callCounts[url] ?: 0

        override suspend fun fetchLatest(
            channelUrl: String,
            priorEtag: String?,
            priorLastModified: String?,
        ): ChannelFeedFetcher.FetchResult {
            callCounts[channelUrl] = (callCounts[channelUrl] ?: 0) + 1
            errors[channelUrl]?.let { throw it }
            return ChannelFeedFetcher.FetchResult.Items(
                items = responses[channelUrl] ?: emptyList(),
                etag = null,
                lastModified = null,
            )
        }
    }
}
