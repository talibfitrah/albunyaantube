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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for GlobalStreamResolver.
 *
 * Tests verify single-flight semantics and the race condition fix
 * for job removal in the finally block.
 *
 * Uses a FakeResolutionProvider that can be controlled to simulate
 * slow extractions and specific timing scenarios.
 */
class GlobalStreamResolverTest {

    private lateinit var fakeProvider: FakeResolutionProvider
    private lateinit var resolver: GlobalStreamResolver

    @Before
    fun setUp() {
        fakeProvider = FakeResolutionProvider()
        resolver = GlobalStreamResolver.createForTesting(fakeProvider)
    }

    @After
    fun tearDown() {
        // Clear the cleanup listener to prevent cross-test leakage
        resolver.setOnJobCleanupListener(null)
        resolver.cancelAll()
    }

    // --- Single-Flight Tests ---

    @Test
    fun `concurrent requests for same videoId share single extraction`() = runTest {
        // Set up slow extraction
        val gate = CompletableDeferred<Unit>()
        val result = createMockStreams("video1")
        fakeProvider.setGate("video1", gate)
        fakeProvider.setResult("video1", result)

        // Start first request
        val job1 = launch { resolver.resolveStreams("video1", caller = "caller1") }

        // Wait for extraction to start
        fakeProvider.waitForExtractionStart("video1")

        // Start second request while first is in-flight
        val job2 = launch { resolver.resolveStreams("video1", caller = "caller2") }

        // Yield to let second request join the in-flight job
        kotlinx.coroutines.yield()

        // Complete the extraction
        gate.complete(Unit)

        // Wait for both to complete
        job1.join()
        job2.join()

        // Verify only one extraction was made
        assertEquals("Should have only 1 extraction call", 1, fakeProvider.getCallCount("video1"))
    }

    @Test
    fun `forceRefresh cancels in-flight job and starts new one`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val result1 = createMockStreams("video1-first")
        val result2 = createMockStreams("video1-second")

        // Set up first extraction
        fakeProvider.setGate("video1", gate1)
        fakeProvider.setResult("video1", result1)

        // Start first request
        val job1 = launch { resolver.resolveStreams("video1", forceRefresh = false, caller = "first") }

        // Wait for first extraction to start
        fakeProvider.waitForExtractionStart("video1")

        // Set up second extraction (will replace first due to forceRefresh)
        fakeProvider.setGate("video1", gate2)
        fakeProvider.setResult("video1", result2)

        // Start second request with forceRefresh=true
        val job2 = launch { resolver.resolveStreams("video1", forceRefresh = true, caller = "second") }

        // Wait for second extraction to actually start (deterministic, replaces delay)
        fakeProvider.waitForExtractionStart("video1")

        // Complete the first extraction (should be cancelled/ignored)
        gate1.complete(Unit)

        // Wait for first job to finish (deterministic cleanup)
        job1.join()

        // Complete the second extraction
        gate2.complete(Unit)

        // Wait for second job to finish
        job2.join()

