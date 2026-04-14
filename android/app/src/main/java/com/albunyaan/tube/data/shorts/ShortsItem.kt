package com.albunyaan.tube.data.shorts

data class ShortsItem(
    val id: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int
) {
    val canonicalShareUrl: String get() = "https://www.youtube.com/shorts/$id"
}

data class ShortsPage(val items: List<ShortsItem>, val nextCursor: String?)
