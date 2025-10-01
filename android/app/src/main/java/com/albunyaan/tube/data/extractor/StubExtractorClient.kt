package com.albunyaan.tube.data.extractor

import kotlin.math.absoluteValue

class StubExtractorClient : ExtractorClient {

    override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> {
        return ids.associateWith { id ->
            val base = id.hashCode().absoluteValue
            VideoMetadata(
                title = "${id.replace('-', ' ').uppercase()} (Extractor)",
                description = "Hydrated video description for $id",
                thumbnailUrl = "https://img.youtube.com/vi/$id/0.jpg",
                durationSeconds = 600 + (base % 420),
                viewCount = 50_000L + (base % 10_000)
            )
        }
    }

    override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> {
        return ids.associateWith { id ->
            val base = id.hashCode().absoluteValue
            ChannelMetadata(
                name = "${id.replace('-', ' ').replaceFirstChar { it.uppercase() }}",
                description = "Hydrated channel description for $id",
                thumbnailUrl = "https://img.youtube.com/channel/$id/avatar.jpg",
                subscriberCount = 250_000L + (base % 50_000),
                videoCount = 120 + (base % 30)
            )
        }
    }

    override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> {
        return ids.associateWith { id ->
            val base = id.hashCode().absoluteValue
            PlaylistMetadata(
                title = "${id.replace('-', ' ').replaceFirstChar { it.uppercase() }}",
                description = "Hydrated playlist description for $id",
                thumbnailUrl = "https://img.youtube.com/playlist/$id/default.jpg",
                itemCount = 25 + (base % 15)
            )
        }
    }
}