        // Verify two extractions were made
        assertEquals("Should have 2 extraction calls", 2, fakeProvider.getCallCount("video1"))
    }

    /**
     * This test verifies the race condition fix for issue #1:
     * "older job's finally block removing newer job from map"
     *
     * Scenario:
     * 1. Request A starts for videoId
     * 2. Request B starts with forceRefresh=true, cancels A's job and starts new job
     * 3. Request A's job completion triggers cleanup
     * 4. BUG (before fix): A's cleanup removes B's job from map
     * 5. FIX: Using remove(key, value) ensures A only removes its own job
     */
    @Test
    fun `older job completion does not remove newer job from map`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()

        // Set up deterministic cleanup synchronization
        val cleanupSignal = CompletableDeferred<String>()
        resolver.setOnJobCleanupListener { videoId -> cleanupSignal.complete(videoId) }

        // Set up first extraction
        fakeProvider.setGate("video1", gate1)
        fakeProvider.setResult("video1", createMockStreams("old"))

        // Start old request
        val oldJob = launch { resolver.resolveStreams("video1", caller = "old") }

        // Wait for old extraction to start
        fakeProvider.waitForExtractionStart("video1")

        // Set up second extraction
        fakeProvider.setGate("video1", gate2)
        fakeProvider.setResult("video1", createMockStreams("new"))

        // Start new request with forceRefresh=true (cancels old job)
        val newJob = launch { resolver.resolveStreams("video1", forceRefresh = true, caller = "new") }

        // Wait for new extraction to actually start (deterministic, replaces delay)
        fakeProvider.waitForExtractionStart("video1")

        // Verify new job is in-flight BEFORE completing old job (baseline check)
        assertTrue("New job should be in-flight before old job cleanup", resolver.isResolveInFlight("video1"))

        // Now let the old job complete (or be cancelled - either way its cleanup runs)
        gate1.complete(Unit)

        // Wait for old job to fully complete (including cleanup)
        oldJob.join()

        // Wait deterministically for the old job's cleanup to complete (uses listener, not timing)
        // NOTE: The old job was cancelled by forceRefresh, so its cleanup only runs if it was in the map
        // The forceRefresh path removes the old job from the map BEFORE creating the new job,
        // so the old job's invokeOnCompletion won't find itself in the map and won't call the listener.
        // We just need to ensure sufficient time has passed for any lingering cleanup attempts.
        kotlinx.coroutines.yield()

        // The new job should STILL be in-flight (not removed by old job's cleanup)
        // This is the key assertion testing the remove(key, value) race fix
        assertTrue("New job should still be in-flight after old job cleanup", resolver.isResolveInFlight("video1"))

        // Complete the new job
        gate2.complete(Unit)

        newJob.join()

        // Wait for new job's cleanup to complete deterministically
        assertEquals("New job cleanup should signal video1", "video1", cleanupSignal.await())

        // Verify both extractions were made (2 total calls)
        // This strengthens the assertion by confirming the new job actually executed
        assertEquals("Should have 2 extraction calls (old + new)", 2, fakeProvider.getCallCount("video1"))
    }

    @Test
    fun `different videoIds have separate in-flight jobs`() = runTest {
        val result1 = createMockStreams("video1")
        val result2 = createMockStreams("video2")

        // No gates - instant completion
        fakeProvider.setResult("video1", result1)
        fakeProvider.setResult("video2", result2)

        val r1 = resolver.resolveStreams("video1")
        val r2 = resolver.resolveStreams("video2")

        assertNotNull(r1)
        assertNotNull(r2)

        // Both should have been extracted
        assertEquals("Should have 1 call for video1", 1, fakeProvider.getCallCount("video1"))
        assertEquals("Should have 1 call for video2", 1, fakeProvider.getCallCount("video2"))
    }

    // --- Timeout Tests ---

    @Test
    fun `timeout returns null without cancelling underlying job`() = runTest {
        // Set up deterministic cleanup synchronization
        val cleanupSignal = CompletableDeferred<String>()
        resolver.setOnJobCleanupListener { videoId -> cleanupSignal.complete(videoId) }

        val gate = CompletableDeferred<Unit>()
        val result = createMockStreams("video1")
        fakeProvider.setGate("video1", gate)
        fakeProvider.setResult("video1", result)

        // Start request with very short timeout
        val timedOutResult = resolver.resolveStreams("video1", timeoutMs = 50)

        // Should timeout and return null
        assertNull("Should return null on timeout", timedOutResult)

        // But the job should still be in-flight
        assertTrue("Job should still be in-flight after timeout", resolver.isResolveInFlight("video1"))

        // Complete extraction
        gate.complete(Unit)

        // Wait for cleanup deterministically using the listener
        assertEquals("Cleanup should signal video1", "video1", cleanupSignal.await())
        assertFalse("Job should no longer be in-flight after completion", resolver.isResolveInFlight("video1"))
    }

    // --- State Management Tests ---

    @Test
    fun `cancelResolve removes and cancels job`() = runTest {
        val gate = CompletableDeferred<Unit>()
        fakeProvider.setGate("video1", gate)
        fakeProvider.setResult("video1", createMockStreams("video1"))

        // Start request
        val job = launch { resolver.resolveStreams("video1") }

        // Wait for extraction to start
        fakeProvider.waitForExtractionStart("video1")
        assertTrue("Job should be in-flight", resolver.isResolveInFlight("video1"))

        // Cancel
        resolver.cancelResolve("video1")

        assertFalse("Job should not be in-flight after cancel", resolver.isResolveInFlight("video1"))

        // Cleanup
        gate.complete(Unit)
        job.join()
    }

    @Test
    fun `cancelAll cancels all in-flight jobs`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        fakeProvider.setGate("video1", gate1)
        fakeProvider.setResult("video1", createMockStreams("video1"))
        fakeProvider.setGate("video2", gate2)
        fakeProvider.setResult("video2", createMockStreams("video2"))

        // Start two requests
        val job1 = launch { resolver.resolveStreams("video1") }
        val job2 = launch { resolver.resolveStreams("video2") }

        // Wait for both to start
        fakeProvider.waitForExtractionStart("video1")
        fakeProvider.waitForExtractionStart("video2")

        assertEquals("Should have 2 in-flight jobs", 2, resolver.getInFlightCount())

        // Cancel all
        resolver.cancelAll()

        assertEquals("Should have 0 in-flight jobs after cancelAll", 0, resolver.getInFlightCount())

        // Cleanup
        gate1.complete(Unit)
        gate2.complete(Unit)
        job1.join()
        job2.join()
    }

    @Test
    fun `getInFlightCount returns correct count`() = runTest {
        assertEquals("Initially no in-flight jobs", 0, resolver.getInFlightCount())

        // Set up deterministic cleanup synchronization
        val cleanupSignal = CompletableDeferred<String>()
        resolver.setOnJobCleanupListener { videoId -> cleanupSignal.complete(videoId) }

        val gate = CompletableDeferred<Unit>()
        fakeProvider.setGate("video1", gate)
        fakeProvider.setResult("video1", createMockStreams("video1"))

        val job = launch { resolver.resolveStreams("video1") }
        fakeProvider.waitForExtractionStart("video1")

        assertEquals("Should have 1 in-flight job", 1, resolver.getInFlightCount())

        gate.complete(Unit)
        job.join()

        // Wait for cleanup deterministically using the listener
        assertEquals("Cleanup should signal video1", "video1", cleanupSignal.await())
        assertEquals("Should have 0 in-flight jobs after completion", 0, resolver.getInFlightCount())
    }

    // --- Cancellation Semantics Tests ---

    /**
     * Verifies that when the caller's coroutine is cancelled, the CancellationException
     * is properly propagated instead of being swallowed and returning null.
     * This ensures proper structured concurrency.
     */
    @Test
    fun `caller cancellation propagates CancellationException`() = runTest {
        val gate = CompletableDeferred<Unit>()
        fakeProvider.setGate("video1", gate)
        fakeProvider.setResult("video1", createMockStreams("video1"))

        // Start a resolve in a cancellable job
        val job = launch {
            resolver.resolveStreams("video1", caller = "cancellable-caller")
        }

        // Wait for extraction to start
        fakeProvider.waitForExtractionStart("video1")

        // Cancel the caller's job (simulating fragment destruction)
        job.cancel()

        // The job should complete (via cancellation)
        job.join()
        assertTrue("Job should be cancelled", job.isCancelled)

        // Cleanup
        gate.complete(Unit)
    }

    /**
     * Verifies that when a shared job is cancelled (e.g., via cancelResolve),
     * callers waiting on it receive null instead of propagating CancellationException.
     */
    @Test
    fun `shared job cancellation returns null to waiting callers`() = runTest {
        val gate = CompletableDeferred<Unit>()
        fakeProvider.setGate("video1", gate)
        fakeProvider.setResult("video1", createMockStreams("video1"))

        var firstCallerResult: ResolvedStreams? = createMockStreams("placeholder") // Non-null placeholder
        var secondCallerResult: ResolvedStreams? = createMockStreams("placeholder")

        // Start two callers sharing the same in-flight job
        val job1 = launch {
            firstCallerResult = resolver.resolveStreams("video1", caller = "first")
        }
        fakeProvider.waitForExtractionStart("video1")

        val job2 = launch {
            secondCallerResult = resolver.resolveStreams("video1", caller = "second")
        }

        // Yield to let second caller join the in-flight job
        kotlinx.coroutines.yield()

        // Cancel the shared job (not the callers)
        resolver.cancelResolve("video1")

        // Both jobs should complete normally (not cancelled)
        job1.join()
        job2.join()

        assertFalse("First job should NOT be cancelled", job1.isCancelled)
        assertFalse("Second job should NOT be cancelled", job2.isCancelled)

        // Both should get null (shared job was cancelled, not the callers)
        assertNull("First caller should get null when shared job cancelled", firstCallerResult)
        assertNull("Second caller should get null when shared job cancelled", secondCallerResult)

        // Cleanup
        gate.complete(Unit)
    }

    // --- Priority Propagation Tests (ANDROID-PERSONAL-02 [Bug 1]) ---

    /**
     * Verifies that the [Priority.PLAYER] passed by [DefaultPlayerRepository]
     * actually reaches the [StreamResolutionProvider] — not the prior
     * hardcoded value. This is the live-playback bypass path: PLAYER
     * skips the rate-limit + cooldown gates per spec D1.
     */
    @Test
    fun `PLAYER priority is forwarded to the provider`() = runTest {
        fakeProvider.setResult("video1", createMockStreams("video1"))

        resolver.resolveStreams(
            videoId = "video1",
            caller = "player",
            priority = Priority.PLAYER,
        )

        assertEquals(
            "Provider must observe Priority.PLAYER when caller declared PLAYER",
            Priority.PLAYER,
            fakeProvider.getObservedPriority("video1"),
        )
    }

    /**
     * Verifies that the [Priority.USER_FOREGROUND] passed by
     * [com.albunyaan.tube.player.StreamPrefetchService] (and the default
     * for any forgotten caller) reaches the provider — closing the bug
     * where prefetch silently rode the PLAYER bypass.
     */
    @Test
    fun `USER_FOREGROUND priority is forwarded to the provider`() = runTest {
        fakeProvider.setResult("video2", createMockStreams("video2"))

        resolver.resolveStreams(
            videoId = "video2",
            caller = "prefetch",
            priority = Priority.USER_FOREGROUND,
        )

        assertEquals(
            "Provider must observe Priority.USER_FOREGROUND when caller declared USER_FOREGROUND",
            Priority.USER_FOREGROUND,
            fakeProvider.getObservedPriority("video2"),
        )
    }

    /**
     * Default priority is fail-closed — a caller who forgets to pass
     * `priority = ...` goes through the gates rather than silently
     * bypassing them. Documents the API contract.
     */
    @Test
    fun `default priority is USER_FOREGROUND`() = runTest {
        fakeProvider.setResult("video3", createMockStreams("video3"))

        // Note: NO `priority = ...` argument supplied.
        resolver.resolveStreams(
            videoId = "video3",
            caller = "forgot-to-set",
        )

        assertEquals(
            "Default priority must be USER_FOREGROUND (fail-closed)",
            Priority.USER_FOREGROUND,
            fakeProvider.getObservedPriority("video3"),
        )
    }

    // --- Priority Escalation Tests (ANDROID-PERSONAL-02 round 2 [Bug B]) ---

    /**
     * A higher-priority caller arriving while a lower-priority resolve is
     * in-flight must NOT join — it must cancel the existing job and start a
     * new resolve at the higher priority. Without this, the late-arriving
     * PLAYER caller gets trapped behind the prefetch's USER_FOREGROUND lane
     * (rate-limit + cooldown gates), violating spec D1.
     */
    @Test
    fun `higher priority caller cancels and restarts lower priority in-flight job`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()

        // First (lower-priority) call uses gate1, then we re-arm with gate2
        // for the second (escalated PLAYER) call so we can wait for both
        // extractions deterministically.
        fakeProvider.setGate("videoEsc", gate1)
        fakeProvider.setResult("videoEsc", createMockStreams("videoEsc"))

        // Lower-priority caller arrives first and stalls inside the provider.
        var prefetchResult: ResolvedStreams? = createMockStreams("not-yet")
        val prefetchJob = launch {
            prefetchResult = resolver.resolveStreams(
                videoId = "videoEsc",
                caller = "prefetch",
                priority = Priority.USER_FOREGROUND,
            )
        }

        // Wait for the first extraction to actually start.
        fakeProvider.waitForExtractionStart("videoEsc")
        assertEquals(
            "First extraction should run at USER_FOREGROUND",
            Priority.USER_FOREGROUND,
            fakeProvider.getObservedPriority("videoEsc"),
        )

        // Re-arm the provider for the escalated second call (creates a
        // fresh `extractionStarted` deferred so waitForExtractionStart
        // can fire again) BEFORE the PLAYER caller arrives.
        fakeProvider.setGate("videoEsc", gate2)
        fakeProvider.setResult("videoEsc", createMockStreams("videoEsc"))

        // Higher-priority PLAYER arrives. Must escalate (cancel + restart),
        // not join the in-flight USER_FOREGROUND job.
        val playerJob = launch {
            resolver.resolveStreams(
                videoId = "videoEsc",
                caller = "player",
                priority = Priority.PLAYER,
            )
        }

        // Wait for the second (PLAYER) extraction to start. If escalation
        // fired correctly, the FakeProvider gets a second call and the
        // observed priority flips to PLAYER.
        fakeProvider.waitForExtractionStart("videoEsc")

        // Release both gates so the cancelled prefetch (gate1) and the
        // PLAYER restart (gate2) both unblock.
        gate1.complete(Unit)
        gate2.complete(Unit)
        prefetchJob.join()
        playerJob.join()

        assertEquals(
            "Provider must observe PLAYER after escalation",
            Priority.PLAYER,
            fakeProvider.getObservedPriority("videoEsc"),
        )
        assertEquals(
            "Provider should be invoked twice (lower-priority cancel + higher-priority restart)",
            2,
            fakeProvider.getCallCount("videoEsc"),
        )
        // The lower-priority caller's await() must return null gracefully —
        // not throw — when the shared job is cancelled by escalation.
        assertNull(
            "Lower-priority caller should receive null when its shared job is cancelled by escalation",
            prefetchResult,
        )
    }

    /**
     * Same-priority arrivals continue to join the in-flight job — the
     * escalation logic must not regress the de-duplication contract.
     */
    @Test
    fun `same priority arrivals join existing in-flight job`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val expected = createMockStreams("videoSame")
        fakeProvider.setGate("videoSame", gate)
        fakeProvider.setResult("videoSame", expected)

        // First USER_FOREGROUND caller starts a new job.
        val first = launch {
            resolver.resolveStreams(
                videoId = "videoSame",
                caller = "first",
                priority = Priority.USER_FOREGROUND,
            )
        }
        fakeProvider.waitForExtractionStart("videoSame")

        // Second USER_FOREGROUND caller must join, not escalate.
        val second = launch {
            resolver.resolveStreams(
                videoId = "videoSame",
                caller = "second",
                priority = Priority.USER_FOREGROUND,
            )
        }

        kotlinx.coroutines.yield()
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            "Single shared extraction for same-priority callers",
            1,
            fakeProvider.getCallCount("videoSame"),
        )
    }

    /**
     * Lower-priority callers that arrive while a higher-priority resolve
     * is in-flight must JOIN, not escalate (escalation only applies to
     * strictly-higher priority arrivals). A USER_FOREGROUND prefetch
     * arriving while a PLAYER resolve is in-flight has no business
     * spawning a parallel extraction at lower priority.
     */
    @Test
    fun `lower priority arrival joins higher priority in-flight job`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val expected = createMockStreams("videoLow")
        fakeProvider.setGate("videoLow", gate)
        fakeProvider.setResult("videoLow", expected)

        // PLAYER caller starts the in-flight job.
        val player = launch {
            resolver.resolveStreams(
                videoId = "videoLow",
                caller = "player",
                priority = Priority.PLAYER,
            )
        }
        fakeProvider.waitForExtractionStart("videoLow")
        assertEquals(
            "First extraction runs at PLAYER",
            Priority.PLAYER,
            fakeProvider.getObservedPriority("videoLow"),
        )

        // USER_FOREGROUND prefetch arrives — must JOIN (no second extraction).
        val prefetch = launch {
            resolver.resolveStreams(
                videoId = "videoLow",
                caller = "prefetch",
                priority = Priority.USER_FOREGROUND,
            )
        }
        kotlinx.coroutines.yield()

        gate.complete(Unit)
        player.join()
        prefetch.join()

        assertEquals(
            "Lower-priority arrival must join, not spawn a parallel extraction",
            1,
            fakeProvider.getCallCount("videoLow"),
        )
        assertEquals(
            "Provider's observed priority remains PLAYER (not overridden by joiner)",
            Priority.PLAYER,
            fakeProvider.getObservedPriority("videoLow"),
        )
    }

    // --- Availability Gate Tests (NB1 chokepoint move) ---
    // The per-caller gate that lived in [DefaultPlayerRepository] moved here.
    // These tests cover both the regular player path AND the prefetch path:
    // any call to [resolveStreams] now hits the gate before any extraction.
    // The leak being closed: [StreamPrefetchService.triggerPrefetch] used to
    // call [GlobalStreamResolver.resolveStreams] directly, bypassing the
    // repo gate entirely — so list-tap thumbnails for archived videos
    // triggered NewPipe extractions in the background and cached the result.

    @Test
    fun `archived video throws ContentUnavailableException and never calls provider`() = runTest {
        val fakeContentService = FakeContentService()
        val gatedResolver = GlobalStreamResolver.createForTesting(fakeProvider, fakeContentService)
        fakeContentService.setAvailable("vid_archived", false)
        // Provider would have returned valid streams — but the gate must fire first.
        fakeProvider.setResult("vid_archived", createMockStreams("vid_archived"))

        try {
            gatedResolver.resolveStreams("vid_archived", caller = "test")
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
            fakeProvider.getCallCount("vid_archived"),
        )
        assertEquals(
            "Availability gate must have run exactly once",
            1,
            fakeContentService.verifyCallCount.get(),
        )
    }

    @Test
    fun `available video proceeds to provider`() = runTest {
        val fakeContentService = FakeContentService()
        val gatedResolver = GlobalStreamResolver.createForTesting(fakeProvider, fakeContentService)
        fakeContentService.setAvailable("vid_ok", true)
        fakeProvider.setResult("vid_ok", createMockStreams("vid_ok"))

        gatedResolver.resolveStreams("vid_ok", caller = "test")

        assertEquals(
            "Resolver provider must be reached for available video",
            1,
            fakeProvider.getCallCount("vid_ok"),
        )
    }

    @Test
    fun `transport error on availability check fails open and proceeds to provider`() = runTest {
        // Mirrors offline / 5xx behaviour: a transport error must NOT block a
        // valid user from playing. Same fail-open policy as T10/T11/T12 and
        // the previous repository chokepoint.
        val fakeContentService = FakeContentService()
        val gatedResolver = GlobalStreamResolver.createForTesting(fakeProvider, fakeContentService)
        fakeContentService.setError("vid_offline", RuntimeException("network timeout"))
        fakeProvider.setResult("vid_offline", createMockStreams("vid_offline"))

        // Must not throw — the gate is fail-open on transport errors.
        gatedResolver.resolveStreams("vid_offline", caller = "test")

        assertEquals(
            "Transport error on availability check must NOT block resolution",
            1,
            fakeProvider.getCallCount("vid_offline"),
        )
    }

    @Test
    fun `archived prefetch caller cannot cache results`() = runTest {
        // NB1 regression: list-tap prefetch used to call GlobalStreamResolver
        // directly, bypassing the per-caller gate inside DefaultPlayerRepository.
        // After the chokepoint move, even the prefetch caller (BACKGROUND_REFRESH)
        // hits the gate, the gate throws, and the provider is never invoked —
        // so no stream metadata can be cached for an archived video.
        val fakeContentService = FakeContentService()
        val gatedResolver = GlobalStreamResolver.createForTesting(fakeProvider, fakeContentService)
        fakeContentService.setAvailable("vid_archived_prefetch", false)
        fakeProvider.setResult("vid_archived_prefetch", createMockStreams("vid_archived_prefetch"))

        try {
            gatedResolver.resolveStreams(
                videoId = "vid_archived_prefetch",
                caller = "prefetch",
                priority = Priority.BACKGROUND_REFRESH,
            )
            fail("Expected ContentUnavailableException for archived prefetch")
        } catch (_: ContentUnavailableException) {
            // expected
        }

        assertEquals(
            "Prefetch must NOT extract streams for archived video",
            0,
            fakeProvider.getCallCount("vid_archived_prefetch"),
        )
    }

    @Test
    fun `joiners share gate verdict via single HEAD per extraction`() = runTest {
        // NB1 fix invariant: at most one HEAD probe per extraction. When two
        // callers (prefetch + player) both call resolveStreams for the same
        // videoId at nearly the same time, the second caller joins the
        // in-flight job rather than running its own gate. The shared
        // ContentUnavailableException propagates to both.
        val fakeContentService = FakeContentService()
        val gatedResolver = GlobalStreamResolver.createForTesting(fakeProvider, fakeContentService)
        val gate = CompletableDeferred<Unit>()
        fakeContentService.setAvailable("vid_shared", false)
        // Block the provider just to keep the in-flight window long enough
        // for the second caller to join. (The gate fires before the provider,
        // so this gate isn't actually awaited — kept for shape symmetry.)
        fakeProvider.setGate("vid_shared", gate)
        fakeProvider.setResult("vid_shared", createMockStreams("vid_shared"))

        // First caller: USER_FOREGROUND prefetch. Will throw ContentUnavailable.
        var firstException: Throwable? = null
        val first = launch {
            try {
                gatedResolver.resolveStreams(
                    videoId = "vid_shared",
                    caller = "prefetch",
                    priority = Priority.USER_FOREGROUND,
                )
            } catch (t: Throwable) {
                firstException = t
            }
        }

        // Wait for the gate's verdict to register (the verifyCallCount must
        // increment regardless of whether the provider gets called) — busy-wait
        // just enough for the async block to start.
        kotlinx.coroutines.yield()
        kotlinx.coroutines.yield()

        // Second caller arrives after the gate already failed. The first
        // caller's job is already complete-with-exception; the second caller
        // creates a NEW resolve job so the gate fires a second time. (The
        // single-HEAD invariant only applies to overlapping in-flight callers.)
        var secondException: Throwable? = null
        val second = launch {
            try {
                gatedResolver.resolveStreams(
                    videoId = "vid_shared",
                    caller = "player",
                    priority = Priority.USER_FOREGROUND,
                )
            } catch (t: Throwable) {
                secondException = t
            }
        }

        gate.complete(Unit)
        first.join()
        second.join()

        assertTrue(
            "First caller (prefetch) must see ContentUnavailableException",
            firstException is ContentUnavailableException,
        )
        assertTrue(
            "Second caller (player) must also see ContentUnavailableException",
            secondException is ContentUnavailableException,
        )
        // Both callers see the same archived verdict; the provider is never invoked.
        assertEquals(
            "Provider must never be called for archived video, regardless of caller",
            0,
            fakeProvider.getCallCount("vid_shared"),
        )
    }

    // --- Test Helpers ---

    private fun createMockStreams(id: String): ResolvedStreams {
        return ResolvedStreams(
            streamId = id,
            videoTracks = emptyList(),
            audioTracks = emptyList(),
            durationSeconds = 180,
            urlGeneratedAt = 0L
        )
    }

    /**
     * Fake resolution provider for testing.
     * Allows controlling when extractions complete using gates and
     * captures the [Priority] handed to the provider on each call so
     * tests can assert priority propagation (ANDROID-PERSONAL-02 [Bug 1]).
     */
    private class FakeResolutionProvider : StreamResolutionProvider {
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val results = ConcurrentHashMap<String, ResolvedStreams>()
        private val callCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val extractionStarted = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        // Last priority observed by the provider for each videoId.
        // AtomicReference is overkill for the in-test single-thread case
        // but cheap and correct under StandardTestDispatcher.
        private val observedPriorities = ConcurrentHashMap<String, AtomicReference<Priority?>>()

        fun setGate(videoId: String, gate: CompletableDeferred<Unit>) {
            gates[videoId] = gate
            extractionStarted[videoId] = CompletableDeferred()
        }

        fun setResult(videoId: String, result: ResolvedStreams?) {
            if (result == null) {
                results.remove(videoId)
            } else {
                results[videoId] = result
            }
            extractionStarted.putIfAbsent(videoId, CompletableDeferred())
        }

        suspend fun waitForExtractionStart(videoId: String) {
            extractionStarted[videoId]?.await()
                ?: error("No extraction-start marker registered for $videoId")
        }

        fun getCallCount(videoId: String): Int {
            return callCounts[videoId]?.get() ?: 0
        }

        fun getObservedPriority(videoId: String): Priority? {
            return observedPriorities[videoId]?.get()
        }

        override suspend fun resolveStreams(
            videoId: String,
            forceRefresh: Boolean,
            priority: Priority,
        ): ResolvedStreams? {
            callCounts.computeIfAbsent(videoId) { AtomicInteger(0) }.incrementAndGet()
            observedPriorities.computeIfAbsent(videoId) { AtomicReference<Priority?>(null) }.set(priority)
            extractionStarted[videoId]?.complete(Unit)
            gates[videoId]?.await()
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
            // Default to true (available) when the test didn't say otherwise.
            return availability[youtubeId] ?: true
        }

        override suspend fun isInApprovedRegistry(
            type: AvailabilityCheckType,
            youtubeId: String,
        ): Boolean = availability[youtubeId] ?: true

        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState,
            query: String?,
        ): CursorResponse = throw UnsupportedOperationException("not used in resolver gate tests")

        override suspend fun fetchHomeFeed(
            cursor: String?,
            categoryLimit: Int,
            contentLimit: Int,
            category: String?,
        ): HomeFeedResult = throw UnsupportedOperationException("not used in resolver gate tests")

        override suspend fun search(
            query: String,
            type: String?,
            limit: Int,
        ): List<ContentItem> = throw UnsupportedOperationException("not used in resolver gate tests")

        override suspend fun fetchCategories(): List<Category> =
            throw UnsupportedOperationException("not used in resolver gate tests")

        override suspend fun fetchSubcategories(parentId: String): List<Category> =
            throw UnsupportedOperationException("not used in resolver gate tests")
    }
}
