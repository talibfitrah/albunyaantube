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
 */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val favoriteVideoDao: FavoriteVideoDao
) : FavoritesRepository {

    override fun getAllFavorites(): Flow<List<FavoriteVideo>> {
        return favoriteVideoDao.getAllFavorites()
    }

    override fun isFavorite(videoId: String): Flow<Boolean> {
        return favoriteVideoDao.isFavorite(videoId)
    }

    override suspend fun isFavoriteOnce(videoId: String): Boolean {
        return favoriteVideoDao.isFavoriteOnce(videoId)
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
            durationSeconds = durationSeconds
        )
        favoriteVideoDao.addFavorite(favorite)
    }

    override suspend fun removeFavorite(videoId: String) {
        favoriteVideoDao.removeFavorite(videoId)
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
            durationSeconds = durationSeconds
        )
        return favoriteVideoDao.toggleFavorite(video)
    }

    override fun getFavoriteCount(): Flow<Int> {
        return favoriteVideoDao.getFavoriteCount()
    }

    override suspend fun clearAll() {
        favoriteVideoDao.clearAll()
    }
}
