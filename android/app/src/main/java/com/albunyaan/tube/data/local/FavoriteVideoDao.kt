package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for favorite videos.
 *
 * All reads scope to current user (uid) and exclude soft-deleted rows.
 * All writes set sync metadata (user_id, dirty). Hard deletes are reserved
 * for account-switch via [wipeForUid].
 */
@Dao
interface FavoriteVideoDao {

    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND deleted = 0 ORDER BY addedAt DESC")
    fun getAllFavorites(uid: String): Flow<List<FavoriteVideo>>

    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND deleted = 0 ORDER BY addedAt DESC")
    suspend fun getAll(uid: String): List<FavoriteVideo>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_videos WHERE videoId = :videoId AND user_id = :uid AND deleted = 0)")
    fun isFavorite(uid: String, videoId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_videos WHERE videoId = :videoId AND user_id = :uid AND deleted = 0)")
    suspend fun isFavoriteOnce(uid: String, videoId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(video: FavoriteVideo)

    @Query("""
        UPDATE favorite_videos
        SET title = :title, channelName = :channelName, thumbnailUrl = :thumbnailUrl, durationSeconds = :durationSeconds, dirty = 1
        WHERE videoId = :videoId AND user_id = :uid
    """)
    suspend fun updateMetadata(
        uid: String,
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    )

    @Transaction
    suspend fun upsertFavorite(video: FavoriteVideo) {
        val exists = isFavoriteOnce(video.user_id, video.videoId)
        if (exists) {
            updateMetadata(video.user_id, video.videoId, video.title, video.channelName, video.thumbnailUrl, video.durationSeconds)
        } else {
            addFavorite(video)
        }
    }

    @Query("UPDATE favorite_videos SET deleted = 1, dirty = 1 WHERE videoId = :videoId AND user_id = :uid")
    suspend fun softDelete(uid: String, videoId: String)

    @Transaction
    suspend fun toggleFavorite(video: FavoriteVideo): Boolean {
        val isFav = isFavoriteOnce(video.user_id, video.videoId)
        if (isFav) {
            softDelete(video.user_id, video.videoId)
        } else {
            addFavorite(video)
        }
        return !isFav
    }

    @Query("SELECT COUNT(*) FROM favorite_videos WHERE user_id = :uid AND deleted = 0")
    fun getFavoriteCount(uid: String): Flow<Int>

    @Query("DELETE FROM favorite_videos WHERE user_id = :uid")
    suspend fun clearAll(uid: String)

    // ── Sync surface ──────────────────────────────────────────────────

    @Query("UPDATE favorite_videos SET user_id = :uid, dirty = 1 WHERE user_id = ''")
    suspend fun tagAnonRowsToUid(uid: String): Int

    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND dirty = 1")
    suspend fun selectDirty(uid: String): List<FavoriteVideo>

    @Query("UPDATE favorite_videos SET updated_at = :ts, dirty = 0 WHERE videoId = :videoId AND user_id = :uid")
    suspend fun clearDirty(uid: String, videoId: String, ts: Long)

    @Query("DELETE FROM favorite_videos WHERE user_id = :uid")
    suspend fun wipeForUid(uid: String)

    @Query("UPDATE favorite_videos SET deleted = 1, dirty = 0, updated_at = :ts WHERE videoId = :videoId AND user_id = :uid")
    suspend fun applyTombstone(uid: String, videoId: String, ts: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromServer(video: FavoriteVideo)
}
