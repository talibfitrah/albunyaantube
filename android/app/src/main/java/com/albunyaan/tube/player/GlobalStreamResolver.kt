package com.albunyaan.tube.player

import android.util.Log
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NewPipePriorityContext
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Functional interface for stream resolution.
 * Used by GlobalStreamResolver to enable testing with fakes.
 *
 * The [priority] parameter declares which rate-limit / cooldown lane the
 * caller belongs to (spec §4.5 + D1). The provider is responsible for
 * setting [NewPipePriorityContext] before invoking NewPipe so the
 * downstream [com.albunyaan.tube.data.extractor.RateLimitedDownloader]
 * sees the correct lane.
 */
fun interface StreamResolutionProvider {
    suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean,
        priority: Priority,
    ): ResolvedStreams?
}

/**
 * Phase 1A: Global single-flight stream resolver.
 *
 * Ensures that prefetch and player share the same in-flight Deferred for any videoId.
 * When a video is being resolved, any subsequent request for the same videoId will
 * join the existing in-flight job instead of starting a duplicate extraction.
 *
 * This eliminates the race condition where:
 * 1. Prefetch starts (takes 4-6s due to slow network)
 * 2. Player times out waiting for prefetch (3s limit)
 * 3. Player starts a NEW extraction (duplicate work, doubles rate limit pressure)
 *
 * Now:
 * 1. Prefetch starts via resolveStreams()
 * 2. Player calls resolveStreams() - joins the SAME in-flight job
 * 3. Both get the result when extraction completes
 *
 * Visibility: All resolve attempts are logged with "joined in-flight resolve" vs "new resolve"
 * for debugging and metrics analysis.
 */
