package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.source.AvailabilityCheckType
import com.albunyaan.tube.data.source.ContentService
import javax.inject.Named

/**
 * Thrown by [PlayerRepository.resolveStreams] when the backend availability gate
 * reports the requested video is no longer available (archived, removed, hidden,
 * or otherwise non-public). Callers must catch this distinctly from
 * stream-resolution failures so the UI can surface a "content not available"
 * state instead of a generic "stream error" / retry.
 *
 * Closes the C1 (regular player playlist queue) and C2 (shorts player) leaks
 * identified in the multi-stage review of the archived-content fix:
 * `PlayerViewModel.loadPlaylist`, `advanceToNext`, `playPrevious`,
 * `hydrateQueue`, `initializePlaylistQueue`, and `ShortsPlayerViewModel` all
 * funnel through `PlayerRepository.resolveStreams`, so gating here covers every
 * call site with a single chokepoint.
 *
 * @param videoId The YouTube id whose availability check returned `false`.
 */
class ContentUnavailableException(val videoId: String) :
    RuntimeException("Video $videoId is unavailable per backend availability check")

interface PlayerRepository {
    /**
     * Resolve stream URLs for the given video.
     *
     * Implementations must perform a backend availability check first and throw
     * [ContentUnavailableException] when the video is archived/removed. Transport
     * errors during the availability check fail-open (proceed to resolve) so
     * offline users are never blocked from valid playback. See the C1 + C2
     * review notes for why this gate lives at the lowest common chokepoint
     * instead of at each individual call site.
     *
     * @param forceRefresh If true, bypass the cache and fetch fresh URLs (use for recovery from playback failures).
     * @param priority Which rate-limit / cooldown lane the caller belongs to (spec §4.5 + D1).
     *   Defaults to [Priority.PLAYER] because the historical (and primary) consumer is the
     *   live playback path, which must bypass the gates per spec D1. Background callers
     *   (prefetch of upcoming queue items, download worker resolves, etc.) MUST override
     *   this with [Priority.USER_FOREGROUND] (or lower) — otherwise they silently ride
     *   the player bypass and undermine cooldown / rate-limit invariants
     *   (ANDROID-PERSONAL-02 round 2 [Bug A]).
     *
     * @throws ContentUnavailableException when the backend reports the video is archived
     *   or otherwise unavailable. Callers catch this to surface ContentUnavailable UI
     *   state (regular player) or skip the page (shorts pager).
     */
    suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean = false,
        priority: Priority = Priority.PLAYER,
    ): ResolvedStreams?
}

/**
 * Phase 1A: Updated to use GlobalStreamResolver for single-flight semantics.
 *
 * When the player calls resolveStreams(), it will join any in-flight resolve
 * started by prefetch instead of starting a duplicate extraction.
 *
 * ANDROID-PERSONAL-02 [Bug 1]: live playback is the ONLY caller that earns
 * [Priority.PLAYER] — the rate-limit + cooldown gates must never block
 * an actively-watching user (spec D1). All other callers (prefetch, etc.)
 * supply USER_FOREGROUND so they go through the gates.
 *
 * ANDROID-PERSONAL-02 round 2 [Bug A]: the priority is now plumbed through
 * the interface instead of hardcoded here, so non-player callers
 * (PlayerViewModel.prefetchNextItems, DownloadWorker.resolveStreamViaExtractor)
 * can declare their actual lane.
 *
 * Archived-content fix C1+C2: backend availability gate runs BEFORE delegating
 * to the global resolver. This is the single chokepoint that closes the
 * playlist-queue and shorts leaks T12 missed (T12 only gated `loadVideo`).
 * Fail-open on transport errors so offline users with a stale playlist still
 * play valid videos — matches T10/T11/T12 policy.
 */
class DefaultPlayerRepository(
    private val globalResolver: GlobalStreamResolver,
    @Named("real") private val contentService: ContentService,
) : PlayerRepository {
    override suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean,
        priority: Priority,
    ): ResolvedStreams? {
        // Archived-content fix C1+C2: gate every resolve through the backend
        // availability check. Throw ContentUnavailableException so the regular
        // player can emit StreamState.ContentUnavailable and the shorts binder
        // (which already wraps this call in runCatching) skips the page.
        val available = try {
            contentService.verifyAvailable(AvailabilityCheckType.VIDEO, videoId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Fail-open on transport errors — same policy as T10/T11/T12. An
            // offline user with a stale playlist must still be able to play
            // valid cached videos. The downstream resolver / NewPipe pipeline
            // produces its own user-visible error if the actual extraction fails.
            android.util.Log.w(
                "DefaultPlayerRepository",
                "Availability check failed for $videoId; proceeding with playback",
                e,
            )
            true
        }
        if (!available) {
            android.util.Log.i(
                "DefaultPlayerRepository",
                "Video $videoId is unavailable per backend; throwing ContentUnavailableException",
            )
            throw ContentUnavailableException(videoId)
        }
        return globalResolver.resolveStreams(
            videoId = videoId,
            forceRefresh = forceRefresh,
            caller = "player",
            priority = priority,
        )
    }
}
