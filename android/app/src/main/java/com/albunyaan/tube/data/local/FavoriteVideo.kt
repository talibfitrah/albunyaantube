package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a favorited video stored locally.
 *
 * This replaces YouTube's like functionality with local favorites that
 * persist across sessions without requiring a Google account.
 *
 * @property videoId The YouTube video ID (unique identifier)
 * @property title Video title at time of favoriting
 * @property channelName Channel name at time of favoriting
 * @property thumbnailUrl Thumbnail URL for display in favorites list
 * @property durationSeconds Video duration in seconds
 * @property addedAt Timestamp when the video was favorited (epoch millis)
 */
@Entity(tableName = "favorite_videos")
data class FavoriteVideo(
    @PrimaryKey
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val addedAt: Long = System.currentTimeMillis()
)
