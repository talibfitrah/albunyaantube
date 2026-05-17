package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams

/**
 * Thrown by [GlobalStreamResolver.resolveStreams] when the backend availability
 * gate reports the requested video is no longer available (archived, removed,
 * hidden, or otherwise non-public). Callers must catch this distinctly from
 * stream-resolution failures so the UI can surface a "content not available"
 * state instead of a generic "stream error" / retry.
 *
 * NB1 fix (Stage-3 review): the gate moved one level deeper — from the
 * per-caller [DefaultPlayerRepository.resolveStreams] chokepoint to the global
 * [GlobalStreamResolver.resolveStreams] entry point. That covers BOTH
 * [DefaultPlayerRepository] AND [StreamPrefetchService] (the tap-prefetch path
 * was the leak: list-tap thumbnails for archived videos used to trigger NewPipe
 * extraction in the background and cache the result).
 *
 * Earlier C1 + C2 fixes covered the regular-player playlist queue and the
 * shorts player by gating inside the repository; the chokepoint move keeps
 * those covered (every call still flows through [GlobalStreamResolver]).
 *
 * @param videoId The YouTube id whose availability check returned `false`.
 */
class ContentUnavailableException(val videoId: String) :
    RuntimeException("Video $videoId is unavailable per backend availability check")

interface PlayerRepository {
    /**
     * Resolve stream URLs for the given video.
     *
     * The backend availability gate is enforced inside
     * [GlobalStreamResolver.resolveStreams] — see [ContentUnavailableException]
     * for context on why the chokepoint moved one level deeper. Callers must
     * still catch [ContentUnavailableException] (the player path) or wrap in
     * `runCatching` (the shorts path) to surface the right UI state.
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
        sourceChannelId: String? = null,
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
 * Archived-content NB1 fix: the per-caller availability gate that used to live
 * here moved one level deeper into [GlobalStreamResolver.resolveStreams]. That
 * covers the tap-prefetch leak ([StreamPrefetchService.triggerPrefetch] +
 * [StreamPrefetchService.awaitOrConsumePrefetch] both call the global resolver
 * directly — they never went through this repository, so a per-caller gate
 * here couldn't see them). The new chokepoint catches every path with a single
 * gate and at most one HEAD per extraction.
 */
class DefaultPlayerRepository(
    private val globalResolver: GlobalStreamResolver,
) : PlayerRepository {
    override suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean,
        priority: Priority,
        sourceChannelId: String?,
    ): ResolvedStreams? {
        return globalResolver.resolveStreams(
            videoId = videoId,
            forceRefresh = forceRefresh,
            caller = "player",
            priority = priority,
            sourceChannelId = sourceChannelId,
        )
    }
}
