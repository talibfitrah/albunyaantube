package com.albunyaan.tube.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for FavoriteVideoDao using an in-memory Room database.
 *
 * These tests verify:
 * - Basic CRUD operations work correctly
 * - @Transaction toggleFavorite executes atomically (check-then-modify in single transaction)
 * - Flow emissions update correctly
 *
 * Note: These are functional/state-based tests, not concurrent stress tests.
 * The @Transaction annotation ensures atomicity at the database level.
 *
 * Uses in-memory database for fast, isolated tests that don't persist data.
 */
@RunWith(AndroidJUnit4::class)
class FavoriteVideoDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: FavoriteVideoDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // OK for tests
            .build()
        dao = database.favoriteVideoDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // region Basic CRUD Operations

    @Test
    fun addFavorite_insertsVideo() = runTest {
        val video = createTestVideo("v1", "Test Video")
        dao.addFavorite(video)

        val favorites = dao.getAllFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("v1", favorites[0].videoId)
        assertEquals("Test Video", favorites[0].title)
    }

    @Test
    fun addFavorite_withReplace_updatesExisting() = runTest {
        val video1 = createTestVideo("v1", "Original Title")
        val video2 = createTestVideo("v1", "Updated Title")

        dao.addFavorite(video1)
        dao.addFavorite(video2)

        val favorites = dao.getAllFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("Updated Title", favorites[0].title)
    }

    @Test
    fun removeFavorite_deletesVideo() = runTest {
        val video = createTestVideo("v1", "Test Video")
        dao.addFavorite(video)

        dao.removeFavorite("v1")

        val favorites = dao.getAllFavorites().first()
        assertTrue(favorites.isEmpty())
    }

    @Test
    fun clearAll_removesAllFavorites() = runTest {
        dao.addFavorite(createTestVideo("v1", "Video 1"))
        dao.addFavorite(createTestVideo("v2", "Video 2"))
        dao.addFavorite(createTestVideo("v3", "Video 3"))

        dao.clearAll()

        val favorites = dao.getAllFavorites().first()
        assertTrue(favorites.isEmpty())
    }

    // endregion

    // region isFavorite Queries

    @Test
    fun isFavorite_returnsTrueForExistingVideo() = runTest {
        dao.addFavorite(createTestVideo("v1", "Test Video"))

        val isFav = dao.isFavorite("v1").first()
        assertTrue(isFav)
    }

    @Test
    fun isFavorite_returnsFalseForNonExistingVideo() = runTest {
        val isFav = dao.isFavorite("nonexistent").first()
        assertFalse(isFav)
    }

    @Test
    fun isFavoriteOnce_returnsTrueForExistingVideo() = runTest {
        dao.addFavorite(createTestVideo("v1", "Test Video"))

        val isFav = dao.isFavoriteOnce("v1")
        assertTrue(isFav)
    }

    @Test
    fun isFavoriteOnce_returnsFalseForNonExistingVideo() = runTest {
        val isFav = dao.isFavoriteOnce("nonexistent")
        assertFalse(isFav)
    }

    // endregion

    // region @Transaction toggleFavorite Tests

    @Test
    fun toggleFavorite_addsWhenNotFavorite() = runTest {
        val video = createTestVideo("v1", "Test Video")

        val result = dao.toggleFavorite(video)

        assertTrue("Should return true when adding to favorites", result)
        assertTrue("Video should now be a favorite", dao.isFavoriteOnce("v1"))
    }

    @Test
    fun toggleFavorite_removesWhenAlreadyFavorite() = runTest {
        val video = createTestVideo("v1", "Test Video")
        dao.addFavorite(video)

        val result = dao.toggleFavorite(video)

        assertFalse("Should return false when removing from favorites", result)
        assertFalse("Video should no longer be a favorite", dao.isFavoriteOnce("v1"))
    }

    @Test
    fun toggleFavorite_doubleToggle_restoresState() = runTest {
        val video = createTestVideo("v1", "Test Video")

        // First toggle: add
        val result1 = dao.toggleFavorite(video)
        assertTrue("First toggle should add", result1)
        assertTrue(dao.isFavoriteOnce("v1"))

        // Second toggle: remove
        val result2 = dao.toggleFavorite(video)
        assertFalse("Second toggle should remove", result2)
        assertFalse(dao.isFavoriteOnce("v1"))

        // Third toggle: add again
        val result3 = dao.toggleFavorite(video)
        assertTrue("Third toggle should add again", result3)
        assertTrue(dao.isFavoriteOnce("v1"))
    }

    @Test
    fun toggleFavorite_atomicity_multipleVideos() = runTest {
        // Test that toggleFavorite works correctly for multiple different videos
        val video1 = createTestVideo("v1", "Video 1")
        val video2 = createTestVideo("v2", "Video 2")
        val video3 = createTestVideo("v3", "Video 3")

        // Add video1 and video2
        assertTrue(dao.toggleFavorite(video1))
        assertTrue(dao.toggleFavorite(video2))

        // Toggle video1 (remove), add video3
        assertFalse(dao.toggleFavorite(video1))
        assertTrue(dao.toggleFavorite(video3))

        // Verify final state
        assertFalse("Video 1 should not be favorite", dao.isFavoriteOnce("v1"))
        assertTrue("Video 2 should be favorite", dao.isFavoriteOnce("v2"))
        assertTrue("Video 3 should be favorite", dao.isFavoriteOnce("v3"))

        val favorites = dao.getAllFavorites().first()
        assertEquals(2, favorites.size)
    }

    // endregion

    // region Flow Reactivity Tests

    @Test
    fun getAllFavorites_orderedByMostRecent() = runTest {
        // Add in specific order with different timestamps
        dao.addFavorite(createTestVideo("v1", "First", addedAt = 1000L))
        dao.addFavorite(createTestVideo("v2", "Second", addedAt = 2000L))
        dao.addFavorite(createTestVideo("v3", "Third", addedAt = 3000L))

        val favorites = dao.getAllFavorites().first()
        assertEquals(3, favorites.size)
        assertEquals("v3", favorites[0].videoId) // Most recent first
        assertEquals("v2", favorites[1].videoId)
        assertEquals("v1", favorites[2].videoId) // Oldest last
    }

    @Test
    fun getFavoriteCount_returnsCorrectCount() = runTest {
        assertEquals(0, dao.getFavoriteCount().first())

        dao.addFavorite(createTestVideo("v1", "Video 1"))
        assertEquals(1, dao.getFavoriteCount().first())

        dao.addFavorite(createTestVideo("v2", "Video 2"))
        assertEquals(2, dao.getFavoriteCount().first())

        dao.removeFavorite("v1")
        assertEquals(1, dao.getFavoriteCount().first())

        dao.clearAll()
        assertEquals(0, dao.getFavoriteCount().first())
    }

    // endregion

    // region Helper Methods

    private fun createTestVideo(
        videoId: String,
        title: String,
        channelName: String = "Test Channel",
        thumbnailUrl: String? = "https://example.com/thumb.jpg",
        durationSeconds: Long = 180L,
        addedAt: Long = System.currentTimeMillis()
    ) = FavoriteVideo(
        videoId = videoId,
        title = title,
        channelName = channelName,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        addedAt = addedAt
    )

    // endregion
}
