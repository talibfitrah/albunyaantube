package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for followed channels.
 *
 * Provides reactive access to followed channels via Flow, enabling
 * automatic UI updates when the follow state changes.
 */
@Dao
interface FollowedChannelDao {

    /**
     * Get all followed channels ordered by most recently followed.
     */
    @Query("SELECT * FROM followed_channels ORDER BY followedAt DESC")
    fun getAllFollowed(): Flow<List<FollowedChannel>>

    /**
     * Check if a channel is followed (reactive).
     */
    @Query("SELECT EXISTS(SELECT 1 FROM followed_channels WHERE channelId = :id)")
    fun isFollowed(id: String): Flow<Boolean>

    /**
     * Check if a channel is followed (one-shot, not reactive).
     */
    @Query("SELECT EXISTS(SELECT 1 FROM followed_channels WHERE channelId = :id)")
    suspend fun isFollowedOnce(id: String): Boolean

    /**
     * Add a channel to followed.
     * Uses IGNORE strategy so an existing row's followedAt timestamp is preserved.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFollow(channel: FollowedChannel)

    /**
     * Remove a channel from followed by ID.
     */
    @Query("DELETE FROM followed_channels WHERE channelId = :id")
    suspend fun removeFollow(id: String)

    /**
     * Toggle follow state for a channel.
     * Returns true if the channel is now followed, false if removed.
     *
     * @Transaction ensures the check-then-insert/delete is atomic,
     * preventing race conditions from rapid taps or concurrent calls.
     */
    @Transaction
    suspend fun toggleFollow(channel: FollowedChannel): Boolean {
        val followed = isFollowedOnce(channel.channelId)
        if (followed) removeFollow(channel.channelId) else addFollow(channel)
        return !followed
    }

    @Query("DELETE FROM followed_channels")
    suspend fun clearAll()
}
