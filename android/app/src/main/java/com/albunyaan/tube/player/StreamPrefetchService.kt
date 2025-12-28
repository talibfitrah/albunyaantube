package com.albunyaan.tube.player

import android.util.Log
import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    /**
     * Clear prefetch-specific state only (does NOT cancel global resolver jobs).
     * Safe to call while playback is active.
     */
    fun clearPrefetchState()
    /**
     * Clear all state AND cancel global resolver jobs.
     * Only call when playback is stopped to avoid interrupting active resolutions.
     */
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
/**
 * Phase 1A: Updated to use GlobalStreamResolver for single-flight semantics.
 *
 * Prefetch now uses the global resolver, so if the player also calls resolve
 * for the same videoId, they share the same in-flight job - no duplicate extractions.
 *
 * Phase 5: Also pre-generates synthetic DASH MPD during prefetch when eligible.
 * This reduces first-frame latency by having the MPD ready when playback starts.
 */
@Singleton
class DefaultStreamPrefetchService @Inject constructor(
    private val globalResolver: GlobalStreamResolver,
    private val rateLimiter: ExtractionRateLimiter,
    private val mpdGenerator: MultiRepresentationMpdGenerator,
    private val mpdRegistry: SyntheticDashMpdRegistry,
    private val featureFlags: PlaybackFeatureFlags
) : StreamPrefetchService {
    companion object {
        private const val TAG = "StreamPrefetch"
        private const val PREFETCH_TIMEOUT_MS = 8000L // Max wait for prefetch extraction
        private const val AWAIT_TIMEOUT_MS = 3000L // Max wait when player wants result
        private const val MAX_CACHED_RESULTS = 5 // Limit memory usage
    }

    // Internal scope that survives fragment destruction - prefetch shouldn't be cancelled on navigation
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track which videoIds have prefetch triggered (for isPrefetchInFlight check)
    private val prefetchingVideoIds = ConcurrentHashMap.newKeySet<String>()

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
     * Phase 1A: Uses GlobalStreamResolver for single-flight semantics. If the player
     * also requests this videoId, they will join the same in-flight job.
     *
     * Note: The caller's scope parameter is ignored - we use our own internal scope
     * to ensure prefetch completes even if the calling fragment is destroyed during navigation.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun triggerPrefetch(videoId: String, scope: CoroutineScope) {
        // Don't start duplicate prefetches - check both tracking set and completed results
        if (prefetchingVideoIds.contains(videoId) || prefetchResults.containsKey(videoId)) {
            Log.d(TAG, "Prefetch already in progress or completed for $videoId")
            return
        }

        // Check rate limiter - use PREFETCH priority (lowest, can be skipped)
        val result = rateLimiter.acquire(videoId, ExtractionRateLimiter.RequestKind.PREFETCH)
        if (result !is ExtractionRateLimiter.RateLimitResult.Allowed) {
            Log.d(TAG, "Prefetch rate-limited for $videoId: $result")
            return
        }

        prefetchingVideoIds.add(videoId)
        Log.d(TAG, "Starting prefetch for $videoId")

        // Phase 1A: Use global resolver - player can join this same job
        serviceScope.launch {
            try {
                val resolved = globalResolver.resolveStreams(
                    videoId = videoId,
                    forceRefresh = false,
                    timeoutMs = PREFETCH_TIMEOUT_MS,
                    caller = "prefetch"
                )
                if (resolved != null) {
                    // Evict oldest results if cache is full (FIFO order)
                    while (prefetchResults.size >= MAX_CACHED_RESULTS) {
                        val oldest = insertionOrder.poll()
                        oldest?.let { prefetchResults.remove(it) }
                    }
                    prefetchResults[videoId] = resolved
                    insertionOrder.offer(videoId)
                    rateLimiter.onExtractionSuccess(videoId)

                    // Phase 5: Pre-generate synthetic DASH MPD if eligible
                    // This ensures the MPD is ready in the registry when playback starts
                    tryPreGenerateMpd(videoId, resolved)

                    Log.d(TAG, "Prefetch completed for $videoId")
                } else {
                    Log.w(TAG, "Prefetch returned null for $videoId")
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "Prefetch failed for $videoId: ${e.message}")
                }
            } finally {
                prefetchingVideoIds.remove(videoId)
            }
        }
    }

    /**
     * Try to get prefetched result, waiting briefly for in-flight prefetch if needed.
     *
     * This is the primary method for PlayerViewModel to use. It:
     * 1. First checks if result is already cached (instant)
     * 2. If prefetch is in-flight, waits up to AWAIT_TIMEOUT_MS for it to complete
     * 3. Returns null if no prefetch or timeout exceeded
     *
     * Phase 1A: Also checks GlobalStreamResolver for in-flight jobs that may have been
     * started by prefetch but not yet completed.
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

        // Phase 1A: Check if prefetch triggered a resolve that's still in-flight
        // Using globalResolver means player will join the same job
        if (prefetchingVideoIds.contains(videoId) || globalResolver.isResolveInFlight(videoId)) {
            Log.d(TAG, "Awaiting in-flight prefetch via GlobalResolver for $videoId")
            val result = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
                // Join the in-flight job via global resolver
                globalResolver.resolveStreams(
                    videoId = videoId,
                    forceRefresh = false,
                    timeoutMs = AWAIT_TIMEOUT_MS,
                    caller = "prefetch_await"
                )
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
     * Phase 1A: Also checks GlobalStreamResolver for in-flight jobs.
     */
    override fun isPrefetchInFlight(videoId: String): Boolean {
        return prefetchingVideoIds.contains(videoId) || globalResolver.isResolveInFlight(videoId)
    }

    /**
     * Cancel any in-flight prefetch for a video ID.
     * Call this if the user navigates away before prefetch completes.
     *
     * Note: Cancelling via GlobalStreamResolver may affect other callers waiting on the same job.
     */
    override fun cancelPrefetch(videoId: String) {
        prefetchingVideoIds.remove(videoId)
        prefetchResults.remove(videoId)
        insertionOrder.remove(videoId) // Clean up order tracking
        // Note: We don't cancel via globalResolver as other callers may be waiting
    }

    /**
     * Clear prefetch-specific state only.
     *
     * Safe to call at any time, including while playback is active. Does NOT cancel
     * in-flight global resolutions, so player streams continue uninterrupted.
     */
    override fun clearPrefetchState() {
        prefetchingVideoIds.clear()
        prefetchResults.clear()
        insertionOrder.clear()
        Log.d(TAG, "Cleared prefetch state (global resolver jobs not affected)")
    }

    /**
     * Clear all prefetches AND cancel global resolver jobs.
     *
     * **Important:** This method calls [GlobalStreamResolver.cancelAll] which cancels ALL
     * in-flight stream resolutions globally, not just those initiated by prefetch.
     * If the player is actively resolving streams when this is called, playback may be
     * interrupted. Only call this when the app is truly backgrounded and playback is stopped.
     *
     * To clear only prefetch-specific work without affecting player resolutions,
     * call [clearPrefetchState] instead.
     */
    override fun clearAll() {
        clearPrefetchState()
        globalResolver.cancelAll()
        Log.d(TAG, "Cleared all prefetch state and cancelled global resolver jobs")
    }

    /**
     * Phase 5: Try to pre-generate synthetic DASH MPD for eligible streams.
     *
     * Pre-generating the MPD during prefetch reduces first-frame latency by
     * having the manifest ready in the registry when playback starts.
     *
     * This is a best-effort optimization - failures are logged but do not affect
     * the prefetch result. The player will generate the MPD on-demand if needed.
     *
     * @param videoId The video ID (used as registry key)
     * @param resolved The resolved streams to generate MPD from
     */
    private fun tryPreGenerateMpd(videoId: String, resolved: ResolvedStreams) {
        // Phase 6: Runtime feature flag for MPD pre-generation
        if (!featureFlags.isMpdPrefetchEnabled) {
            Log.d(TAG, "MPD pre-gen disabled via feature flag")
            return
        }

        try {
            // Check eligibility first (fast operation)
            val (eligible, reason) = mpdGenerator.checkEligibility(resolved)
            if (!eligible) {
                Log.d(TAG, "MPD pre-gen skipped for $videoId: $reason")
                return
            }

            // Generate the MPD (no quality cap during prefetch - player will apply constraints)
            val mpdResult = mpdGenerator.generateMpd(resolved, qualityCapHeight = null)

            when (mpdResult) {
                is MultiRepresentationMpdGenerator.Result.Success -> {
                    // Phase 5: Register MPD WITH metadata in the registry.
                    // This enables true cache hits where createMediaSource() can skip regeneration.
                    mpdRegistry.registerWithMetadata(
                        videoId = videoId,
                        mpdXml = mpdResult.mpdXml,
                        videoTracks = mpdResult.videoTracks,
                        audioTrack = mpdResult.audioTrack,
                        codecFamily = mpdResult.codecFamily
                    )
                    Log.d(TAG, "MPD pre-generated for $videoId: ${mpdResult.videoTracks.size} reps (${mpdResult.codecFamily})")
                }
                is MultiRepresentationMpdGenerator.Result.Failure -> {
                    Log.d(TAG, "MPD pre-gen failed for $videoId: ${mpdResult.reason}")
                }
            }
        } catch (e: Exception) {
            // Non-fatal: player will generate MPD on-demand if needed
            Log.w(TAG, "MPD pre-gen exception for $videoId: ${e.message}")
        }
    }
}
