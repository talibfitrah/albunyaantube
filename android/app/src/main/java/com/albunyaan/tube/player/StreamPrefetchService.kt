package com.albunyaan.tube.player

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.data.extractor.Priority
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
    private val featureFlags: PlaybackFeatureFlags,
    private val segmentPreBuffer: SegmentPreBuffer,
) : StreamPrefetchService {

    /**
     * Time source for cache TTL bookkeeping. Defaults to [System.currentTimeMillis].
     *
     * Var-with-internal-setter rather than a constructor param so Hilt's
     * @Inject ctor stays simple. Tests replace this via [setClockForTesting]
     * to drive the TTL clock without depending on `runTest`'s virtual time
     * (which advances scheduler time, not wall-clock). Wall-clock is the
     * right reference here because the TTL bounds an **archive-bypass**
     * window — the relevant elapsed time is the gap between the prefetch
     * resolve and the user's tap, both real-time events regardless of the
     * coroutine dispatcher.
     */
    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Test seam: replace the wall-clock used for TTL bookkeeping. Production
     * callers MUST NOT use this — Hilt wires the production singleton with
     * [System.currentTimeMillis].
     */
    @VisibleForTesting
    internal fun setClockForTesting(newClock: () -> Long) {
        clock = newClock
    }

    /**
     * Test seam: write directly into the prefetch cache, skipping the
     * background launch in [triggerPrefetch]. Production callers MUST NOT
     * use this — it bypasses rate limiting and the resolver. Tests use this
     * to keep TTL coverage hermetic without trying to drive
     * [Dispatchers.IO] from a `runTest` virtual scheduler.
     */
    @VisibleForTesting
    internal fun primeCacheForTesting(videoId: String, streams: ResolvedStreams, cachedAtMs: Long) {
        prefetchResults[videoId] = CachedResult(streams, cachedAtMs)
        insertionOrder.offer(videoId)
    }

    companion object {
        private const val TAG = "StreamPrefetch"
        private const val PREFETCH_TIMEOUT_MS = 8000L // Max wait for prefetch extraction
        private const val AWAIT_TIMEOUT_MS = 3000L // Max wait when player wants result
        private const val MAX_CACHED_RESULTS = 5 // Limit memory usage

        /**
         * TTL on cached prefetch results.
         *
         * Bounds the archive-bypass window: if a video is archived AFTER prefetch
         * resolved its streams, the cached entry is held for at most this long
         * before consumption forces a re-resolve through [GlobalStreamResolver],
         * which runs the backend availability gate again. 30s is plenty for the
         * common case (user taps within a few seconds of the prefetch trigger)
         * while keeping the bypass window short.
         */
        @VisibleForTesting
        internal const val PREFETCH_RESULT_TTL_MS = 30_000L
    }

    /**
     * Cached prefetch result with the wall-clock timestamp at which it was
     * stored. The timestamp drives the TTL eviction inside the consumer paths
     * (see [awaitOrConsumePrefetch] and [consumePrefetch]).
     */
    private data class CachedResult(val streams: ResolvedStreams, val cachedAtMs: Long)

    // Internal scope that survives fragment destruction - prefetch shouldn't be cancelled on navigation
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track which videoIds have prefetch triggered (for isPrefetchInFlight check)
    private val prefetchingVideoIds = ConcurrentHashMap.newKeySet<String>()

    // Results from completed prefetches (short-lived cache - consumed once or expires after TTL)
    private val prefetchResults = ConcurrentHashMap<String, CachedResult>()

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
                // ANDROID-PERSONAL-02 [Bug 1]: prefetch is NOT real-time playback
                // — it must respect the rate-limit + cooldown gates so a tripped
                // cooldown actually halts background work (spec D1).
                // BACKGROUND_REFRESH priority ensures the rate limiter reserves
                // 5 tokens for foreground (channel/Me) so prefetch never starves
                // user-facing loads. Use full PREFETCH_TIMEOUT_MS so prefetch can
                // actually warm the cache when budget is available.
                val resolved = globalResolver.resolveStreams(
                    videoId = videoId,
                    forceRefresh = false,
                    timeoutMs = PREFETCH_TIMEOUT_MS,
                    caller = "prefetch",
                    priority = Priority.BACKGROUND_REFRESH,
                )
                if (resolved != null) {
                    // Evict oldest results if cache is full (FIFO order)
                    while (prefetchResults.size >= MAX_CACHED_RESULTS) {
                        val oldest = insertionOrder.poll()
                        oldest?.let { prefetchResults.remove(it) }
                    }
                    // N8 fix: stamp the cache entry with wall-clock time so the
                    // consumer can enforce PREFETCH_RESULT_TTL_MS and force a
                    // re-resolve through the gate after the TTL expires.
                    prefetchResults[videoId] = CachedResult(resolved, clock())
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
        // First check if result is already ready (fastest path).
        // N8 fix: validate TTL before returning. If the entry is stale, drop it
        // and return null so the caller falls through to a fresh resolve via
        // [globalResolver] — which runs the backend availability gate again.
        consumeFreshCachedResult(videoId, "cached")?.let { return it }

        // Phase 1A: Check if prefetch triggered a resolve that's still in-flight
        // Using globalResolver means player will join the same job
        if (prefetchingVideoIds.contains(videoId) || globalResolver.isResolveInFlight(videoId)) {
            Log.d(TAG, "Awaiting in-flight prefetch via GlobalResolver for $videoId")
            val result = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
                // Join the in-flight job via global resolver
                // ANDROID-PERSONAL-02 [Bug 1]: prefetch await path must declare
                // its own priority. ANDROID-PERSONAL-02 round 3 [Bug F]:
                // this method is called from the PLAYER path
                // (PlayerViewModel.resolveWithRetry awaits the in-flight
                // prefetch BEFORE falling back to PlayerRepository), so we
                // declare PLAYER priority. Without this, a stalled
                // USER_FOREGROUND prefetch would hold playback for
                // AWAIT_TIMEOUT_MS before the player's own resolve trips
                // Bug-B priority escalation. PLAYER here lets the existing
                // prefetch be cancelled-and-replaced with a PLAYER-priority
                // resolve immediately on join.
                globalResolver.resolveStreams(
                    videoId = videoId,
                    forceRefresh = false,
                    timeoutMs = AWAIT_TIMEOUT_MS,
                    caller = "prefetch_await",
                    priority = Priority.PLAYER,
                )
            }
            // Result may have been stored in prefetchResults by the job, consume it
            // (TTL-checked: entries that landed before the await but expired during
            // the await are dropped here, same as the fast-path check above).
            consumeFreshCachedResult(videoId, "awaited")?.let { return it }
            // Or use the direct result if available — `result` is the live return
            // from globalResolver, not from our cache, so no TTL check needed.
            if (result != null) {
                Log.d(TAG, "Prefetch consumed (direct await) for $videoId")
                return result
            }
            Log.d(TAG, "Prefetch await timed out for $videoId")
        }

        return null
    }

    /**
     * Consume the cached result for [videoId] iff it exists AND is younger than
     * [PREFETCH_RESULT_TTL_MS]. Stale entries are evicted (and the FIFO order
     * tracker is kept consistent). Returns null in both the absent and expired
     * cases so the caller falls through to a fresh resolve through the gate.
     *
     * The [stage] parameter only feeds the success log line — it lets the
     * cached/awaited paths share this helper without losing diagnostic context.
     */
    private fun consumeFreshCachedResult(videoId: String, stage: String): ResolvedStreams? {
        val cached = prefetchResults[videoId] ?: return null
        val ageMs = clock() - cached.cachedAtMs
        if (ageMs > PREFETCH_RESULT_TTL_MS) {
            // Drop stale entry. Don't return it — the caller must re-resolve so
            // [GlobalStreamResolver]'s availability gate runs again. This bounds
            // the archive-bypass window for N8.
            prefetchResults.remove(videoId)
            insertionOrder.remove(videoId)
            Log.d(TAG, "Prefetch cache expired for $videoId (age=${ageMs}ms); will re-resolve")
            return null
        }
        // Fresh enough — consume (remove-once semantics matches pre-TTL behaviour).
        prefetchResults.remove(videoId)
        insertionOrder.remove(videoId)
        Log.d(TAG, "Prefetch consumed ($stage) for $videoId (age=${ageMs}ms)")
        return cached.streams
    }

    /**
     * Try to get prefetched result immediately (non-blocking).
     * Call this from PlayerViewModel before starting normal resolution.
     *
     * @return The prefetched ResolvedStreams if already available and ready, null otherwise.
     *         Consumes the result (removes from cache).
     *
     * N8 fix: TTL-checked. Stale entries return null and are evicted so the
     * caller re-resolves through the gate.
     */
    override fun consumePrefetch(videoId: String): ResolvedStreams? {
        return consumeFreshCachedResult(videoId, "sync")
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
    private suspend fun tryPreGenerateMpd(videoId: String, resolved: ResolvedStreams) {
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
                    if (featureFlags.isSegmentPreloadEnabled) {
                        val lowestTrackUrl = mpdResult.videoTracks
                            .minByOrNull { it.bitrate ?: Int.MAX_VALUE }
                            ?.url
                        if (!lowestTrackUrl.isNullOrBlank()) {
                            serviceScope.launch { segmentPreBuffer.preBuffer(lowestTrackUrl) }
                        }
                    }
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
