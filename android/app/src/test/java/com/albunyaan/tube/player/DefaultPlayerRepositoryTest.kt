package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.model.HomeFeedResult
import com.albunyaan.tube.data.source.AvailabilityCheckType
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [DefaultPlayerRepository] verifying that the [Priority]
 * argument is plumbed through to the underlying [GlobalStreamResolver]
 * (and on to its provider) — closing
 * ANDROID-PERSONAL-02 round 2 [Bug A]: previously the repository hardcoded
 * [Priority.PLAYER] regardless of caller intent, so non-player callers
 * (prefetch, download worker) silently rode the player bypass and skipped
 * the rate-limit + cooldown gates.
 */
class DefaultPlayerRepositoryTest {

    private lateinit var fakeProvider: PriorityCapturingProvider
    private lateinit var resolver: GlobalStreamResolver
    private lateinit var fakeContentService: FakeContentService
    private lateinit var repository: DefaultPlayerRepository

    @Before
    fun setUp() {
        fakeProvider = PriorityCapturingProvider()
        resolver = GlobalStreamResolver.createForTesting(fakeProvider)
        fakeContentService = FakeContentService()
        repository = DefaultPlayerRepository(resolver, fakeContentService)
    }

    /**
     * Regression: assertions ride on what the *provider observed*, not on
     * the [resolveStreams] return value. Under runTest's StandardTestDispatcher
     * the resolver's internal `resolverScope.async` runs on real
     * [kotlinx.coroutines.Dispatchers.IO] while `withTimeoutOrNull` may
     * advance virtual time past the timeout before the IO async completes,
     * so the return value can spuriously be null. The provider's
     * AtomicReference is set BEFORE the IO async returns, so it's a
     * stable signal — same pattern as
     * [GlobalStreamResolverTest.PLAYER priority is forwarded to the provider].
     */

    @Test
    fun `default priority is PLAYER (live playback bypass)`() = runTest {
        fakeProvider.setResult("video1", makeStreams("video1"))

        // Note: NO `priority = ...` argument supplied — exercises the default.
        repository.resolveStreams("video1")

        assertEquals(
            "Default priority must be PLAYER for back-compat with the live playback path",
            Priority.PLAYER,
            fakeProvider.observedPriority.get(),
        )
    }

    @Test
    fun `explicit PLAYER priority is forwarded`() = runTest {
        fakeProvider.setResult("video2", makeStreams("video2"))

        repository.resolveStreams("video2", priority = Priority.PLAYER)

        assertEquals(
            "PLAYER priority must reach the resolver provider",
            Priority.PLAYER,
            fakeProvider.observedPriority.get(),
        )
    }

    @Test
    fun `USER_FOREGROUND priority is forwarded (round 2 Bug A)`() = runTest {
        fakeProvider.setResult("video3", makeStreams("video3"))

        // Mirrors the prefetch / DownloadWorker call site: declare the lane
        // explicitly so the resolve goes through rate-limit + cooldown gates.
        repository.resolveStreams("video3", priority = Priority.USER_FOREGROUND)

        assertEquals(
            "USER_FOREGROUND must reach the provider, not be silently overridden to PLAYER",
            Priority.USER_FOREGROUND,
            fakeProvider.observedPriority.get(),
        )
    }

    @Test
    fun `BACKGROUND_REFRESH priority is forwarded`() = runTest {
        fakeProvider.setResult("video4", makeStreams("video4"))

        repository.resolveStreams("video4", priority = Priority.BACKGROUND_REFRESH)

        assertEquals(
            "BACKGROUND_REFRESH must reach the provider for the lowest-priority lane",
            Priority.BACKGROUND_REFRESH,
            fakeProvider.observedPriority.get(),
        )
    }

    @Test
    fun `VISIBLE_INTERACTIVE priority is forwarded`() = runTest {
        fakeProvider.setResult("video-visible", makeStreams("video-visible"))

        repository.resolveStreams("video-visible", priority = Priority.VISIBLE_INTERACTIVE)

        assertEquals(
            "VISIBLE_INTERACTIVE must reach the provider for fast visible metadata work",
            Priority.VISIBLE_INTERACTIVE,
            fakeProvider.observedPriority.get(),
        )
    }

    @Test
    fun `forceRefresh and priority compose correctly`() = runTest {
        fakeProvider.setResult("video5", makeStreams("video5"))

        repository.resolveStreams(
            videoId = "video5",
            forceRefresh = true,
            priority = Priority.USER_FOREGROUND,
        )

        assertEquals(
            "Priority must propagate even when forceRefresh=true",
            Priority.USER_FOREGROUND,
            fakeProvider.observedPriority.get(),
        )
    }

    // ── C1+C2 Availability Gate Tests ─────────────────────────────────────────
    // These cover the playlist-queue and shorts leaks T12 missed: every caller
    // hits this chokepoint, so gating here closes both paths in one place.

    @Test
    fun `archived video throws ContentUnavailableException and never calls resolver`() = runTest {
        fakeContentService.setAvailable("vid_archived", false)
        // Provider would have returned valid streams — but the gate must fire first.
        fakeProvider.setResult("vid_archived", makeStreams("vid_archived"))

        try {
            repository.resolveStreams("vid_archived")
            fail("Expected ContentUnavailableException for archived video")
        } catch (e: ContentUnavailableException) {
            assertEquals(
                "Exception must carry the archived videoId",
                "vid_archived",
                e.videoId,
            )
        }

        assertEquals(
            "Archived video MUST NOT reach the underlying resolver provider",
            0,
            fakeProvider.callCount.get(),
        )
        assertEquals(
            "Availability gate must have run exactly once",
            1,
            fakeContentService.verifyCallCount.get(),
        )
    }

