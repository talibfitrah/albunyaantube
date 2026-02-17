package com.albunyaan.tube.data.model

data class CursorResponse(
    val data: List<ContentItem>,
    val pageInfo: PageInfo?
) {
    data class PageInfo(
        val nextCursor: String?
    )
}

data class HomeFeedResult(
    val sections: List<HomeSection>,
    val nextCursor: String?,
    val hasMore: Boolean
)

enum class ContentType {
    HOME,
    CHANNELS,
    PLAYLISTS,
    VIDEOS,
    ALL  // Used for Featured section which shows mixed content types
}
