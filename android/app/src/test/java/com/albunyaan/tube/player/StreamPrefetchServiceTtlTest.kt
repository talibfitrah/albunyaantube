package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8 / N8 regression coverage: TTL on the prefetch result cache inside
 * [DefaultStreamPrefetchService] must bound the archive-bypass window.
 *
 * Threat model: a video archived AFTER [DefaultStreamPrefetchService.triggerPrefetch]
 * resolved its streams used to be replayable forever from the in-memory
 * cache because [DefaultStreamPrefetchService.awaitOrConsumePrefetch] returned
 * the cached entry directly without any time check.
 *
 * Driven via an injected clock (production uses System.currentTimeMillis;
 * tests use a mutable lambda) so we don't depend on real wall-clock time.
 *
 * Test strategy: rather than try to drive [Dispatchers.IO] (the production
 * service-scope used by [DefaultStreamPrefetchService.triggerPrefetch]) from
 * `runTest`'s virtual scheduler — which doesn't propagate, so prefetch
 * coroutines wouldn't complete deterministically — we use the
 * [DefaultStreamPrefetchService.primeCacheForTesting] seam to populate the
 * cache directly. That keeps the test hermetic and laser-focused on the
 * consumer-side TTL logic, which is the actual behaviour under regression test.
 *
 * The fully-end-to-end "trigger → cache populated → expire → consume" path is
 * inherently coupled to the real dispatcher and isn't worth jumping through
 * fragile hoops to cover here. The TTL seam tests below assert exactly the
 * branch the bug report flagged: the consumer-side decision to serve vs evict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class StreamPrefetchServiceTtlTest {

    private lateinit var globalResolver: GlobalStreamResolver
    private lateinit var rateLimiter: ExtractionRateLimiter
    private lateinit var mpdGenerator: MultiRepresentationMpdGenerator
    private lateinit var mpdRegistry: SyntheticDashMpdRegistry
    private lateinit var featureFlags: PlaybackFeatureFlags
    private lateinit var segmentPreBuffer: SegmentPreBuffer
    private lateinit var service: DefaultStreamPrefetchService

    /**
     * Mutable wall-clock pointer driven by the tests. Bumping it past the
     * TTL boundary simulates time elapsing between prefetch and consumption
     * without `runTest` virtual-time tricks.
     */
    private var now: Long = 1_000_000L

    @Before
    fun setUp() {
        rateLimiter = ExtractionRateLimiter().apply {
            setTestClock { now }
        }

        // The resolver is unused by the consumer-side TTL paths but mockito
        // can't easily mock final classes that have intrinsics; we use the
        // test factory with a no-op provider. No backend gate either — these
        // tests cover the cache TTL, not the HEAD probe (which is covered
        // elsewhere by GlobalStreamResolver tests).
        globalResolver = GlobalStreamResolver.createForTesting(
            provider = StreamResolutionProvider { videoId, _, _ -> createResolvedStreams(videoId) },
            contentService = null,
        )

        mpdGenerator = MultiRepresentationMpdGenerator()
        mpdRegistry = SyntheticDashMpdRegistry()

        // PlaybackFeatureFlags pulls SharedPreferences off ApplicationContext.
        // We don't exercise the MPD pre-gen path here (the test primes the
        // cache directly via primeCacheForTesting), but the constructor
        // requires the dependency, so a minimal mock is enough.
        featureFlags = mock {
            on { isMpdPrefetchEnabled } doReturn false
            on { isSegmentPreloadEnabled } doReturn false
        }

        // SegmentPreBuffer needs Context + caches; never invoked because
        // [isSegmentPreloadEnabled] is false. Mock keeps the constructor happy.
        segmentPreBuffer = mock()

        service = DefaultStreamPrefetchService(
            globalResolver = globalResolver,
            rateLimiter = rateLimiter,
            mpdGenerator = mpdGenerator,
            mpdRegistry = mpdRegistry,
            featureFlags = featureFlags,
            segmentPreBuffer = segmentPreBuffer,
        ).apply {
            setClockForTesting { now }
        }
    }

    @After
    fun tearDown() {
        globalResolver.setOnJobCleanupListener(null)
    }

    // ── TTL Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `awaitOrConsumePrefetch within TTL returns cached result`() = runTest {
        val streams = createResolvedStreams("video_1")
        // Stamp the cache 5s ago. The TTL is 30s, so this should be served.
        service.primeCacheForTesting("video_1", streams, cachedAtMs = now - 5_000L)

        val result = service.awaitOrConsumePrefetch("video_1")

        assertNotNull("Within-TTL cached entry must be returned", result)
        assertEquals("video_1", result?.streamId)
    }

    @Test
    fun `awaitOrConsumePrefetch past TTL drops the cache and returns null`() = runTest {
        val streams = createResolvedStreams("video_1")
        // Stamp the cache far enough in the past that TTL has lapsed. The
        // consumer must evict and return null so the caller re-resolves
        // through the gate (in production: globalResolver.resolveStreams).
        val staleAge = DefaultStreamPrefetchService.PREFETCH_RESULT_TTL_MS + 1_000L
        service.primeCacheForTesting("video_1", streams, cachedAtMs = now - staleAge)

        val result = service.awaitOrConsumePrefetch("video_1")

        assertNull(
            "Expired cache must return null so caller re-resolves through the gate",
            result,
        )

        // Defensive: the eviction must be persistent. A second consume must
        // also return null even though the test resolver could theoretically
        // serve a fresh result — the consumer-side TTL path doesn't kick off
        // a real resolve when there's no in-flight job.
        val resultAgain = service.consumePrefetch("video_1")
        assertNull(
            "Expired entry must stay evicted; second consume must also return null",
            resultAgain,
        )
    }

    @Test
    fun `consumePrefetch within TTL returns cached result`() {
        val streams = createResolvedStreams("video_1")
        service.primeCacheForTesting("video_1", streams, cachedAtMs = now - 5_000L)

        val result = service.consumePrefetch("video_1")

        assertNotNull("Within-TTL cached entry must be returned synchronously", result)
        assertEquals("video_1", result?.streamId)
    }

    @Test
    fun `consumePrefetch past TTL drops the cache and returns null`() {
        val streams = createResolvedStreams("video_1")
        val staleAge = DefaultStreamPrefetchService.PREFETCH_RESULT_TTL_MS + 1_000L
        service.primeCacheForTesting("video_1", streams, cachedAtMs = now - staleAge)

        val result = service.consumePrefetch("video_1")

        assertNull("Expired cache (sync path) must return null", result)
    }

    @Test
    fun `cache exactly at TTL boundary is still served`() {
        // Boundary verification: ageMs > TTL is the eviction condition, so an
        // entry exactly at TTL ms old must still be returned. This pins the
        // semantics of the TTL boundary — change to ≥ would break this test.
        val streams = createResolvedStreams("video_1")
        service.primeCacheForTesting(
            "video_1",
            streams,
            cachedAtMs = now - DefaultStreamPrefetchService.PREFETCH_RESULT_TTL_MS,
        )

        val result = service.consumePrefetch("video_1")

        assertNotNull("Entry exactly at TTL boundary must still be served", result)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createResolvedStreams(streamId: String) = ResolvedStreams(
        streamId = streamId,
        videoTracks = listOf(
            VideoTrack(
                url = "https://example.com/$streamId/video.mp4",
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                bitrate = 2000000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = false,
            )
        ),
        audioTracks = listOf(
            AudioTrack(
                url = "https://example.com/$streamId/audio.m4a",
                mimeType = "audio/mp4",
                bitrate = 128000,
                codec = "mp4a",
            )
        ),
        subtitleTracks = emptyList(),
        durationSeconds = 300,
        hlsUrl = null,
        dashUrl = null,
        urlGeneratedAt = 0L,
        urlTimebaseVersion = ResolvedStreams.URL_TIMEBASE_VERSION,
    )
}