    @Test
    fun `available video proceeds to resolver`() = runTest {
        fakeContentService.setAvailable("vid_ok", true)
        fakeProvider.setResult("vid_ok", makeStreams("vid_ok"))

        val resolved = repository.resolveStreams("vid_ok")

        assertEquals(
            "Resolver provider must be reached for available video",
            1,
            fakeProvider.callCount.get(),
        )
        assertEquals(
            "Provider must observe the priority for available video",
            Priority.PLAYER,
            fakeProvider.observedPriority.get(),
        )
        // Note: `resolved` may legitimately be null under runTest's virtual time
        // (see class-level KDoc on the IO async timeout race) — assertions ride
        // on the provider counter instead.
        if (resolved != null) {
            assertEquals("vid_ok", resolved.streamId)
        }
    }

    @Test
    fun `availability check transport error fails open and proceeds to resolver`() = runTest {
        // Mirrors offline / 5xx behaviour: a transport error must NOT block a
        // valid user from playing. Same fail-open policy as T10/T11/T12.
        fakeContentService.setError("vid_offline", RuntimeException("network timeout"))
        fakeProvider.setResult("vid_offline", makeStreams("vid_offline"))

        // Must not throw — the gate is fail-open on transport errors.
        repository.resolveStreams("vid_offline")

        assertEquals(
            "Transport error on availability check must NOT block resolution",
            1,
            fakeProvider.callCount.get(),
        )
    }

    @Test
    fun `archived video bypasses resolver even with priority and forceRefresh`() = runTest {
        // Defence-in-depth: proves the gate fires regardless of caller-supplied
        // priority lane or forceRefresh — no path through resolveStreams can
        // sneak past the availability check.
        fakeContentService.setAvailable("vid_archived2", false)
        fakeProvider.setResult("vid_archived2", makeStreams("vid_archived2"))

        try {
            repository.resolveStreams(
                videoId = "vid_archived2",
                forceRefresh = true,
                priority = Priority.USER_FOREGROUND,
            )
            fail("Expected ContentUnavailableException")
        } catch (_: ContentUnavailableException) {
            // expected
        }

        assertEquals(0, fakeProvider.callCount.get())
    }

    private fun makeStreams(id: String): ResolvedStreams = ResolvedStreams(
        streamId = id,
        videoTracks = emptyList(),
        audioTracks = emptyList(),
        durationSeconds = 60,
        urlGeneratedAt = 0L,
    )

    /**
     * Captures the priority handed to the resolver provider so tests can
     * assert priority propagation end-to-end. Also exposes [callCount] so the
     * C1+C2 gate tests can prove the resolver is NEVER reached for archived
     * videos (the gate must short-circuit before this provider is invoked).
     */
    private class PriorityCapturingProvider : StreamResolutionProvider {
        val observedPriority = AtomicReference<Priority?>(null)
        val callCount = AtomicInteger(0)
        private val results = mutableMapOf<String, ResolvedStreams?>()

        fun setResult(videoId: String, result: ResolvedStreams?) {
            results[videoId] = result
        }

        override suspend fun resolveStreams(
            videoId: String,
            forceRefresh: Boolean,
            priority: Priority,
        ): ResolvedStreams? {
            callCount.incrementAndGet()
            observedPriority.set(priority)
            return results[videoId]
        }
    }

    /**
     * In-test fake [ContentService] that lets us drive `verifyAvailable`
     * deterministically — return true / false / throw per videoId — and
     * count gate invocations. Other ContentService methods are unused here
     * and either return empty data or fail loudly so accidental new calls
     * surface as test failures.
     */
    private class FakeContentService : ContentService {
        val verifyCallCount = AtomicInteger(0)
        private val availability = mutableMapOf<String, Boolean>()
        private val errors = mutableMapOf<String, Throwable>()

        fun setAvailable(videoId: String, available: Boolean) {
            availability[videoId] = available
        }

        fun setError(videoId: String, error: Throwable) {
            errors[videoId] = error
        }

        override suspend fun verifyAvailable(
            type: AvailabilityCheckType,
            youtubeId: String,
        ): Boolean {
            verifyCallCount.incrementAndGet()
            errors[youtubeId]?.let { throw it }
            // Default to true (available) when the test didn't say otherwise —
            // matches T12's existing FakeContentService behaviour.
            return availability[youtubeId] ?: true
        }

        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState,
            query: String?,
        ): CursorResponse = throw UnsupportedOperationException("not used in repository gate tests")

        override suspend fun fetchHomeFeed(
            cursor: String?,
            categoryLimit: Int,
            contentLimit: Int,
            category: String?,
        ): HomeFeedResult = throw UnsupportedOperationException("not used in repository gate tests")

        override suspend fun search(
            query: String,
            type: String?,
            limit: Int,
        ): List<ContentItem> = throw UnsupportedOperationException("not used in repository gate tests")

        override suspend fun fetchCategories(): List<Category> =
            throw UnsupportedOperationException("not used in repository gate tests")

        override suspend fun fetchSubcategories(parentId: String): List<Category> =
            throw UnsupportedOperationException("not used in repository gate tests")
    }
}
