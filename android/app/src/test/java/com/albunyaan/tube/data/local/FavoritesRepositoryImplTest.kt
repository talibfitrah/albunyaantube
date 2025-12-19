package com.albunyaan.tube.data.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for FavoritesRepositoryImpl.
 *
 * Tests verify:
 * - getAllFavorites returns reactive flow from DAO
 * - isFavorite returns reactive boolean flow
 * - isFavoriteOnce returns one-shot boolean
 * - addFavorite creates FavoriteVideo and calls DAO
 * - removeFavorite delegates to DAO
 * - toggleFavorite returns correct new state
 * - getFavoriteCount returns reactive count
 * - clearAll delegates to DAO
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRepositoryImplTest {

    private lateinit var fakeDao: FakeFavoriteVideoDao
    private lateinit var repository: FavoritesRepositoryImpl

    @Before
    fun setup() {
        fakeDao = FakeFavoriteVideoDao()
        repository = FavoritesRepositoryImpl(fakeDao)
    }

    @Test
    fun `getAllFavorites returns flow from DAO`() = runTest {
        val video = FavoriteVideo("v1", "Title", "Channel", null, 100)
        fakeDao.addFavorite(video)

        val favorites = repository.getAllFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("v1", favorites[0].videoId)
    }

    @Test
    fun `isFavorite returns true for favorited video`() = runTest {
        val video = FavoriteVideo("v1", "Title", "Channel", null, 100)
        fakeDao.addFavorite(video)

        val isFav = repository.isFavorite("v1").first()
        assertTrue(isFav)
    }

    @Test
    fun `isFavorite returns false for non-favorited video`() = runTest {
        val isFav = repository.isFavorite("nonexistent").first()
        assertFalse(isFav)
    }

    @Test
    fun `isFavoriteOnce returns correct boolean`() = runTest {
        val video = FavoriteVideo("v1", "Title", "Channel", null, 100)
        fakeDao.addFavorite(video)

        assertTrue(repository.isFavoriteOnce("v1"))
        assertFalse(repository.isFavoriteOnce("nonexistent"))
    }

    @Test
    fun `addFavorite creates FavoriteVideo with correct properties`() = runTest {
        repository.addFavorite(
            videoId = "v1",
            title = "Test Video",
            channelName = "Test Channel",
            thumbnailUrl = "https://example.com/thumb.jpg",
            durationSeconds = 300
        )

        val favorites = fakeDao.getAllFavorites().first()
        assertEquals(1, favorites.size)

        val added = favorites[0]
        assertEquals("v1", added.videoId)
        assertEquals("Test Video", added.title)
        assertEquals("Test Channel", added.channelName)
        assertEquals("https://example.com/thumb.jpg", added.thumbnailUrl)
        assertEquals(300, added.durationSeconds)
    }

    @Test
    fun `addFavorite with null thumbnail creates FavoriteVideo`() = runTest {
        repository.addFavorite(
            videoId = "v1",
            title = "Test Video",
            channelName = "Test Channel",
            thumbnailUrl = null,
            durationSeconds = 300
        )

        val favorites = fakeDao.getAllFavorites().first()
        assertEquals(1, favorites.size)
        assertNull(favorites[0].thumbnailUrl)
    }

    @Test
    fun `removeFavorite delegates to DAO`() = runTest {
        val video = FavoriteVideo("v1", "Title", "Channel", null, 100)
        fakeDao.addFavorite(video)
        assertTrue(fakeDao.isFavoriteOnce("v1"))

        repository.removeFavorite("v1")

        assertFalse(fakeDao.isFavoriteOnce("v1"))
    }

    @Test
    fun `toggleFavorite adds favorite when not present and returns true`() = runTest {
        val result = repository.toggleFavorite(
            videoId = "v1",
            title = "Test Video",
            channelName = "Test Channel",
            thumbnailUrl = null,
            durationSeconds = 100
        )

        assertTrue("Toggle should return true when adding", result)
        assertTrue("Video should now be favorited", repository.isFavoriteOnce("v1"))
    }

    @Test
    fun `toggleFavorite removes favorite when present and returns false`() = runTest {
        // First add the favorite
        repository.addFavorite("v1", "Test Video", "Test Channel", null, 100)
        assertTrue(repository.isFavoriteOnce("v1"))

        // Toggle should remove it
        val result = repository.toggleFavorite(
            videoId = "v1",
            title = "Test Video",
            channelName = "Test Channel",
            thumbnailUrl = null,
            durationSeconds = 100
        )

        assertFalse("Toggle should return false when removing", result)
        assertFalse("Video should no longer be favorited", repository.isFavoriteOnce("v1"))
    }

    @Test
    fun `getFavoriteCount returns reactive count`() = runTest {
        assertEquals(0, repository.getFavoriteCount().first())

        repository.addFavorite("v1", "Video 1", "Channel", null, 100)
        assertEquals(1, repository.getFavoriteCount().first())

        repository.addFavorite("v2", "Video 2", "Channel", null, 200)
        assertEquals(2, repository.getFavoriteCount().first())

        repository.removeFavorite("v1")
        assertEquals(1, repository.getFavoriteCount().first())
    }

    @Test
    fun `clearAll removes all favorites`() = runTest {
        repository.addFavorite("v1", "Video 1", "Channel", null, 100)
        repository.addFavorite("v2", "Video 2", "Channel", null, 200)
        repository.addFavorite("v3", "Video 3", "Channel", null, 300)
        assertEquals(3, repository.getFavoriteCount().first())

        repository.clearAll()

        assertEquals(0, repository.getFavoriteCount().first())
        assertTrue(repository.getAllFavorites().first().isEmpty())
    }

    @Test
    fun `addFavorite replaces existing favorite with same videoId`() = runTest {
        repository.addFavorite("v1", "Original Title", "Channel", null, 100)
        repository.addFavorite("v1", "Updated Title", "Channel", null, 200)

        val favorites = repository.getAllFavorites().first()
        assertEquals("Should have only 1 favorite (replaced)", 1, favorites.size)
        assertEquals("Updated Title", favorites[0].title)
        assertEquals(200, favorites[0].durationSeconds)
    }

    /**
     * Fake DAO implementation for testing FavoritesRepositoryImpl.
     */
    private class FakeFavoriteVideoDao : FavoriteVideoDao {
        private val favoritesFlow = MutableStateFlow<List<FavoriteVideo>>(emptyList())

        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = favoritesFlow

        override fun isFavorite(videoId: String): Flow<Boolean> =
            favoritesFlow.map { list -> list.any { it.videoId == videoId } }

        override suspend fun isFavoriteOnce(videoId: String): Boolean =
            favoritesFlow.value.any { it.videoId == videoId }

        override suspend fun addFavorite(video: FavoriteVideo) {
            val current = favoritesFlow.value.toMutableList()
            // REPLACE behavior
            current.removeAll { it.videoId == video.videoId }
            current.add(video)
            favoritesFlow.value = current
        }

        override suspend fun removeFavorite(videoId: String) {
            favoritesFlow.value = favoritesFlow.value.filter { it.videoId != videoId }
        }

        override suspend fun removeFavorite(video: FavoriteVideo) {
            removeFavorite(video.videoId)
        }

        override suspend fun toggleFavorite(video: FavoriteVideo): Boolean {
            val isFav = isFavoriteOnce(video.videoId)
            if (isFav) {
                removeFavorite(video.videoId)
            } else {
                addFavorite(video)
            }
            return !isFav
        }

        override fun getFavoriteCount(): Flow<Int> =
            favoritesFlow.map { it.size }

        override suspend fun clearAll() {
            favoritesFlow.value = emptyList()
        }
    }
}
