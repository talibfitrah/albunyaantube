package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.ResolvedStreams

interface PlayerRepository {
    /**
     * Resolve stream URLs for the given video.
     * @param forceRefresh If true, bypass the cache and fetch fresh URLs (use for recovery from playback failures)
     */
    suspend fun resolveStreams(videoId: String, forceRefresh: Boolean = false): ResolvedStreams?
}

/**
 * Phase 1A: Updated to use GlobalStreamResolver for single-flight semantics.
 *
 * When the player calls resolveStreams(), it will join any in-flight resolve
 * started by prefetch instead of starting a duplicate extraction.
 */
class DefaultPlayerRepository(
    private val globalResolver: GlobalStreamResolver
) : PlayerRepository {
    override suspend fun resolveStreams(videoId: String, forceRefresh: Boolean): ResolvedStreams? =
        globalResolver.resolveStreams(
            videoId = videoId,
            forceRefresh = forceRefresh,
            caller = "player"
        )
}
