package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.ResolvedStreams

interface PlayerRepository {
    /**
     * Resolve stream URLs for the given video.
     * @param forceRefresh If true, bypass the cache and fetch fresh URLs (use for recovery from playback failures)
     */
    suspend fun resolveStreams(videoId: String, forceRefresh: Boolean = false): ResolvedStreams?
}

class DefaultPlayerRepository(
    private val extractorClient: NewPipeExtractorClient
) : PlayerRepository {
    override suspend fun resolveStreams(videoId: String, forceRefresh: Boolean): ResolvedStreams? =
        extractorClient.resolveStreams(videoId, forceRefresh)
}
