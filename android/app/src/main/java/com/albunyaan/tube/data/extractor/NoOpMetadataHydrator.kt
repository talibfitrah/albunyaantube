package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType

/**
 * A no-op implementation of MetadataHydrator that returns items unchanged.
 * Used when backend already provides complete metadata (thumbnails, titles, counts).
 */
class NoOpMetadataHydrator : MetadataHydrator(NoOpExtractorClient) {
    override suspend fun hydrate(type: ContentType, items: List<ContentItem>): List<ContentItem> {
        return items
    }
}

private object NoOpExtractorClient : ExtractorClient {
    override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> = emptyMap()
    override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> = emptyMap()
    override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
}
