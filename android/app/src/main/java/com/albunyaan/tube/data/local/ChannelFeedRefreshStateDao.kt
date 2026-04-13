package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChannelFeedRefreshStateDao {

    @Query("SELECT * FROM channel_feed_refresh_state WHERE channelId = :id")
    suspend fun get(id: String): ChannelFeedRefreshState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ChannelFeedRefreshState)

    @Query("DELETE FROM channel_feed_refresh_state WHERE channelId = :id")
    suspend fun delete(id: String)
}
