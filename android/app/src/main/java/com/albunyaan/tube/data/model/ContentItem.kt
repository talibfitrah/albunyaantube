package com.albunyaan.tube.data.model

sealed class ContentItem {
    data class Video(
        val id: String,
        val title: String,
        val category: String,
        val durationMinutes: Int,
        val uploadedDaysAgo: Int,
        val description: String,
        val thumbnailUrl: String? = null,
        val viewCount: Long? = null
    ) : ContentItem()

    data class Channel(
        val id: String,
        val name: String,
        val category: String,
        val subscribers: Int,
        val description: String? = null,
        val thumbnailUrl: String? = null,
        val videoCount: Int? = null
    ) : ContentItem()

    data class Playlist(
        val id: String,
        val title: String,
        val category: String,
        val itemCount: Int,
        val description: String? = null,
        val thumbnailUrl: String? = null
    ) : ContentItem()
}
