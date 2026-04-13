package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscribed_channels")
data class SubscribedChannel(
    @PrimaryKey val channelId: String,
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long = System.currentTimeMillis()
)
