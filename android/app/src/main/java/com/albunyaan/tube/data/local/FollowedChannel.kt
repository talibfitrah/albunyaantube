package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a channel the user has followed locally.
 *
 * Provides a local "follow" replacement for YouTube's subscribe action so
 * users can track channels without requiring a Google account.
 *
 * @property channelId The YouTube channel ID (unique identifier)
 * @property title Channel title at time of following
 * @property avatarUrl Channel avatar URL for display in the followed list
 * @property followedAt Timestamp when the channel was followed (epoch millis)
 */
@Entity(tableName = "followed_channels")
data class FollowedChannel(
    @PrimaryKey val channelId: String,
    val title: String,
    val avatarUrl: String?,
    val followedAt: Long = System.currentTimeMillis()
)
