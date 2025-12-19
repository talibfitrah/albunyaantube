package com.albunyaan.tube.ui.favorites

import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for FavoritesViewModel.
 *
 * Tests verify:
 * - Initial state starts with empty list
 * - Favorites flow updates reflect repository changes
 * - Remove favorite calls repository correctly
 * - Clear all favorites calls repository correctly
 * - StateFlow properly transforms repository data
 *
 * Uses StandardTestDispatcher for explicit control over coroutine execution,
 * with advanceUntilIdle() to process pending coroutines deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeRepository: FakeFavoritesRepository
    private lateinit var viewModel: FavoritesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeFavoritesRepository()
        viewModel = FavoritesViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `favorites flow starts with empty list`() = testScope.runTest {
        // Start collecting to trigger stateIn's WhileSubscribed
        backgroundScope.launch { viewModel.favorites.collect { } }
        advanceUntilIdle()
        val favorites = viewModel.favorites.first()
        assertTrue("Initial favorites should be empty", favorites.isEmpty())
    }

    @Test
    fun `favorites flow reflects repository data`() = testScope.runTest {
        // Add data to repository first
        fakeRepository.addFavorite("v1", "Video 1", "Test Channel", "https://example.com/thumb.jpg", 180)
        fakeRepository.addFavorite("v2", "Video 2", "Test Channel", "https://example.com/thumb.jpg", 180)

        // Create ViewModel after data exists so stateIn collects the existing data
        val vm = FavoritesViewModel(fakeRepository)

        // Start collecting to trigger stateIn's WhileSubscribed
        backgroundScope.launch { vm.favorites.collect { } }
        advanceUntilIdle()

        val favorites = vm.favorites.value
        assertEquals("Should have 2 favorites", 2, favorites.size)
        assertTrue("Should contain video 1", favorites.any { it.videoId == "v1" })
        assertTrue("Should contain video 2", favorites.any { it.videoId == "v2" })
    }

    @Test
    fun `removeFavorite removes video from repository`() = testScope.runTest {
        // Setup: Add favorite first, then create ViewModel
        fakeRepository.addFavorite("v1", "Video 1", "Test Channel", null, 100)
        val vm = FavoritesViewModel(fakeRepository)

        // Start collecting to trigger stateIn's WhileSubscribed
        backgroundScope.launch { vm.favorites.collect { } }
        advanceUntilIdle()

        assertEquals("Should have 1 favorite before removal", 1, vm.favorites.value.size)

        vm.removeFavorite("v1")
        advanceUntilIdle()

        assertTrue("Favorites should be empty after removal", vm.favorites.value.isEmpty())
    }

    @Test
    fun `clearAllFavorites clears all from repository`() = testScope.runTest {
        // Setup: Add favorites first, then create ViewModel
        fakeRepository.addFavorite("v1", "Video 1", "Channel", null, 100)
        fakeRepository.addFavorite("v2", "Video 2", "Channel", null, 200)
        fakeRepository.addFavorite("v3", "Video 3", "Channel", null, 300)
        val vm = FavoritesViewModel(fakeRepository)

        // Start collecting to trigger stateIn's WhileSubscribed
        backgroundScope.launch { vm.favorites.collect { } }
        advanceUntilIdle()

        assertEquals("Should have 3 favorites before clear", 3, vm.favorites.value.size)

        vm.clearAllFavorites()
        advanceUntilIdle()

        assertTrue("Favorites should be empty after clear all", vm.favorites.value.isEmpty())
    }

    @Test
    fun `favorites flow updates reactively when repository changes`() = testScope.runTest {
        // Start collecting to trigger stateIn's WhileSubscribed
        backgroundScope.launch { viewModel.favorites.collect { } }
        advanceUntilIdle()
        assertTrue("Initial favorites should be empty", viewModel.favorites.first().isEmpty())

        // Add a favorite
        fakeRepository.addFavorite("v1", "Video 1", "Channel", null, 100)
        advanceUntilIdle()
        assertEquals("Should have 1 favorite after add", 1, viewModel.favorites.first().size)

        // Add another
        fakeRepository.addFavorite("v2", "Video 2", "Channel", null, 200)
        advanceUntilIdle()
        assertEquals("Should have 2 favorites after second add", 2, viewModel.favorites.first().size)

        // Remove one
        fakeRepository.removeFavorite("v1")
        advanceUntilIdle()
        assertEquals("Should have 1 favorite after removal", 1, viewModel.favorites.first().size)
        assertEquals("Remaining favorite should be v2", "v2", viewModel.favorites.first()[0].videoId)
    }

    /**
     * Fake FavoritesRepository for testing.
     * Uses MutableStateFlow to simulate reactive database updates.
     */
    private class FakeFavoritesRepository : FavoritesRepository {
        private val favoritesFlow = MutableStateFlow<List<FavoriteVideo>>(emptyList())

        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = favoritesFlow

        override fun isFavorite(videoId: String): Flow<Boolean> =
            favoritesFlow.map { list -> list.any { it.videoId == videoId } }

        override suspend fun isFavoriteOnce(videoId: String): Boolean =
            favoritesFlow.value.any { it.videoId == videoId }

        override suspend fun addFavorite(
            videoId: String,
            title: String,
            channelName: String,
            thumbnailUrl: String?,
            durationSeconds: Int
        ) {
            val current = favoritesFlow.value.toMutableList()
            // Remove existing if present (REPLACE behavior)
            current.removeAll { it.videoId == videoId }
            current.add(
                FavoriteVideo(
                    videoId = videoId,
                    title = title,
                    channelName = channelName,
                    thumbnailUrl = thumbnailUrl,
                    durationSeconds = durationSeconds
                )
            )
            favoritesFlow.value = current
        }

        override suspend fun removeFavorite(videoId: String) {
            favoritesFlow.value = favoritesFlow.value.filter { it.videoId != videoId }
        }

        override suspend fun toggleFavorite(
            videoId: String,
            title: String,
            channelName: String,
            thumbnailUrl: String?,
            durationSeconds: Int
        ): Boolean {
            val isFav = isFavoriteOnce(videoId)
            if (isFav) {
                removeFavorite(videoId)
            } else {
                addFavorite(videoId, title, channelName, thumbnailUrl, durationSeconds)
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
