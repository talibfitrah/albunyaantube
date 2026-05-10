package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 *
 * NB1 chokepoint move (Stage-3 review): the per-caller availability gate
 * that used to live here moved into [GlobalStreamResolver]. Coverage for
 * the gate itself (archived video, fail-open, defence-in-depth) is now
 * in [GlobalStreamResolverTest]. This file is back to priority-propagation
 * only.
 */
class DefaultPlayerRepositoryTest {

    private lateinit var fakeProvider: PriorityCapturingProvider
    private lateinit var resolver: GlobalStreamResolver
    private lateinit var repository: DefaultPlayerRepository

    @Before
    fun setUp() {
        fakeProvider = PriorityCapturingProvider()
        // No ContentService → gate is skipped, exposing pure priority-propagation
        // behaviour. The gate's tests live in GlobalStreamResolverTest.
        resolver = GlobalStreamResolver.createForTesting(fakeProvider)
        repository = DefaultPlayerRepository(resolver)
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

    private fun makeStreams(id: String): ResolvedStreams = ResolvedStreams(
        streamId = id,
        videoTracks = emptyList(),
        audioTracks = emptyList(),
        durationSeconds = 60,
        urlGeneratedAt = 0L,
    )

    /**
     * Captures the priority handed to the resolver provider so tests can
     * assert priority propagation end-to-end.
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
}
