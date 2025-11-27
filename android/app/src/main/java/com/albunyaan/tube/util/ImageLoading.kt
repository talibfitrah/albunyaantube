package com.albunyaan.tube.util

import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem

/**
 * Utility for loading images with Coil, providing content-type specific placeholders
 * and transformations.
 */
object ImageLoading {
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
