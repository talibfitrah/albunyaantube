package com.albunyaan.tube.data.extractor

interface ExtractorClient {
    suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata>

    suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata>

    suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata>
}
