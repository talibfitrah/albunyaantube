package com.albunyaan.tube.util

import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ImageLoading utility logic.
 *
 * Tests the actual helper functions exposed by ImageLoading object.
 * This ensures tests will catch regressions if the utility changes.
 *
 * Note: Actual Coil image loading (loadThumbnail extension) requires instrumentation tests
 * since it depends on Android ImageView and Coil's load() function.
 */
class ImageLoadingTest {

    @Test
    fun `getPlaceholderForItem returns thumbnail background for Video`() {
        val video = createVideo("1", "Test Video")
        val placeholder = ImageLoading.getPlaceholderForItem(video)
        assertEquals(R.drawable.home_thumbnail_bg, placeholder)
    }

    @Test
    fun `getPlaceholderForItem returns thumbnail background for Playlist`() {
        val playlist = createPlaylist("1", "Test Playlist")
        val placeholder = ImageLoading.getPlaceholderForItem(playlist)
        assertEquals(R.drawable.home_thumbnail_bg, placeholder)
    }

    @Test
    fun `getPlaceholderForItem returns channel avatar background for Channel`() {
        val channel = createChannel("1", "Test Channel")
        val placeholder = ImageLoading.getPlaceholderForItem(channel)
        assertEquals(R.drawable.home_channel_avatar_bg, placeholder)
    }

    @Test
    fun `getUrlForItem returns thumbnailUrl for Video`() {
        val video = createVideo("1", "Test Video", thumbnailUrl = "https://example.com/video.jpg")
        val url = ImageLoading.getUrlForItem(video)
        assertEquals("https://example.com/video.jpg", url)
    }

    @Test
    fun `getUrlForItem returns thumbnailUrl for Playlist`() {
        val playlist = createPlaylist("1", "Test Playlist", thumbnailUrl = "https://example.com/playlist.jpg")
        val url = ImageLoading.getUrlForItem(playlist)
        assertEquals("https://example.com/playlist.jpg", url)
    }

    @Test
    fun `getUrlForItem returns thumbnailUrl for Channel`() {
        val channel = createChannel("1", "Test Channel", thumbnailUrl = "https://example.com/channel.jpg")
        val url = ImageLoading.getUrlForItem(channel)
        assertEquals("https://example.com/channel.jpg", url)
    }

    @Test
    fun `getUrlForItem returns null when Video has no thumbnail`() {
        val video = createVideo("1", "Test Video", thumbnailUrl = null)
        val url = ImageLoading.getUrlForItem(video)
        assertEquals(null, url)
    }

    @Test
    fun `isUrlValid returns false for null URL`() {
        assertFalse(ImageLoading.isUrlValid(null))
    }

    @Test
    fun `isUrlValid returns false for blank URL`() {
        assertFalse(ImageLoading.isUrlValid(""))
        assertFalse(ImageLoading.isUrlValid("   "))
    }

    @Test
    fun `isUrlValid returns true for valid URL`() {
        assertTrue(ImageLoading.isUrlValid("https://example.com/image.jpg"))
    }

    @Test
    fun `shouldApplyCircleCrop returns true for Channel`() {
        val channel = createChannel("1", "Test Channel")
        assertTrue(ImageLoading.shouldApplyCircleCrop(channel))
    }

    @Test
    fun `shouldApplyCircleCrop returns false for Video`() {
        val video = createVideo("1", "Test Video")
        assertFalse(ImageLoading.shouldApplyCircleCrop(video))
    }

    @Test
    fun `shouldApplyCircleCrop returns false for Playlist`() {
        val playlist = createPlaylist("1", "Test Playlist")
        assertFalse(ImageLoading.shouldApplyCircleCrop(playlist))
    }

    // Test data helpers
    private fun createVideo(id: String, title: String, thumbnailUrl: String? = null) = ContentItem.Video(
        id = id,
        title = title,
        category = "Test",
        durationSeconds = 10,
        uploadedDaysAgo = 1,
        description = "Test video",
        thumbnailUrl = thumbnailUrl
    )

    private fun createPlaylist(id: String, title: String, thumbnailUrl: String? = null) = ContentItem.Playlist(
        id = id,
        title = title,
        category = "Test",
        itemCount = 10,
        description = "Test playlist",
        thumbnailUrl = thumbnailUrl
    )

    private fun createChannel(id: String, name: String, thumbnailUrl: String? = null) = ContentItem.Channel(
        id = id,
        name = name,
        category = "Test",
        subscribers = 1000,
        description = "Test channel",
        thumbnailUrl = thumbnailUrl
    )
}
