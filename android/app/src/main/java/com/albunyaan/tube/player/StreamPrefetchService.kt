package com.albunyaan.tube.player

import android.util.Log
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for stream prefetching to enable testing.
 */
interface StreamPrefetchService {
    fun triggerPrefetch(videoId: String, scope: CoroutineScope)
    suspend fun awaitOrConsumePrefetch(videoId: String): ResolvedStreams?
    fun consumePrefetch(videoId: String): ResolvedStreams?
    fun isPrefetchInFlight(videoId: String): Boolean
    fun cancelPrefetch(videoId: String)
    fun clearAll()
}

/**
 * Default implementation of StreamPrefetchService.
 *
 * This optimizes perceived load time by starting stream resolution when the user
 * taps a video item, rather than waiting until the player screen is fully loaded.
 *
 * Flow:
 * 1. User taps video in list → triggerPrefetch(videoId) called
 * 2. Background coroutine starts resolving streams from YouTube
 * 3. Navigation to PlayerFragment begins immediately (no blocking)
 * 4. PlayerFragment's ViewModel calls awaitOrConsumePrefetch(videoId) with short timeout
 * 5. If prefetch completed/completes within timeout, cached data is used; otherwise normal resolution
 *
 * This hides ~2-5 seconds of extraction latency behind the navigation animation
 * and fragment initialization time.
 *
 * Thread Safety:
 * - Uses internal CoroutineScope with SupervisorJob to survive fragment destruction
 * - ConcurrentHashMap for thread-safe access to in-flight jobs and results
 * - Deferred pattern allows both immediate consumption AND awaiting in-flight jobs
 */
