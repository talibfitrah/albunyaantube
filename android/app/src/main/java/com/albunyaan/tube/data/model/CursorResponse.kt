package com.albunyaan.tube.data.model

data class CursorResponse(
    val items: List<ContentItem>,
    val nextCursor: String?
)

enum class ContentType {
    HOME,
    CHANNELS,
    PLAYLISTS,
    VIDEOS
}
