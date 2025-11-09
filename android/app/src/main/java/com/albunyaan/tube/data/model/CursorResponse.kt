package com.albunyaan.tube.data.model

data class CursorResponse(
    val data: List<ContentItem>,
    val pageInfo: PageInfo?
) {
    data class PageInfo(
        val nextCursor: String?
    )
}

enum class ContentType {
    HOME,
    CHANNELS,
    PLAYLISTS,
    VIDEOS
}

