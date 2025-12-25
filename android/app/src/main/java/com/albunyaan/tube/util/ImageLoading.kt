package com.albunyaan.tube.util

import android.widget.ImageView
import coil.load
import coil.request.CachePolicy
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem

/**
 * Utility for loading images with Coil, providing content-type specific placeholders
 * and transformations.
 *
 * All image loading uses aggressive caching to ensure thumbnails always display.
 *
 * For YouTube thumbnails (videos, shorts), uses [ThumbnailUrlHelper] to generate
 * fallback URLs when the primary URL fails.
 */
object ImageLoading {

    /**
     * Unique key for storing fallback request tags on ImageViews.
     * Using a keyed tag prevents conflicts with other code that may use the generic tag.
     */
    private val FALLBACK_REQUEST_TAG_KEY = R.id.thumbnail_fallback_request_tag
    /**
     * Extension function to load thumbnail images for content items.
     * Uses appropriate placeholders and transformations based on content type.
     */
    fun ImageView.loadThumbnail(
        item: ContentItem,
        crossfade: Boolean = true
    ) {
        val placeholder = getPlaceholderForItem(item)
        val url = getUrlForItem(item)

        // Handle null or blank URLs by showing placeholder immediately
        if (!isUrlValid(url)) {
            setImageResource(placeholder)
            return
        }

        load(url) {
            placeholder(placeholder)
            error(placeholder)
            if (crossfade) crossfade(true)
            if (shouldApplyCircleCrop(item)) {
                transformations(CircleCropTransformation())
            }
            // Aggressive caching for reliable thumbnail display
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
            networkCachePolicy(CachePolicy.ENABLED)
        }
    }

    /**
     * Extension function to load any thumbnail URL with reliable caching.
     * Use this for direct URL loading in adapters.
     */
    fun ImageView.loadThumbnailUrl(
        url: String?,
        placeholder: Int = R.drawable.thumbnail_placeholder,
        circleCrop: Boolean = false,
        crossfade: Boolean = true
    ) {
        if (url.isNullOrBlank()) {
            setImageResource(placeholder)
            return
        }

        load(url) {
            placeholder(placeholder)
            error(placeholder)
            if (crossfade) crossfade(true)
            if (circleCrop) {
                transformations(CircleCropTransformation())
            }
            // Aggressive caching for reliable thumbnail display
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
            networkCachePolicy(CachePolicy.ENABLED)
        }
    }

    /**
     * Extension function to load YouTube video/shorts thumbnails with automatic fallback.
     *
     * When the primary thumbnail URL fails (404, network error), this function
     * automatically tries fallback URLs in descending quality order until one succeeds.
     *
     * This is specifically designed for:
     * - YouTube Shorts (which may use different thumbnail patterns)
     * - Videos where maxresdefault doesn't exist
     * - Unreliable thumbnail CDN responses
     *
     * Uses request tagging to prevent race conditions in RecyclerView:
     * When a view is recycled and rebound to a new item, any in-flight requests
     * for the old item are ignored by checking the tag before applying results.
     *
     * @param primaryUrl The primary thumbnail URL from NewPipe extraction
     * @param videoId The YouTube video ID (11 characters) for generating fallbacks
     * @param isShort Whether this is a YouTube Short (uses 9:16 aspect ratio)
     * @param placeholder Placeholder drawable while loading
     * @param crossfade Whether to use crossfade animation
     */
    fun ImageView.loadYouTubeThumbnail(
        primaryUrl: String?,
        videoId: String?,
        isShort: Boolean = false,
        placeholder: Int = R.drawable.thumbnail_placeholder,
        crossfade: Boolean = true
    ) {
        val fallbackUrls = ThumbnailUrlHelper.getFallbackUrls(primaryUrl, videoId, isShort)

        if (fallbackUrls.isEmpty()) {
            setImageResource(placeholder)
            setTag(FALLBACK_REQUEST_TAG_KEY, null)
            return
        }

        // Tag the view with the first URL to detect stale callbacks in RecyclerView
        // Using a keyed tag prevents conflicts with other code that may use the generic tag
        val requestTag = fallbackUrls.first()
        setTag(FALLBACK_REQUEST_TAG_KEY, requestTag)

        // Start loading with fallback chain
        loadWithFallback(fallbackUrls, 0, placeholder, crossfade, requestTag)
    }

    /**
     * Internal function to load with fallback chain.
     * Recursively tries the next URL if the current one fails.
     *
     * @param requestTag The original request identifier to detect stale callbacks
     */
    private fun ImageView.loadWithFallback(
        urls: List<String>,
        index: Int,
        placeholder: Int,
        crossfade: Boolean,
        requestTag: String
    ) {
        if (index >= urls.size) {
            // All URLs failed, show placeholder (only if still the same request)
            if (getTag(FALLBACK_REQUEST_TAG_KEY) == requestTag) {
                setImageResource(placeholder)
            }
            return
        }

        val currentUrl = urls[index]
        val imageView = this

        load(currentUrl) {
            placeholder(placeholder)
            if (crossfade && index == 0) crossfade(true) // Only crossfade on first attempt
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
            networkCachePolicy(CachePolicy.ENABLED)

            listener(
                onSuccess = { _, _ ->
                    // Verify request is still current - ignore stale callbacks from recycled views
                    // If stale, the new request will overwrite this image when it completes
                    if (imageView.getTag(FALLBACK_REQUEST_TAG_KEY) != requestTag) {
                        return@listener
                    }
                },
                onError = { _, result ->
                    // Ignore stale callbacks from recycled views
                    if (imageView.getTag(FALLBACK_REQUEST_TAG_KEY) != requestTag) {
                        return@listener
                    }

                    // Try next fallback URL
                    if (index + 1 < urls.size) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "ImageLoading",
                                "Thumbnail failed (${result.throwable.message}), trying fallback ${index + 2}/${urls.size}"
                            )
                        }
                        imageView.loadWithFallback(urls, index + 1, placeholder, false, requestTag)
                    } else {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("ImageLoading", "All ${urls.size} thumbnail URLs failed")
                        }
                        // Only set placeholder if this request is still valid for this view
                        if (imageView.getTag(FALLBACK_REQUEST_TAG_KEY) == requestTag) {
                            imageView.setImageResource(placeholder)
                        }
                    }
                }
            )
        }
    }

    /**
     * Get the appropriate placeholder drawable resource for a content item.
     * Exposed for testing.
     */
    fun getPlaceholderForItem(item: ContentItem): Int {
        return when (item) {
            is ContentItem.Video, is ContentItem.Playlist -> R.drawable.home_thumbnail_bg
            is ContentItem.Channel -> R.drawable.home_channel_avatar_bg
        }
    }

    /**
     * Extract thumbnail URL from a content item.
     * Exposed for testing.
     */
    fun getUrlForItem(item: ContentItem): String? {
        return when (item) {
            is ContentItem.Video -> item.thumbnailUrl
            is ContentItem.Playlist -> item.thumbnailUrl
            is ContentItem.Channel -> item.thumbnailUrl
        }
    }

    /**
     * Check if a URL is valid (not null or blank).
     * Exposed for testing.
     */
    fun isUrlValid(url: String?): Boolean {
        return !url.isNullOrBlank()
    }

    /**
     * Determine if circle crop transformation should be applied.
     * Exposed for testing.
     */
    fun shouldApplyCircleCrop(item: ContentItem): Boolean {
        return item is ContentItem.Channel
    }
}
