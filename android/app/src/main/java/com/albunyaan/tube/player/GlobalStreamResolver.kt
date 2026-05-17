package com.albunyaan.tube.player

import android.util.Log
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NewPipePriorityContext
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.source.AvailabilityCheckType
import com.albunyaan.tube.data.source.ContentService
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
import javax.inject.Named
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
    private val resolutionProvider: StreamResolutionProvider,
    /**
     * Backend availability gate for the archived-content fix. When non-null, every
     * call to [resolveStreams] hits this with a HEAD probe BEFORE invoking NewPipe
     * — so an archived/removed videoId can never trigger an extraction (closes
     * the NB1 prefetch leak: list-tap thumbnails for archived videos used to
     * bypass the per-caller gate that lived in [DefaultPlayerRepository]).
     *
     * Nullable because [createForTesting] callers don't always need the gate;
     * production wiring always supplies it via Hilt. When null, the gate is
     * skipped (legacy behaviour) — every direct test should opt-in by passing
     * a fake [ContentService] via [createForTesting].
     */
    private val contentService: ContentService?,
    private val resolverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        private const val TAG = "GlobalStreamResolver"
        private const val DEFAULT_TIMEOUT_MS = 20_000L // 20s default timeout

        /**
         * Create a GlobalStreamResolver with a custom resolution provider for testing.
         *
         * @param provider The stream resolution provider to use
         * @param contentService Optional backend availability gate. Default `null`
         *   skips the gate so existing tests that pre-date the chokepoint move
         *   keep working. Tests that exercise the gate (NB1 prefetch leak
         *   regression coverage) supply a fake.
         * @return A new GlobalStreamResolver instance
         */
        @JvmStatic
        @JvmOverloads
        fun createForTesting(
            provider: StreamResolutionProvider,
            contentService: ContentService? = null,
        ): GlobalStreamResolver {
            return GlobalStreamResolver(
                provider,
                contentService,
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            )
        }
    }

    /**
     * Primary constructor for production use with Hilt DI.
     *
     * @param extractorClient NewPipe extraction backend.
     * @param contentService Backend availability gate (the @Named("real") binding).
     *   Runs ahead of every NewPipe extraction so archived / removed videos can
     *   never trigger a list-tap prefetch (NB1 fix — moves the chokepoint that
     *   used to live in [DefaultPlayerRepository] one level deeper, covering
     *   prefetch + player + shorts in a single place). Fail-open on transport
     *   errors: an offline user must still be able to play valid cached videos.
     */
    @Inject
    constructor(
        extractorClient: NewPipeExtractorClient,
        @Named("real") contentService: ContentService,
    ) : this(
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
        },
        contentService,
    )

    /**
     * In-flight resolve job paired with the [Priority] under which it was
     * created. Tracking the priority is required so a later, higher-priority
     * caller can detect the lane mismatch and force an escalation (cancel
     * the existing job and restart) — see ANDROID-PERSONAL-02 round 2 [Bug B].
     */
    private data class InFlight(
        val deferred: Deferred<ResolvedStreams?>,
        val priority: Priority,
    )

    // In-flight resolve jobs by videoId - any caller can join
    private val inFlightJobs = ConcurrentHashMap<String, InFlight>()

    // Private lock for synchronizing job creation/cancellation (don't use 'this' as lock)
    private val lock = Any()

    /**
     * Comparable rank for priorities. Higher number = higher priority.
     *
     * Defined locally instead of on the enum because the enum's
     * declaration order is documented as not significant — adding a
     * `compareTo` to it would invite ordinal-based bugs elsewhere.
     */
    private fun Priority.rank(): Int = when (this) {
        Priority.PLAYER -> 2
        Priority.VISIBLE_INTERACTIVE -> 1
        Priority.USER_FOREGROUND -> 1
        Priority.BACKGROUND_REFRESH -> 0
    }

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
     * ## Priority escalation (ANDROID-PERSONAL-02 round 2 [Bug B])
     *
     * Without escalation, a USER_FOREGROUND prefetch that started first
     * could trap a later PLAYER caller behind the rate-limit + cooldown
     * gates of the prefetch's lane — exactly what spec D1 forbids
     * ("playback must never block on a refresh-thread bucket"). To prevent
     * that, when a higher-priority caller arrives we **cancel the existing
     * in-flight job and start a new one** at the higher priority. The lower-
     * priority caller's `await()` returns null (cancellation result); that
     * caller is expected to be null-tolerant. Currently
     * [com.albunyaan.tube.player.StreamPrefetchService] is null-tolerant
     * (logs and continues); future callers must be too.
     *
     * Same-priority and lower-priority arrivals continue to join (no
     * cancellation, no duplicate work).
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
     *   caller of the **same or lower** priority joins an already-in-flight
     *   job (de-duplication), the second caller inherits the first
     *   caller's priority for that job — the first caller wins. A
     *   *higher* priority caller does NOT join; instead it triggers the
     *   escalation described above.
     * @return ResolvedStreams if successful, null on timeout/error
     */
    suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        caller: String = "unknown",
        priority: Priority = Priority.USER_FOREGROUND,
        sourceChannelId: String? = null,
    ): ResolvedStreams? {
        // Fast path: check if there's already an in-flight job we can join (only if not forceRefresh)
        // Note: We skip the isActive check since the job state can change immediately after.
        // Just try to await the job if it exists - if it completed/cancelled, await returns quickly.
        //
        // ANDROID-PERSONAL-02 round 2 [Bug B]: a higher-priority caller must
        // NOT join a lower-priority in-flight job — fall through to the
        // synchronized block so the escalation path can replace the job.
        if (!forceRefresh) {
            val existing = inFlightJobs[videoId]
            if (existing != null && priority.rank() <= existing.priority.rank()) {
                Log.d(TAG, "[$caller] joined in-flight resolve for $videoId (priority=$priority, existing=${existing.priority})")
                return try {
                    withTimeoutOrNull(timeoutMs) {
                        existing.deferred.await()
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
                } catch (e: ContentUnavailableException) {
                    // NB1 fix: the chokepoint inside the in-flight job said the
                    // video is archived. Every joining caller must see the same
                    // verdict — propagate so the player path's
                    // [com.albunyaan.tube.ui.player.PlayerViewModel.resolveWithRetry]
                    // (and shorts via [PlayerBinder]'s runCatching) can render
                    // ContentUnavailable. Squashing this to null would silently
                    // re-open the leak.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "[$caller] in-flight resolve failed for $videoId: ${e.message}")
                    null
                }
            }
        }

        // Need a new job (forceRefresh=true, no in-flight job, it completed/failed,
        // OR a higher-priority caller is escalating an existing lower-priority job).
        // Use synchronized block to prevent race between check and put.
        val job = synchronized(lock) {
            // If forceRefresh, cancel any existing in-flight job first
            if (forceRefresh) {
                inFlightJobs.remove(videoId)?.let { existing ->
                    if (existing.deferred.isActive) {
                        Log.d(TAG, "[$caller] cancelling cached resolve for $videoId due to forceRefresh=true")
                        existing.deferred.cancel()
                    }
                }
            } else {
                // Double-check after acquiring lock
                val doubleCheck = inFlightJobs[videoId]
                if (doubleCheck != null && doubleCheck.deferred.isActive) {
                    if (priority.rank() <= doubleCheck.priority.rank()) {
                        // Same / lower priority: join the existing job (de-duplication wins).
                        Log.d(
                            TAG,
                            "[$caller] joined in-flight resolve for $videoId (after lock; priority=$priority, existing=${doubleCheck.priority})",
                        )
                        return@synchronized doubleCheck.deferred
                    }
                    // ANDROID-PERSONAL-02 round 2 [Bug B]: priority escalation.
                    // The existing job runs at a strictly lower priority than
                    // this caller demands. Joining would trap us behind the
                    // wrong rate-limit / cooldown lane (spec D1). Cancel the
                    // existing job and let the new-job path below recreate
                    // it at the higher priority. Lower-priority callers
                    // already awaiting that Deferred will get a
                    // CancellationException, which the existing await() catch
                    // converts to null (they must be null-tolerant — see
                    // KDoc above).
                    //
                    // NOTE: invokeOnCompletion uses remove(key, value), so
                    // when the cancelled job's completion handler fires it
                    // will not yank the replacement job out of the map.
                    Log.d(
                        TAG,
                        "[$caller] escalating priority for $videoId (was=${doubleCheck.priority}, now=$priority); cancelling existing job",
                    )
                    inFlightJobs.remove(videoId)
                    doubleCheck.deferred.cancel()
                }
            }

            // Create new job
            Log.d(TAG, "[$caller] new resolve for $videoId (forceRefresh=$forceRefresh, priority=$priority)")
            val newJob: Deferred<ResolvedStreams?> = resolverScope.async {
                // Archived-content NB1 fix: backend availability gate runs as the
                // FIRST step of every new resolve job — moved from
                // [DefaultPlayerRepository.resolveStreams] one level deeper so
                // both the player path AND the tap-prefetch path
                // ([StreamPrefetchService.triggerPrefetch] + [awaitOrConsumePrefetch])
                // share a single chokepoint.
                //
                // Why inside the async (and not at the top of resolveStreams):
                // joiners of an in-flight job must NOT each do their own HEAD
                // call. Putting the gate here means exactly one HEAD per
                // extraction — the gate fires for the first caller; later
                // joiners share its outcome (the gate's
                // [ContentUnavailableException] propagates to all awaiters).
                //
                // Fail-open on transport errors mirrors the policy used by T10
                // / T11 / T12 and the previous repository chokepoint: an
                // offline user with a stale list must still be able to play
                // valid cached videos. The downstream resolver / NewPipe
                // pipeline produces its own user-visible error if the actual
                // extraction fails.
                if (contentService != null) {
                    val available = try {
                        if (sourceChannelId != null) {
                            // Video is from an approved channel — check channel availability,
                            // not per-video registry (channel videos aren't individually registered).
                            contentService.verifyAvailable(AvailabilityCheckType.CHANNEL, sourceChannelId)
                        } else {
                            contentService.verifyAvailable(AvailabilityCheckType.VIDEO, videoId)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "[$caller] availability check failed for $videoId; proceeding", e)
                        true
                    }
                    if (!available) {
                        Log.i(
                            TAG,
                            "[$caller] $videoId unavailable per backend; throwing ContentUnavailableException",
                        )
                        throw ContentUnavailableException(videoId)
                    }
                }
                try {
                    resolutionProvider.resolveStreams(videoId, forceRefresh, priority)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is ContentUnavailableException) throw e
                    Log.e(TAG, "resolveStreams failed for $videoId: ${e.message}")
                    null
                }
                // Note: cleanup happens via invokeOnCompletion below, not in finally
            }
            val inFlight = InFlight(newJob, priority)
            // IMPORTANT: Insert into map BEFORE registering completion handler.
            // This prevents a race where the job completes before insertion,
            // causing the completion handler to fail removing a job that wasn't inserted yet,
            // leaving a completed job permanently in the map.
            inFlightJobs[videoId] = inFlight
            // Use remove(key, value) to only remove if this exact InFlight is still registered
            // This prevents a race where an older job's completion removes a newer job
            newJob.invokeOnCompletion {
                val removed = inFlightJobs.remove(videoId, inFlight)
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
        } catch (e: ContentUnavailableException) {
            // NB1 fix: the chokepoint inside the new resolve job said the
            // video is archived. Propagate to the caller so the player path
            // ([PlayerViewModel.resolveWithRetry]) can render
            // ContentUnavailable instead of treating it as a generic
            // resolve failure (which would trigger retries and an Error
            // overlay).
            throw e
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
        val inFlight = inFlightJobs[videoId]
        return inFlight != null && inFlight.deferred.isActive
    }

    /**
     * Cancel an in-flight resolve for a videoId.
     * Use sparingly - other callers waiting on this job will get null.
     */
    fun cancelResolve(videoId: String) {
        val cancelled = inFlightJobs.remove(videoId)?.also { it.deferred.cancel() }
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
            inFlightJobs.forEach { (_, inFlight) -> inFlight.deferred.cancel() }
            inFlightJobs.clear()
            Log.d(TAG, "Cancelled all resolves ($count jobs)")
        }
    }

    /**
     * Get count of in-flight resolves (for debugging/metrics).
     */
    fun getInFlightCount(): Int = inFlightJobs.count { it.value.deferred.isActive }
}
