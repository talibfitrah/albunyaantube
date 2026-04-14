package com.albunyaan.tube.data.me

sealed class ChipItem {
    abstract val id: String
    abstract val label: String
    abstract val imageUrl: String?

    data class Channel(
        override val id: String,
        override val label: String,
        override val imageUrl: String?,
        val channelUrl: String,
    ) : ChipItem()

    data class Playlist(
        override val id: String,
        override val label: String,
        override val imageUrl: String?,
        val playlistUrl: String,
    ) : ChipItem()
}

data class MeFeedVideo(
    val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?,
    val uploadedAt: Long,
    val isShort: Boolean,
)

sealed class MeFeedState {
    data object Loading : MeFeedState()
    data object Empty : MeFeedState()
    data class Content(
        val chips: List<ChipItem>,
        val shorts: List<MeFeedVideo>,
        val videos: List<MeFeedVideo>,
        val refreshing: Boolean,
        val filterChannelId: String?,
    ) : MeFeedState()
    data class Error(val message: String) : MeFeedState()
}
