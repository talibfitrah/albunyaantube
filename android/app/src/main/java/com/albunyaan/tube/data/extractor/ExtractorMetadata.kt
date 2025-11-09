package com.albunyaan.tube.data.extractor

data class VideoMetadata(
    val title: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    val viewCount: Long? = null
)

data class ChannelMetadata(
    val name: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val subscriberCount: Long? = null,
    val videoCount: Int? = null
)

data class PlaylistMetadata(
    val title: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val itemCount: Int? = null
)
