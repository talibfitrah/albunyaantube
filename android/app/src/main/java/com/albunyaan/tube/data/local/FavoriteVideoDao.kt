package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for favorite videos.
 *
 * Provides reactive access to favorites via Flow, enabling
 * automatic UI updates when favorites change.
 */
@Dao
interface FavoriteVideoDao {

    /**
     * Get all favorite videos ordered by most recently added.
     */
    @Query("SELECT * FROM favorite_videos ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteVideo>>

    /**
     * Check if a video is favorited.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM favorite_videos WHERE videoId = :videoId)")
    fun isFavorite(videoId: String): Flow<Boolean>

    /**
     * Check if a video is favorited (one-shot, not reactive).
     */
    @Query("SELECT EXISTS(SELECT 1 FROM favorite_videos WHERE videoId = :videoId)")
    suspend fun isFavoriteOnce(videoId: String): Boolean

    /**
     * Add a video to favorites.
     * Uses REPLACE strategy to update if already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(video: FavoriteVideo)

    /**
     * Remove a video from favorites by ID.
     */
    @Query("DELETE FROM favorite_videos WHERE videoId = :videoId")
    suspend fun removeFavorite(videoId: String)

    /**
     * Remove a video from favorites.
     */
    @Delete
    suspend fun removeFavorite(video: FavoriteVideo)

    /**
     * Toggle favorite status for a video.
     * Returns true if the video is now a favorite, false if removed.
     *
     * @Transaction ensures the check-then-insert/delete is atomic,
     * preventing race conditions from rapid taps or concurrent calls.
     */
    @Transaction
    suspend fun toggleFavorite(video: FavoriteVideo): Boolean {
        val isFav = isFavoriteOnce(video.videoId)
        if (isFav) {
            removeFavorite(video.videoId)
        } else {
            addFavorite(video)
        }
        return !isFav
    }

    /**
     * Get count of favorites.
     */
    @Query("SELECT COUNT(*) FROM favorite_videos")
    fun getFavoriteCount(): Flow<Int>

    /**
     * Clear all favorites.
     */
    @Query("DELETE FROM favorite_videos")
    suspend fun clearAll()
}
