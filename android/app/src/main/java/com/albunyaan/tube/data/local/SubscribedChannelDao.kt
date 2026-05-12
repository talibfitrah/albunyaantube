package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscribedChannelDao {

    @Query("SELECT * FROM subscribed_channels WHERE user_id = :uid AND deleted = 0 ORDER BY subscribedAt DESC")
    fun observeAll(uid: String): Flow<List<SubscribedChannel>>

    @Query("SELECT * FROM subscribed_channels WHERE user_id = :uid AND deleted = 0 ORDER BY subscribedAt DESC")
    suspend fun getAll(uid: String): List<SubscribedChannel>

    @Query("SELECT * FROM subscribed_channels WHERE channelId = :id AND user_id = :uid AND deleted = 0")
    suspend fun getById(uid: String, id: String): SubscribedChannel?

    @Query("SELECT COUNT(*) FROM subscribed_channels WHERE user_id = :uid AND deleted = 0")
    suspend fun count(uid: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :id AND user_id = :uid AND deleted = 0)")
    fun observeIsSubscribed(uid: String, id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :id AND user_id = :uid AND deleted = 0)")
    suspend fun isSubscribed(uid: String, id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: SubscribedChannel)

    @Query("UPDATE subscribed_channels SET deleted = 1, dirty = 1 WHERE channelId = :id AND user_id = :uid")
    suspend fun softDelete(uid: String, id: String)

    // ── Sync surface ──────────────────────────────────────────────────

    @Query("UPDATE subscribed_channels SET user_id = :uid, dirty = 1 WHERE user_id = ''")
    suspend fun tagAnonRowsToUid(uid: String): Int

    @Query("SELECT * FROM subscribed_channels WHERE user_id = :uid AND dirty = 1")
    suspend fun selectDirty(uid: String): List<SubscribedChannel>

    @Query("UPDATE subscribed_channels SET updated_at = :ts, dirty = 0 WHERE channelId = :id AND user_id = :uid")
    suspend fun clearDirty(uid: String, id: String, ts: Long)

    @Query("DELETE FROM subscribed_channels WHERE user_id = :uid")
    suspend fun wipeForUid(uid: String)

    @Query("UPDATE subscribed_channels SET deleted = 1, dirty = 0, updated_at = :ts WHERE channelId = :id AND user_id = :uid")
    suspend fun applyTombstone(uid: String, id: String, ts: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromServer(channel: SubscribedChannel)
}
