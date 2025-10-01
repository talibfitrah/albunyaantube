package com.albunyaan.tube.data.model

data class CursorResponse(
    val items: List<ContentItem>,
    val hasNext: Boolean
)

enum class ContentType {
    HOME,
    CHANNELS,
    PLAYLISTS,
    VIDEOS
}
