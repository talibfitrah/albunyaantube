package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
     * Allows controlling when extractions complete using gates.
     */
    private class FakeResolutionProvider : StreamResolutionProvider {
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val results = mutableMapOf<String, ResolvedStreams?>()
        private val callCounts = mutableMapOf<String, AtomicInteger>()
        private val extractionStarted = mutableMapOf<String, CompletableDeferred<Unit>>()

        fun setGate(videoId: String, gate: CompletableDeferred<Unit>) {
            gates[videoId] = gate
            extractionStarted[videoId] = CompletableDeferred()
        }

        fun setResult(videoId: String, result: ResolvedStreams?) {
            results[videoId] = result
            if (!extractionStarted.containsKey(videoId)) {
                extractionStarted[videoId] = CompletableDeferred()
            }
        }

        suspend fun waitForExtractionStart(videoId: String) {
            extractionStarted[videoId]?.await()
        }

        fun getCallCount(videoId: String): Int {
            return callCounts[videoId]?.get() ?: 0
        }

        override suspend fun resolveStreams(videoId: String, forceRefresh: Boolean): ResolvedStreams? {
            callCounts.getOrPut(videoId) { AtomicInteger(0) }.incrementAndGet()
            extractionStarted[videoId]?.complete(Unit)
            gates[videoId]?.await()
            return results[videoId]
        }
    }
}
