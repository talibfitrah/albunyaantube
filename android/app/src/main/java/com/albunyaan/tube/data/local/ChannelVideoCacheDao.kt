package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelVideoCacheDao {

    @Query(
        """SELECT * FROM channel_video_cache
           WHERE uploadedAt IS NOT NULL AND uploadedAt >= :minUploadedAt
           ORDER BY uploadedAt DESC"""
    )
    fun observeRecent(minUploadedAt: Long): Flow<List<ChannelVideoCache>>

    @Query(
        """SELECT * FROM channel_video_cache
           WHERE channelId = :channelId
           ORDER BY uploadedAt DESC"""
    )
    suspend fun getForChannel(channelId: String): List<ChannelVideoCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ChannelVideoCache>)

    @Query("DELETE FROM channel_video_cache WHERE channelId = :channelId")
    suspend fun deleteForChannel(channelId: String)

    @Transaction
    suspend fun replaceForChannel(channelId: String, rows: List<ChannelVideoCache>) {
        deleteForChannel(channelId)
        if (rows.isNotEmpty()) upsertAll(rows)
    }

    @Query(
        """DELETE FROM channel_video_cache
           WHERE channelId NOT IN (SELECT channelId FROM subscribed_channels)"""
    )
    suspend fun pruneUnsubscribed()
}