@Singleton
class DefaultStreamPrefetchService @Inject constructor(
    private val extractorClient: NewPipeExtractorClient,
    private val rateLimiter: ExtractionRateLimiter
) : StreamPrefetchService {
    companion object {
        private const val TAG = "StreamPrefetch"
        private const val PREFETCH_TIMEOUT_MS = 8000L // Max wait for prefetch extraction
        private const val AWAIT_TIMEOUT_MS = 3000L // Max wait when player wants result
        private const val MAX_CACHED_RESULTS = 5 // Limit memory usage
    }

    // Internal scope that survives fragment destruction - prefetch shouldn't be cancelled on navigation
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-flight prefetch jobs by video ID - Deferred allows awaiting
    private val prefetchJobs = ConcurrentHashMap<String, Deferred<ResolvedStreams?>>()

    // Results from completed prefetches (short-lived cache - consumed once)
    private val prefetchResults = ConcurrentHashMap<String, ResolvedStreams>()

    // Tracks insertion order for FIFO eviction (ConcurrentHashMap doesn't maintain order)
    private val insertionOrder = ConcurrentLinkedQueue<String>()

    /**
     * Trigger prefetch for a video ID. Call this when the user taps a video item,
     * before starting navigation.
     *
     * This method returns immediately - prefetch runs in the background using an
     * internal scope that survives fragment destruction.
     *
     * Note: The caller's scope parameter is ignored - we use our own internal scope
     * to ensure prefetch completes even if the calling fragment is destroyed during navigation.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun triggerPrefetch(videoId: String, scope: CoroutineScope) {
        // Don't start duplicate prefetches - check both in-flight and completed
        if (prefetchJobs.containsKey(videoId) || prefetchResults.containsKey(videoId)) {
            Log.d(TAG, "Prefetch already in progress or completed for $videoId")
            return
        }

        // Check rate limiter - use PREFETCH priority (lowest, can be skipped)
        val result = rateLimiter.acquire(videoId, ExtractionRateLimiter.RequestKind.PREFETCH)
        if (result !is ExtractionRateLimiter.RateLimitResult.Allowed) {
            Log.d(TAG, "Prefetch rate-limited for $videoId: $result")
            return
        }

        Log.d(TAG, "Starting prefetch for $videoId")
        val deferred = serviceScope.async {
            try {
                val resolved = withTimeoutOrNull(PREFETCH_TIMEOUT_MS) {
                    extractorClient.resolveStreams(videoId, forceRefresh = false)
                }
                if (resolved != null) {
                    // Evict oldest results if cache is full (FIFO order)
                    while (prefetchResults.size >= MAX_CACHED_RESULTS) {
                        val oldest = insertionOrder.poll()
                        oldest?.let { prefetchResults.remove(it) }
                    }
                    prefetchResults[videoId] = resolved
                    insertionOrder.offer(videoId)
                    rateLimiter.onExtractionSuccess(videoId)
                    Log.d(TAG, "Prefetch completed for $videoId")
                } else {
                    Log.w(TAG, "Prefetch returned null for $videoId")
                }
                resolved
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "Prefetch failed for $videoId: ${e.message}")
                }
                null
            } finally {
                prefetchJobs.remove(videoId)
            }
        }
        prefetchJobs[videoId] = deferred
    }

    /**
     * Try to get prefetched result, waiting briefly for in-flight prefetch if needed.
     *
     * This is the primary method for PlayerViewModel to use. It:
     * 1. First checks if result is already cached (instant)
     * 2. If prefetch is in-flight, waits up to AWAIT_TIMEOUT_MS for it to complete
     * 3. Returns null if no prefetch or timeout exceeded
     *
     * @return The prefetched ResolvedStreams if available, null otherwise.
     *         Consumes the result (removes from cache).
     */
    override suspend fun awaitOrConsumePrefetch(videoId: String): ResolvedStreams? {
        // First check if result is already ready (fastest path)
        prefetchResults.remove(videoId)?.let { result ->
            insertionOrder.remove(videoId) // Clean up order tracking
            Log.d(TAG, "Prefetch consumed (cached) for $videoId")
            return result
        }

        // Check if prefetch is in-flight - if so, wait briefly
        val inFlight = prefetchJobs[videoId]
        if (inFlight != null) {
            Log.d(TAG, "Awaiting in-flight prefetch for $videoId")
            val result = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
                inFlight.await()
            }
            // Result may have been stored in prefetchResults by the job, consume it
            prefetchResults.remove(videoId)?.let { cached ->
                insertionOrder.remove(videoId) // Clean up order tracking
                Log.d(TAG, "Prefetch consumed (awaited) for $videoId")
                return cached
            }
            // Or use the direct result if available
            if (result != null) {
                Log.d(TAG, "Prefetch consumed (direct await) for $videoId")
                return result
            }
            Log.d(TAG, "Prefetch await timed out for $videoId")
        }

        return null
    }

    /**
     * Try to get prefetched result immediately (non-blocking).
     * Call this from PlayerViewModel before starting normal resolution.
     *
     * @return The prefetched ResolvedStreams if already available and ready, null otherwise.
     *         Consumes the result (removes from cache).
     */
    override fun consumePrefetch(videoId: String): ResolvedStreams? {
        return prefetchResults.remove(videoId)?.also {
            insertionOrder.remove(videoId) // Clean up order tracking
            Log.d(TAG, "Prefetch consumed for $videoId")
        }
    }

    /**
     * Check if a prefetch is currently in-flight for the given video ID.
     * Used to avoid starting duplicate extractions in PlayerViewModel.
     */
    override fun isPrefetchInFlight(videoId: String): Boolean {
        return prefetchJobs.containsKey(videoId)
    }

    /**
     * Cancel any in-flight prefetch for a video ID.
     * Call this if the user navigates away before prefetch completes.
     */
    override fun cancelPrefetch(videoId: String) {
        prefetchJobs.remove(videoId)?.cancel()
        prefetchResults.remove(videoId)
        insertionOrder.remove(videoId) // Clean up order tracking
    }

    /**
     * Clear all prefetches. Call on app background or memory pressure.
     */
    override fun clearAll() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        prefetchResults.clear()
        insertionOrder.clear()
    }
}
