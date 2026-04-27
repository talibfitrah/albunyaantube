package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams

interface PlayerRepository {
    /**
     * Resolve stream URLs for the given video.
     *
     * @param forceRefresh If true, bypass the cache and fetch fresh URLs (use for recovery from playback failures).
     * @param priority Which rate-limit / cooldown lane the caller belongs to (spec §4.5 + D1).
     *   Defaults to [Priority.PLAYER] because the historical (and primary) consumer is the
     *   live playback path, which must bypass the gates per spec D1. Background callers
     *   (prefetch of upcoming queue items, download worker resolves, etc.) MUST override
     *   this with [Priority.USER_FOREGROUND] (or lower) — otherwise they silently ride
     *   the player bypass and undermine cooldown / rate-limit invariants
     *   (ANDROID-PERSONAL-02 round 2 [Bug A]).
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
 */
class DefaultPlayerRepository(
    private val globalResolver: GlobalStreamResolver
) : PlayerRepository {
    override suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean,
        priority: Priority,
    ): ResolvedStreams? =
        globalResolver.resolveStreams(
            videoId = videoId,
            forceRefresh = forceRefresh,
            caller = "player",
            priority = priority,
        )
}