@Singleton
class GlobalStreamResolver private constructor(
    private val resolutionProvider: StreamResolutionProvider
) {
    companion object {
        private const val TAG = "GlobalStreamResolver"
        private const val DEFAULT_TIMEOUT_MS = 20_000L // 20s default timeout

        /**
         * Create a GlobalStreamResolver with a custom resolution provider for testing.
         * @param provider The stream resolution provider to use
         * @return A new GlobalStreamResolver instance
         */
        @JvmStatic
        fun createForTesting(provider: StreamResolutionProvider): GlobalStreamResolver {
            return GlobalStreamResolver(provider)
        }
    }

    /**
     * Primary constructor for production use with Hilt DI.
     */
    @Inject
    constructor(extractorClient: NewPipeExtractorClient) : this(
        // ANDROID-PERSONAL-02 [Bug 1]: respect the caller-supplied priority
        // instead of hardcoding [Priority.PLAYER]. Only the real-time
        // playback path (DefaultPlayerRepository) declares PLAYER and earns
        // the bypass — prefetch / await-prefetch declare USER_FOREGROUND so
        // they go through the rate-limit + cooldown gates (spec D1).
        //
        // The first-caller's priority sets the in-flight job's effective
        // priority via this provider lambda; subsequent callers join the
        // existing Deferred (de-duplication is by-design — see KDoc on
        // [resolveStreams]).
        //
        // Why the explicit [withContext(Dispatchers.IO)] BEFORE [with]:
        // [NewPipePriorityContext] uses a plain ThreadLocal, so it only
        // propagates across coroutine dispatcher hops if the priority is
        // already set on the thread the next stage runs on. By forcing the
        // IO dispatch here, we set the ThreadLocal on an IO thread; even
        // though [extractorClient.resolveStreams] has its own internal
        // `withContext(Dispatchers.IO)`, that hop becomes a no-op (already
        // IO) and the ThreadLocal stays put. Without this outer
        // `withContext`, a future caller on Dispatchers.Main / Default
        // would set the ThreadLocal on the wrong thread, the inner IO hop
        // would land on a fresh worker that has no ThreadLocal, and
        // [NewPipePriorityContext.currentOrDefault] would silently fall
        // back to USER_FOREGROUND — defeating the spec D1 player bypass
        // for the player path.
        StreamResolutionProvider { videoId, forceRefresh, priority ->
            withContext(Dispatchers.IO) {
                NewPipePriorityContext.with(priority) {
                    extractorClient.resolveStreams(videoId, forceRefresh)
                }
            }
        }
    )

    // Internal scope that survives fragment destruction
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-flight resolve jobs by videoId - any caller can join
    private val inFlightJobs = ConcurrentHashMap<String, Deferred<ResolvedStreams?>>()

    // Private lock for synchronizing job creation/cancellation (don't use 'this' as lock)
    private val lock = Any()

    // Test-only: listener for job cleanup events (for deterministic test synchronization)
    @Volatile
    private var onJobCleanup: ((videoId: String) -> Unit)? = null

    /**
     * Set a listener for job cleanup events (test-only).
     * Called when a job's invokeOnCompletion handler removes it from the map.
     * Use this in tests to wait for cleanup without relying on timing.
     *
     * WARNING: Always clear this listener after use to avoid cross-test leakage.
     * Recommended: use @After to call setOnJobCleanupListener(null).
     *
     * @param listener Callback receiving the videoId that was cleaned up, or null to clear
     */
    @VisibleForTesting
    fun setOnJobCleanupListener(listener: ((videoId: String) -> Unit)?) {
        onJobCleanup = listener
    }

    /**
     * Resolve streams for a videoId with single-flight semantics.
     *
     * If a resolve is already in-flight for this videoId and forceRefresh=false, joins that job.
     * If forceRefresh=true and a job exists, cancels it and starts a new one.
     * Otherwise, starts a new resolve and registers it for others to join.
     *
     * @param videoId The YouTube video ID to resolve
     * @param forceRefresh If true, cancels any in-flight job and forces fresh extraction
     * @param timeoutMs Maximum time to wait for resolution (default 20s)
     * @param caller A tag identifying who is calling (for logging: "prefetch", "player", etc.)
     * @param priority The rate-limit / cooldown lane this caller belongs to
     *   (spec §4.5 + D1). Defaults to [Priority.USER_FOREGROUND] which is the
     *   non-bypassing fail-closed choice — a forgotten caller goes through the
     *   gates rather than silently bypassing them. Only the live playback
     *   path supplies [Priority.PLAYER].
     *
     *   Note on the join semantics: when a new resolve is started this
     *   priority is what NewPipe sees on the IO thread. If a *second*
     *   caller joins an already-in-flight job (de-duplication), the second
     *   caller inherits the first caller's priority for that job — the
     *   first caller wins. That's by-design: starting a duplicate
     *   extraction just to apply a different priority would defeat the
     *   single-flight contract that this class exists to enforce.
     * @return ResolvedStreams if successful, null on timeout/error
     */
    suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        caller: String = "unknown",
        priority: Priority = Priority.USER_FOREGROUND,
    ): ResolvedStreams? {
        // Fast path: check if there's already an in-flight job we can join (only if not forceRefresh)
        // Note: We skip the isActive check since the job state can change immediately after.
        // Just try to await the job if it exists - if it completed/cancelled, await returns quickly.
        if (!forceRefresh) {
            val existingJob = inFlightJobs[videoId]
            if (existingJob != null) {
                Log.d(TAG, "[$caller] joined in-flight resolve for $videoId")
                return try {
                    withTimeoutOrNull(timeoutMs) {
                        existingJob.await()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Distinguish between caller cancellation vs shared job cancellation:
                    // - If the caller's coroutine is no longer active, rethrow to propagate cancellation
                    // - If the caller is still active but the shared job was cancelled, return null
                    if (!currentCoroutineContext().isActive) {
                        Log.d(TAG, "[$caller] caller cancelled while waiting for $videoId")
                        throw e
                    }
                    Log.w(TAG, "[$caller] shared job cancelled for $videoId, returning null")
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "[$caller] in-flight resolve failed for $videoId: ${e.message}")
                    null
                }
            }
        }

        // Need a new job (forceRefresh=true, or no in-flight job, or it completed/failed)
        // Use synchronized block to prevent race between check and put
        val job = synchronized(lock) {
            // If forceRefresh, cancel any existing in-flight job first
            if (forceRefresh) {
                inFlightJobs.remove(videoId)?.let { existingJob ->
                    if (existingJob.isActive) {
                        Log.d(TAG, "[$caller] cancelling cached resolve for $videoId due to forceRefresh=true")
                        existingJob.cancel()
                    }
                }
            } else {
                // Double-check after acquiring lock (not forceRefresh case)
                val doubleCheckJob = inFlightJobs[videoId]
                if (doubleCheckJob != null && doubleCheckJob.isActive) {
                    Log.d(TAG, "[$caller] joined in-flight resolve for $videoId (after lock)")
                    return@synchronized doubleCheckJob
                }
            }

            // Create new job
            Log.d(TAG, "[$caller] new resolve for $videoId (forceRefresh=$forceRefresh, priority=$priority)")
            val newJob: Deferred<ResolvedStreams?> = resolverScope.async {
                try {
                    resolutionProvider.resolveStreams(videoId, forceRefresh, priority)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "resolveStreams failed for $videoId: ${e.message}")
                    null
                }
                // Note: cleanup happens via invokeOnCompletion below, not in finally
            }
            // IMPORTANT: Insert into map BEFORE registering completion handler.
            // This prevents a race where the job completes before insertion,
            // causing the completion handler to fail removing a job that wasn't inserted yet,
            // leaving a completed job permanently in the map.
            inFlightJobs[videoId] = newJob
            // Use remove(key, value) to only remove if this exact job is still registered
            // This prevents a race where an older job's completion removes a newer job
            newJob.invokeOnCompletion {
                val removed = inFlightJobs.remove(videoId, newJob)
                if (removed) {
                    Log.d(TAG, "Cleaned up completed job for $videoId")
                    // Notify test listener (if set) for deterministic synchronization
                    onJobCleanup?.invoke(videoId)
                }
            }
            newJob
        }

        val result = try {
            withTimeoutOrNull(timeoutMs) {
                job.await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Distinguish between caller cancellation vs shared job cancellation:
            // - If the caller's coroutine is no longer active, rethrow to propagate cancellation
            //   (e.g., fragment destroyed mid-resolve → structured concurrency demands propagation)
            // - If the caller is still active but the shared job was cancelled (e.g., cancelResolve
            //   or forceRefresh), return null so the caller can handle gracefully
            if (!currentCoroutineContext().isActive) {
                Log.d(TAG, "[$caller] caller cancelled while awaiting new job for $videoId")
                throw e
            }
            Log.w(TAG, "[$caller] shared job cancelled for $videoId, returning null")
            null
        } catch (e: Exception) {
            Log.w(TAG, "[$caller] resolve failed for $videoId: ${e.message}")
            null
        }

        if (result == null) {
            // Only log timeout if we didn't already log a failure (exception)
            // Note: We can't distinguish timeout vs exception that returned null, but that's fine
            Log.d(TAG, "[$caller] resolve returned null for $videoId (timeout or no result)")
        }

        return result
    }

    /**
     * Check if a resolve is currently in-flight for a videoId.
     * Useful for UI to show "loading" state appropriately.
     */
    fun isResolveInFlight(videoId: String): Boolean {
        val job = inFlightJobs[videoId]
        return job != null && job.isActive
    }

    /**
     * Cancel an in-flight resolve for a videoId.
     * Use sparingly - other callers waiting on this job will get null.
     */
    fun cancelResolve(videoId: String) {
        val cancelled = inFlightJobs.remove(videoId)?.also { it.cancel() }
        if (cancelled != null) {
            Log.d(TAG, "Cancelled resolve for $videoId")
        } else {
            Log.d(TAG, "No in-flight resolve to cancel for $videoId")
        }
    }

    /**
     * Cancel all in-flight resolves.
     * Call on app background or memory pressure.
     *
     * Note: Uses synchronized to ensure atomic cancel+clear operation.
     */
    fun cancelAll() {
        synchronized(lock) {
            val count = inFlightJobs.size
            inFlightJobs.forEach { (_, job) -> job.cancel() }
            inFlightJobs.clear()
            Log.d(TAG, "Cancelled all resolves ($count jobs)")
        }
    }

    /**
     * Get count of in-flight resolves (for debugging/metrics).
     */
    fun getInFlightCount(): Int = inFlightJobs.count { it.value.isActive }
}
