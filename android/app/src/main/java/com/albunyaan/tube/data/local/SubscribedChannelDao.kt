package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscribedChannelDao {

    @Query("SELECT * FROM subscribed_channels ORDER BY subscribedAt DESC")
    fun observeAll(): Flow<List<SubscribedChannel>>

    @Query("SELECT * FROM subscribed_channels ORDER BY subscribedAt DESC")
    suspend fun getAll(): List<SubscribedChannel>

    @Query("SELECT * FROM subscribed_channels WHERE channelId = :id")
    suspend fun getById(id: String): SubscribedChannel?

    @Query("SELECT COUNT(*) FROM subscribed_channels")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :id)")
    fun observeIsSubscribed(id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :id)")
    suspend fun isSubscribed(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: SubscribedChannel)

    @Query("DELETE FROM subscribed_channels WHERE channelId = :id")
    suspend fun delete(id: String)
}
