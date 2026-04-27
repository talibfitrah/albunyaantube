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
            telemetry = MeRefreshTelemetry(),
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
            telemetry = MeRefreshTelemetry(),
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
            telemetry = MeRefreshTelemetry(),
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
            telemetry = MeRefreshTelemetry(),
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
            telemetry = MeRefreshTelemetry(),
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
            telemetry = MeRefreshTelemetry(),
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

    @Test
    fun notModified_path_advances_lastSuccessfulFetchAt_and_persists_etag_without_touching_cache() = runTest {
        // T2 review (Important / I-1): exercise the NotModified branch in
        // refreshOne. Pre-populate refresh state with a known prior ETag +
        // Last-Modified plus accumulated counters and a backoff window, plus
        // pre-existing cache rows. The fake fetcher always returns 304 with
        // a fresh ETag/Last-Modified pair. After refresh:
        //  - cache rows must be untouched (304 = don't replaceForChannel),
        //  - lastSuccessfulFetchAt must advance to clockMillis,
        //  - lastErrorMessage must be nulled,
        //  - new etag + lastModified from the response must be persisted,
        //  - consecutive{Error,Empty}Count must reset to 0,
        //  - backoffUntilMs must be cleared (T9 §6: any success → clear backoff).
        subscribe("UC1")
        val priorEtag = "W/\"old-etag\""
        val priorLastModified = "Mon, 31 Mar 2025 00:00:00 GMT"
        val newEtag = "W/\"new-etag\""
        val newLastModified = "Tue, 01 Apr 2025 00:00:00 GMT"
        // Seed a non-zero backoff to prove that 304 (success) clears it.
        // Make it stale so the backoff gate doesn't skip the fetch.
        val staleBackoff = clockMillis - 1L
        val oldSuccess = clockMillis - 60L * 60L * 1_000L // 1 hour ago

        // Seed prior refresh state with conditional-GET headers + counters.
        db.channelFeedRefreshStateDao().upsert(
            com.albunyaan.tube.data.local.ChannelFeedRefreshState(
                channelId = "UC1",
                lastSuccessfulFetchAt = oldSuccess,
                lastAttemptAt = oldSuccess,
                lastErrorMessage = null,
                etag = priorEtag,
                lastModified = priorLastModified,
                consecutiveErrorCount = 3,
                consecutiveEmptyCount = 2,
                backoffUntilMs = staleBackoff,
            )
        )

        // Seed pre-existing cache rows that the NotModified branch must NOT
        // touch.
        db.channelVideoCacheDao().upsertAll(
            listOf(
                com.albunyaan.tube.data.local.ChannelVideoCache(
                    videoId = "vCached1",
                    channelId = "UC1",
                    channelName = "name-UC1",
                    title = "Cached 1",
                    thumbnailUrl = null,
                    durationSeconds = null,
                    viewCount = null,
                    uploadedAt = clockMillis - 1_000L,
                    isShort = false,
                    fetchedAt = oldSuccess,
                ),
                com.albunyaan.tube.data.local.ChannelVideoCache(
                    videoId = "vCached2",
                    channelId = "UC1",
                    channelName = "name-UC1",
                    title = "Cached 2",
                    thumbnailUrl = null,
                    durationSeconds = null,
                    viewCount = null,
                    uploadedAt = clockMillis - 2_000L,
                    isShort = false,
                    fetchedAt = oldSuccess,
                ),
            )
        )

        // Fake fetcher that records the priorEtag/priorLastModified it was
        // handed and always returns NotModified.
        var seenPriorEtag: String? = "<unset>"
        var seenPriorLastModified: String? = "<unset>"
        val notModifiedFetcher = object : ChannelFeedFetcher {
            override suspend fun fetchLatest(
                channelUrl: String,
                priorEtag: String?,
                priorLastModified: String?,
            ): ChannelFeedFetcher.FetchResult {
                seenPriorEtag = priorEtag
                seenPriorLastModified = priorLastModified
                return ChannelFeedFetcher.FetchResult.NotModified(
                    etag = newEtag,
                    lastModified = newLastModified,
                )
            }
        }
        val repo304 = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = notModifiedFetcher,
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        ).also { it.currentTimeMillisProvider = { clockMillis } }

        // Force=true so freshness gate doesn't skip the fetch.
        repo304.refresh(force = true)

        // Conditional-GET headers were forwarded into the fetcher.
        assertEquals(priorEtag, seenPriorEtag)
        assertEquals(priorLastModified, seenPriorLastModified)

        // Cache rows untouched.
        val cacheRows = db.channelVideoCacheDao().getForChannel("UC1")
        assertEquals(
            "NotModified must not replace cache rows",
            listOf("vCached1", "vCached2"),
            cacheRows.map { it.videoId }.sorted(),
        )

        // Refresh state updated correctly.
        val state = db.channelFeedRefreshStateDao().get("UC1")
        assertNotNull(state)
        assertEquals(clockMillis, state!!.lastSuccessfulFetchAt)
        assertEquals(clockMillis, state.lastAttemptAt)
        assertNull(state.lastErrorMessage)
        assertEquals(newEtag, state.etag)
        assertEquals(newLastModified, state.lastModified)
        assertEquals(0, state.consecutiveErrorCount)
        assertEquals(0, state.consecutiveEmptyCount)
        // T9 §6: any success path (including 304) clears backoffUntilMs.
        assertNull(state.backoffUntilMs)
    }

    // T9 backoff tests (ANDROID-PERSONAL-02 / spec §5 + §6).

    @Test
    fun `T9 429 on first failure sets backoffUntilMs to now plus 1h`() = runTest {
        subscribe("UC1")
        fetcher.errors["https://yt/UC1"] = java.io.IOException("HTTP 429")

        repo.refresh(force = false)

        val state = db.channelFeedRefreshStateDao().get("UC1")
        assertNotNull(state)
        assertEquals(1, state!!.consecutiveErrorCount)
        assertEquals("HTTP 429", state.lastErrorMessage)
        // ATOM_429_BACKOFFS[0] = 1 hour
        val expected = clockMillis + 60L * 60L * 1_000L
        assertEquals(expected, state.backoffUntilMs)
    }

    @Test
    fun `T9 backoff window skips fetcher entirely on subsequent refresh`() = runTest {
        subscribe("UC1")
        fetcher.errors["https://yt/UC1"] = java.io.IOException("HTTP 429")

        // First refresh: records the 429 + sets backoffUntilMs = now + 1h.
        repo.refresh(force = false)
        assertEquals(1, fetcher.callsFor("https://yt/UC1"))

        // Advance past TTL (so freshness gate doesn't skip) but stay
        // within the backoff window.
        clockMillis += MeFeedRepository.CACHE_TTL_MS + 1_000L
        // Clear the canned error so a fetcher call would otherwise succeed —
        // any further call would mean the backoff gate failed.
        fetcher.errors.remove("https://yt/UC1")
        fetcher.responses["https://yt/UC1"] = listOf(item("v1", clockMillis - 1_000L))

        repo.refresh(force = false)

        // Backoff still in effect (we advanced ~30 min, ladder is 1h) —
        // fetcher must NOT have been called a second time.
        assertEquals(
            "backoff window must skip the fetcher",
            1, fetcher.callsFor("https://yt/UC1"),
        )
    }

    @Test
    fun `T9 force=true bypasses active backoff`() = runTest {
        subscribe("UC1")
        fetcher.errors["https://yt/UC1"] = java.io.IOException("HTTP 429")
        repo.refresh(force = false) // arms backoff
        assertEquals(1, fetcher.callsFor("https://yt/UC1"))

        // Inside the backoff window. Without force, fetcher would not be
        // called. With force=true (pull-to-refresh), it must be called.
        clockMillis += 5L * 60L * 1_000L // +5 min, well inside 1h backoff
        fetcher.errors.remove("https://yt/UC1")
        fetcher.responses["https://yt/UC1"] = listOf(item("v1", clockMillis - 1_000L))

        repo.refresh(force = true)

        assertEquals(
            "force=true must bypass the backoff gate",
            2, fetcher.callsFor("https://yt/UC1"),
        )
    }

    @Test
    fun `T9 repeated 429s escalate backoff to 4h then 24h`() = runTest {
        subscribe("UC1")
        fetcher.errors["https://yt/UC1"] = java.io.IOException("HTTP 429")

        // First 429 → 1h.
        repo.refresh(force = false)
        var state = db.channelFeedRefreshStateDao().get("UC1")!!
        assertEquals(clockMillis + 60L * 60L * 1_000L, state.backoffUntilMs)
        assertEquals(1, state.consecutiveErrorCount)

        // Advance past the 1h backoff. Second 429 → 4h.
        clockMillis += 60L * 60L * 1_000L + 1_000L
        repo.refresh(force = false)
        state = db.channelFeedRefreshStateDao().get("UC1")!!
        assertEquals(clockMillis + 4L * 60L * 60L * 1_000L, state.backoffUntilMs)
        assertEquals(2, state.consecutiveErrorCount)

        // Advance past 4h. Third 429 → 24h.
        clockMillis += 4L * 60L * 60L * 1_000L + 1_000L
        repo.refresh(force = false)
        state = db.channelFeedRefreshStateDao().get("UC1")!!
        assertEquals(clockMillis + 24L * 60L * 60L * 1_000L, state.backoffUntilMs)
        assertEquals(3, state.consecutiveErrorCount)

        // Fourth 429 stays clamped at the top of the ladder (24h).
        clockMillis += 24L * 60L * 60L * 1_000L + 1_000L
        repo.refresh(force = false)
        state = db.channelFeedRefreshStateDao().get("UC1")!!
        assertEquals(clockMillis + 24L * 60L * 60L * 1_000L, state.backoffUntilMs)
        assertEquals(4, state.consecutiveErrorCount)
    }

    @Test
    fun `T9 successful fetch clears backoffUntilMs`() = runTest {
        subscribe("UC1")
        fetcher.errors["https://yt/UC1"] = java.io.IOException("HTTP 429")
        repo.refresh(force = false)
        assertNotNull(db.channelFeedRefreshStateDao().get("UC1")!!.backoffUntilMs)

        // Advance past backoff, swap fetcher to a real success.
        clockMillis += 60L * 60L * 1_000L + 1_000L
        fetcher.errors.remove("https://yt/UC1")
        fetcher.responses["https://yt/UC1"] = listOf(item("v1", clockMillis - 1_000L))

        repo.refresh(force = false)

        val state = db.channelFeedRefreshStateDao().get("UC1")
        assertNotNull(state)
        assertNull("success path must clear backoff", state!!.backoffUntilMs)
        assertEquals(0, state.consecutiveErrorCount)
        assertEquals(0, state.consecutiveEmptyCount)
    }

    @Test
    fun `T9 5xx errors use 5min ladder`() = runTest {
        subscribe("UC1")
        fetcher.errors["https://yt/UC1"] = java.io.IOException("HTTP 503")

        repo.refresh(force = false)

        val state = db.channelFeedRefreshStateDao().get("UC1")
        assertNotNull(state)
        // ATOM_5XX_BACKOFFS[0] = 5 min
        val expected = clockMillis + 5L * 60L * 1_000L
        assertEquals(expected, state!!.backoffUntilMs)
        assertEquals(1, state.consecutiveErrorCount)
    }

    // Note (T9 / CR2 flake): a unit test for "timeout does NOT increment
    // consecutiveErrorCount" was scoped out for two reasons:
    //  1. Triggering the outer withTimeout(PER_CHANNEL_TIMEOUT_MS) requires
    //     advancing virtual time by ~15 s, which leaks into the CR2 stagger
    //     test (other tests in the same Robolectric sandbox observe a
    //     polluted scheduler and the stagger assertion fails).
    //  2. Constructing TimeoutCancellationException directly is blocked by
    //     its `internal` constructor.
    // The behaviour is covered by inspection: the catch (toc:
    // TimeoutCancellationException) branch in refreshOne explicitly
    // preserves the prior counters / backoff / etag / lastModified —
    // see `// T9: timeouts do NOT increment consecutiveErrorCount` comment
    // in MeFeedRepository.refreshOne. The instrumented test in T12 will
    // exercise this against the real OkHttp client + a slow MockWebServer.

    /**
     * ANDROID-PERSONAL-02 [Bug 3]: an OUTER cancellation (e.g. the
     * worker's [withTimeout(WORKER_TIMEOUT_MS)] firing while a fetcher is
     * still suspended in its inner per-channel withTimeout) must propagate
     * — it must NOT be absorbed into the soft "per-channel timeout"
     * branch.
     *
     * Without the fix, [TimeoutCancellationException] caught before the
     * generic [CancellationException] would route ANY TCE through the
     * timeout branch, including the outer cancellation that descends into
     * the inner withTimeout block as a TCE on the same scope.
     *
     * Construction: install a fetcher that suspends indefinitely. Wrap
     * [refresh] in a small outer [withTimeout]. The outer timeout fires
     * → parent scope cancels → the cancellation reaches the fetcher's
     * `delay` and surfaces as a CE inside refreshOne's catch. With the
     * fix, `currentCoroutineContext().isActive` is false → re-throw → the
     * outer withTimeout sees the propagation. The refresh-state row is
     * either absent (cancelled before write) or, at minimum, NOT marked
     * with the soft "timeout after ..." error message.
     */
    @Test
    fun `Bug 3 outer cancellation propagates and does not mark per-channel timeout`() = runTest {
        subscribe("UC1")
        val hangingFetcher = object : ChannelFeedFetcher {
            override suspend fun fetchLatest(
                channelUrl: String,
                priorEtag: String?,
                priorLastModified: String?,
            ): ChannelFeedFetcher.FetchResult {
                // Sleep far longer than the outer withTimeout below.
                kotlinx.coroutines.delay(60L * 60L * 1_000L)
                return ChannelFeedFetcher.FetchResult.Items(emptyList(), null, null)
            }
        }
        val repoH = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = hangingFetcher,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            telemetry = MeRefreshTelemetry(),
        ).also { it.currentTimeMillisProvider = { clockMillis } }

        var caught: Throwable? = null
        try {
            kotlinx.coroutines.withTimeout(50L) {
                repoH.refresh(force = true)
            }
        } catch (t: Throwable) {
            caught = t
        }
        // (a) The outer cancellation propagated — the test observed it,
        // not a swallowed soft-timeout.
        assertTrue(
            "outer cancellation must surface to caller (got ${caught?.javaClass?.simpleName})",
            caught is kotlinx.coroutines.CancellationException,
        )
        // (b) The refresh-state row was NOT marked with the soft
        // "timeout after ..." message that the per-channel timeout branch
        // writes. Either no row exists yet (cancelled before write) or
        // the existing row's lastErrorMessage is null/non-timeout.
        val state = db.channelFeedRefreshStateDao().get("UC1")
        if (state != null) {
            val msg = state.lastErrorMessage
            assertTrue(
                "outer cancellation must not be recorded as a per-channel timeout (msg=$msg)",
                msg == null || !msg.contains("timeout after"),
            )
        }
    }

    // Note: a positive-case unit test for "inner per-channel timeout
    // routes through the soft-timeout branch and preserves prior
    // counters / etag / lastModified / backoffUntilMs" was scoped out
    // for the same reason as the original T9 / CR2 flake note above:
    // advancing virtual time by ~15 s pollutes the CR2 stagger test
    // sharing the Robolectric sandbox. The behaviour is guaranteed by
    // inspection — the `if (ce is TimeoutCancellationException)` branch
    // in refreshOne uses `previous?.copy(...)` so prior counters /
    // etag / lastModified / backoffUntilMs are preserved by structural
    // copy. The instrumented test in T12 against the real OkHttp client
    // + slow MockWebServer exercises this end-to-end.

    @Test
    fun `T9 round-robin picks oldest-fetch-first`() = runTest {
        // Seed two channels: UC_OLD has old success, UC_NEW has very recent.
        // perTickBudget=1 forces only one to refresh — it must be UC_OLD.
        subs.subscribe(SubscribedChannel("UC_OLD", "https://yt/UC_OLD", "old", null, 5_000L))
        subs.subscribe(SubscribedChannel("UC_NEW", "https://yt/UC_NEW", "new", null, 1_000L))
        // Note: subscribedAt prefers UC_OLD as recent (5_000 > 1_000) under
        // the prior sort — but round-robin should pick UC_OLD only because
        // its lastSuccessfulFetchAt is the oldest. Force the ages directly.
        db.channelFeedRefreshStateDao().upsert(
            com.albunyaan.tube.data.local.ChannelFeedRefreshState(
                channelId = "UC_OLD",
                lastSuccessfulFetchAt = clockMillis - 10L * 24L * 60L * 60L * 1_000L,
                lastAttemptAt = clockMillis - 10L * 24L * 60L * 60L * 1_000L,
                lastErrorMessage = null,
            )
        )
        db.channelFeedRefreshStateDao().upsert(
            com.albunyaan.tube.data.local.ChannelFeedRefreshState(
                channelId = "UC_NEW",
                lastSuccessfulFetchAt = clockMillis - 10_000L, // 10s ago
                lastAttemptAt = clockMillis - 10_000L,
                lastErrorMessage = null,
            )
        )
        fetcher.responses["https://yt/UC_OLD"] = listOf(item("vo", clockMillis - 1_000L))
        fetcher.responses["https://yt/UC_NEW"] = listOf(item("vn", clockMillis - 1_000L))

        repo.refresh(force = true, perTickBudget = 1)

        // UC_OLD must have been picked; UC_NEW must not.
        assertEquals(1, fetcher.callsFor("https://yt/UC_OLD"))
        assertEquals(0, fetcher.callsFor("https://yt/UC_NEW"))
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
