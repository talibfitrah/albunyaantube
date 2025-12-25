package com.albunyaan.tube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailUrlHelperTest {

    @Test
    fun `extractVideoId returns null for null input`() {
        assertNull(ThumbnailUrlHelper.extractVideoId(null))
    }

    @Test
    fun `extractVideoId returns null for empty string`() {
        assertNull(ThumbnailUrlHelper.extractVideoId(""))
    }

    @Test
    fun `extractVideoId extracts ID from standard thumbnail URL`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        assertEquals("dQw4w9WgXcQ", ThumbnailUrlHelper.extractVideoId(url))
    }

    @Test
    fun `extractVideoId extracts ID from webp thumbnail URL`() {
        val url = "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/mqdefault.webp"
        assertEquals("dQw4w9WgXcQ", ThumbnailUrlHelper.extractVideoId(url))
    }

    @Test
    fun `extractVideoId extracts ID from maxresdefault URL`() {
        val url = "https://i.ytimg.com/vi/ABC123def45/maxresdefault.jpg"
        assertEquals("ABC123def45", ThumbnailUrlHelper.extractVideoId(url))
    }

    @Test
    fun `normalizeVideoId returns valid 11-char ID as-is`() {
        assertEquals("dQw4w9WgXcQ", ThumbnailUrlHelper.normalizeVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun `normalizeVideoId extracts ID from watch URL`() {
        assertEquals("dQw4w9WgXcQ", ThumbnailUrlHelper.normalizeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `normalizeVideoId extracts ID from short URL`() {
        assertEquals("dQw4w9WgXcQ", ThumbnailUrlHelper.normalizeVideoId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `normalizeVideoId extracts ID from shorts URL`() {
        assertEquals("dQw4w9WgXcQ", ThumbnailUrlHelper.normalizeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `normalizeVideoId extracts ID from embed URL`() {
        assertEquals("dQw4w9WgXcQ", ThumbnailUrlHelper.normalizeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun `normalizeVideoId returns null for invalid input`() {
        assertNull(ThumbnailUrlHelper.normalizeVideoId(null))
        assertNull(ThumbnailUrlHelper.normalizeVideoId(""))
        assertNull(ThumbnailUrlHelper.normalizeVideoId("   "))
    }

    @Test
    fun `isShortsThumbnail detects shorts patterns`() {
        assertTrue(ThumbnailUrlHelper.isShortsThumbnail("https://i.ytimg.com/vi/abc123/oar2.jpg"))
        assertTrue(ThumbnailUrlHelper.isShortsThumbnail("https://i.ytimg.com/vi/abc123/frame0.jpg"))
        assertTrue(ThumbnailUrlHelper.isShortsThumbnail("https://youtube.com/shorts/abc123"))
    }

    @Test
    fun `isShortsThumbnail returns false for regular thumbnails`() {
        assertFalse(ThumbnailUrlHelper.isShortsThumbnail("https://i.ytimg.com/vi/abc123/hqdefault.jpg"))
        assertFalse(ThumbnailUrlHelper.isShortsThumbnail("https://i.ytimg.com/vi/abc123/maxresdefault.jpg"))
        assertFalse(ThumbnailUrlHelper.isShortsThumbnail(null))
        assertFalse(ThumbnailUrlHelper.isShortsThumbnail(""))
    }

    @Test
    fun `getFallbackUrls returns empty list when no primary URL and no video ID`() {
        val urls = ThumbnailUrlHelper.getFallbackUrls(null, null)
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `getFallbackUrls returns primary URL first`() {
        val primaryUrl = "https://custom.cdn.com/thumb.jpg"
        val urls = ThumbnailUrlHelper.getFallbackUrls(primaryUrl, "dQw4w9WgXcQ")
        assertEquals(primaryUrl, urls.first())
    }

    @Test
    fun `getFallbackUrls includes standard quality variants`() {
        val urls = ThumbnailUrlHelper.getFallbackUrls(null, "dQw4w9WgXcQ")

        // Should include all standard variants
        assertTrue(urls.any { it.contains("maxresdefault") })
        assertTrue(urls.any { it.contains("sddefault") })
        assertTrue(urls.any { it.contains("hqdefault") })
        assertTrue(urls.any { it.contains("mqdefault") })
        assertTrue(urls.any { it.contains("default.jpg") })
    }

    @Test
    fun `getFallbackUrls includes shorts-specific patterns for shorts`() {
        val urls = ThumbnailUrlHelper.getFallbackUrls(null, "dQw4w9WgXcQ", isShort = true)

        // Should include Shorts-specific patterns first
        assertTrue(urls.any { it.contains("oar2.jpg") })
        assertTrue(urls.any { it.contains("frame0.jpg") })
    }

    @Test
    fun `getFallbackUrls does not duplicate primary URL in fallbacks`() {
        val primaryUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        val urls = ThumbnailUrlHelper.getFallbackUrls(primaryUrl, "dQw4w9WgXcQ")

        // Primary URL should only appear once
        assertEquals(1, urls.count { it == primaryUrl })
    }

    @Test
    fun `getFallbackUrls includes both jpg and webp variants`() {
        val urls = ThumbnailUrlHelper.getFallbackUrls(null, "dQw4w9WgXcQ")

        assertTrue(urls.any { it.endsWith(".jpg") })
        assertTrue(urls.any { it.endsWith(".webp") })
        // Verify webp uses correct /vi_webp/ path
        assertTrue(urls.any { it.contains("/vi_webp/") && it.endsWith(".webp") })
    }

    @Test
    fun `getFallbackUrls extracts video ID from primary URL when ID not provided`() {
        val primaryUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/custom.jpg"
        val urls = ThumbnailUrlHelper.getFallbackUrls(primaryUrl, null)

        // Should have generated fallbacks using extracted ID
        assertTrue(urls.size > 1)
        assertTrue(urls.any { it.contains("hqdefault") })
    }

    @Test
    fun `getFallbackUrls returns only primary URL when ID cannot be extracted`() {
        val primaryUrl = "https://custom.cdn.com/image.jpg"
        val urls = ThumbnailUrlHelper.getFallbackUrls(primaryUrl, null)

        // Only primary URL since we can't generate fallbacks
        assertEquals(1, urls.size)
        assertEquals(primaryUrl, urls.first())
    }
}
