package com.albunyaan.tube.util

/**
 * Utility for generating YouTube thumbnail URL fallback chains.
 *
 * YouTube provides multiple thumbnail sizes and formats. If the best quality thumbnail
 * fails to load (404, network error), we can try lower quality variants.
 *
 * Thumbnail quality hierarchy (highest to lowest):
 * - maxresdefault (1280x720) - Not always available
 * - sddefault (640x480)
 * - hqdefault (480x360)
 * - mqdefault (320x180)
 * - default (120x90) - Always available
 *
 * For Shorts specifically, YouTube may use different URL patterns. This helper
 * ensures we have fallback URLs regardless of the initial URL format.
 */
object ThumbnailUrlHelper {

    private const val TAG = "ThumbnailUrlHelper"

    // YouTube thumbnail base URL pattern
    private val VIDEO_ID_PATTERN = Regex("[a-zA-Z0-9_-]{11}")

    // Thumbnail quality variants in descending order
    private val THUMBNAIL_QUALITIES = listOf(
        "maxresdefault",
        "sddefault",
        "hqdefault",
        "mqdefault",
        "default"
    )

    // Shorts-specific patterns (9:16 aspect ratio thumbnails)
    private val SHORTS_THUMBNAIL_PATTERNS = listOf(
        // Shorts may use different CDN patterns
        "https://i.ytimg.com/vi/%s/oar2.jpg",      // Shorts-specific 9:16
        "https://i.ytimg.com/vi/%s/frame0.jpg",    // Frame grab
        "https://i.ytimg.com/vi/%s/hq2.jpg",       // High quality alternate
        "https://i.ytimg.com/vi/%s/hqdefault.jpg", // Standard HQ
        "https://i.ytimg.com/vi/%s/mqdefault.jpg", // Medium quality
        "https://i.ytimg.com/vi/%s/default.jpg"    // Fallback
    )

    /**
     * Generates a list of fallback thumbnail URLs for a YouTube video.
     *
     * @param primaryUrl The primary thumbnail URL (from NewPipe extraction)
     * @param videoId The YouTube video ID (11 characters)
     * @param isShort Whether this is a YouTube Short (uses 9:16 thumbnails)
     * @return List of URLs to try in order, with primary first
     */
    fun getFallbackUrls(
        primaryUrl: String?,
        videoId: String?,
        isShort: Boolean = false
    ): List<String> {
        val fallbacks = mutableListOf<String>()

        // Add primary URL first if valid
        primaryUrl?.takeIf { it.isNotBlank() }?.let { fallbacks.add(it) }

        // Extract video ID if not provided, validating against expected pattern
        val id = videoId?.takeIf { it.length == 11 && VIDEO_ID_PATTERN.matches(it) }
            ?: extractVideoId(primaryUrl)
            ?: return fallbacks // Can't generate fallbacks without ID

        // For Shorts, add Shorts-specific patterns first
        if (isShort) {
            SHORTS_THUMBNAIL_PATTERNS.forEach { pattern ->
                val url = String.format(pattern, id)
                if (url !in fallbacks) fallbacks.add(url)
            }
        }

        // Add standard YouTube thumbnail URLs
        THUMBNAIL_QUALITIES.forEach { quality ->
            // Try both jpg and webp variants
            // Note: YouTube serves webp from /vi_webp/ path, not /vi/ with .webp extension
            val jpgUrl = "https://i.ytimg.com/vi/$id/$quality.jpg"
            val webpUrl = "https://i.ytimg.com/vi_webp/$id/$quality.webp"

            // Prefer jpg (more compatible) over webp
            if (jpgUrl !in fallbacks) fallbacks.add(jpgUrl)
            if (webpUrl !in fallbacks) fallbacks.add(webpUrl)
        }

        return fallbacks
    }

    /**
     * Attempts to extract a YouTube video ID from a thumbnail URL.
     *
     * Common YouTube thumbnail URL patterns:
     * - https://i.ytimg.com/vi/VIDEO_ID/hqdefault.jpg
     * - https://i.ytimg.com/vi_webp/VIDEO_ID/hqdefault.webp
     * - https://img.youtube.com/vi/VIDEO_ID/0.jpg
     */
    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null

        // Pattern 1: /vi/VIDEO_ID/ or /vi_webp/VIDEO_ID/
        val viPattern = Regex("/vi(?:_webp)?/([a-zA-Z0-9_-]{11})/")
        viPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern 2: Check if the URL contains a valid video ID anywhere
        // (less reliable, used as fallback)
        VIDEO_ID_PATTERN.find(url)?.value?.let { candidate ->
            // Verify it looks like a video ID (not part of a longer string)
            val index = url.indexOf(candidate)
            val before = url.getOrNull(index - 1)
            val after = url.getOrNull(index + 11)
            if ((before == null || before == '/' || before == '=') &&
                (after == null || after == '/' || after == '&' || after == '.' || after == '?')
            ) {
                return candidate
            }
        }

        return null
    }

    /**
     * Checks if a URL appears to be a YouTube Shorts thumbnail.
     *
     * Shorts thumbnails may have different patterns or aspect ratios.
     */
    fun isShortsThumbnail(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        // Check for Shorts-specific patterns
        return url.contains("shorts") ||
                url.contains("oar2") ||
                url.contains("oar1") ||
                url.contains("frame0")
    }

    /**
     * Normalizes a YouTube video ID from various formats.
     *
     * Handles:
     * - Plain ID: "dQw4w9WgXcQ"
     * - Watch URL: "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
     * - Short URL: "https://youtu.be/dQw4w9WgXcQ"
     * - Shorts URL: "https://www.youtube.com/shorts/dQw4w9WgXcQ"
     */
    fun normalizeVideoId(input: String?): String? {
        if (input.isNullOrBlank()) return null

        // Already a valid ID
        if (input.length == 11 && VIDEO_ID_PATTERN.matches(input)) {
            return input
        }

        // Try to extract from URL patterns
        // Pattern: /watch?v=ID
        Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(input)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern: /shorts/ID or /embed/ID
        Regex("(?:shorts|embed)/([a-zA-Z0-9_-]{11})").find(input)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern: youtu.be/ID (handled separately)
        Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(input)?.groupValues?.getOrNull(1)?.let { return it }

        // Last resort: find any 11-character ID pattern
        return VIDEO_ID_PATTERN.find(input)?.value
    }
}
