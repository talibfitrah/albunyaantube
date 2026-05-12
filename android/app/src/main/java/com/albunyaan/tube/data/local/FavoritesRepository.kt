package com.albunyaan.tube.data.local

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for managing favorite videos.
 *
 * Provides a clean abstraction for favorites operations, enabling
 * testing with fake implementations.
 */
interface FavoritesRepository {
    /**
     * Get all favorite videos as a Flow.
     * Emits updates automatically when favorites change.
     */
    fun getAllFavorites(): Flow<List<FavoriteVideo>>

    /**
     * Check if a video is favorited (reactive).
     */
    fun isFavorite(videoId: String): Flow<Boolean>

    /**
     * Check if a video is favorited (one-shot).
     */
    suspend fun isFavoriteOnce(videoId: String): Boolean

    /**
     * Add a video to favorites.
     */
    suspend fun addFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    )

    /**
     * Remove a video from favorites.
     */
    suspend fun removeFavorite(videoId: String)

    /**
     * Toggle favorite status.
     * Returns true if now favorited, false if removed.
     */
    suspend fun toggleFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    ): Boolean

    /**
     * Get the count of favorites.
     */
    fun getFavoriteCount(): Flow<Int>

    /**
     * Clear all favorites.
     */
    suspend fun clearAll()
}

/**
 * Default implementation of FavoritesRepository using Room DAO.
 *
 * NOTE: Until T26 wires real FirebaseAuth UIDs, all DAO calls pass uid=""
 * (the anon-era sentinel). SyncManager.bind() will tag these rows with the
 * real uid on first sign-in. Do not replace "" with a hardcoded string here.
 */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val favoriteVideoDao: FavoriteVideoDao
) : FavoritesRepository {

    override fun getAllFavorites(): Flow<List<FavoriteVideo>> {
        return favoriteVideoDao.getAllFavorites(uid = "")
    }

    override fun isFavorite(videoId: String): Flow<Boolean> {
        return favoriteVideoDao.isFavorite(uid = "", videoId = videoId)
    }

    override suspend fun isFavoriteOnce(videoId: String): Boolean {
        return favoriteVideoDao.isFavoriteOnce(uid = "", videoId = videoId)
    }

    override suspend fun addFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    ) {
        val favorite = FavoriteVideo(
            videoId = videoId,
            title = title,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            user_id = "",
        )
        // Use upsert to update metadata while preserving addedAt for existing favorites
        favoriteVideoDao.upsertFavorite(favorite)
    }

    override suspend fun removeFavorite(videoId: String) {
        favoriteVideoDao.softDelete(uid = "", videoId = videoId)
    }

    override suspend fun toggleFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    ): Boolean {
        val video = FavoriteVideo(
            videoId = videoId,
            title = title,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            user_id = "",
        )
        return favoriteVideoDao.toggleFavorite(video)
    }

    override fun getFavoriteCount(): Flow<Int> {
        return favoriteVideoDao.getFavoriteCount(uid = "")
    }

    override suspend fun clearAll() {
        favoriteVideoDao.clearAll(uid = "")
    }
}
