package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.ResolvedStreams

interface PlayerRepository {
    suspend fun resolveStreams(videoId: String): ResolvedStreams?
}

class DefaultPlayerRepository(
    private val extractorClient: NewPipeExtractorClient
) : PlayerRepository {
    override suspend fun resolveStreams(videoId: String): ResolvedStreams? = extractorClient.resolveStreams(videoId)
}
