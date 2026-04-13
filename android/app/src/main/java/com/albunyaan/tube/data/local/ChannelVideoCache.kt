package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channel_video_cache",
    indices = [Index("channelId"), Index("uploadedAt")]
)
data class ChannelVideoCache(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?,
    val uploadedAt: Long?,
    val isShort: Boolean,
    val fetchedAt: Long = System.currentTimeMillis()
)
