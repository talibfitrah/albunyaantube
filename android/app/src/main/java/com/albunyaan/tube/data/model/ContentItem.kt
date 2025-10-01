package com.albunyaan.tube.data.model

sealed class ContentItem {
    data class Video(
        val id: String,
        val title: String,
        val category: String,
        val durationMinutes: Int,
        val uploadedDaysAgo: Int,
        val description: String
    ) : ContentItem()

    data class Channel(
        val id: String,
        val name: String,
        val category: String,
        val subscribers: Int
    ) : ContentItem()

    data class Playlist(
        val id: String,
        val title: String,
        val category: String,
        val itemCount: Int
    ) : ContentItem()
}
