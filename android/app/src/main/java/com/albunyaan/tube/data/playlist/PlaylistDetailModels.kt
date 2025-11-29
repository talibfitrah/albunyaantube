package com.albunyaan.tube.data.playlist

import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.download.DownloadPolicy

/**
 * Playlist header information for the detail screen.
 * Maps NewPipe PlaylistInfo to UI-friendly data.
 *
 * Contains metadata passed from navigation arguments (category, excluded, downloadPolicy)
 * combined with live data from NewPipe extraction.
 */
data class PlaylistHeader(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val bannerUrl: String?,
    val channelId: String?,
    val channelName: String?,
    val itemCount: Long?,
    val totalDurationSeconds: Long?,
    val description: String?,
    val tags: List<String>,
    // Albunyaan metadata passed via nav args
    val category: String?,
    val excluded: Boolean,
    val downloadPolicy: DownloadPolicy
)

/**
 * Represents a video item within a playlist, preserving order.
 */
data class PlaylistItem(
    /** 1-based position in the playlist */
    val position: Int,
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val viewCount: Long?,
    val publishedTime: String?,
    val channelId: String?,
    val channelName: String?
)

/**
 * Generic paginated page response for playlist content.
 *
 * @param T The type of items in this page
 * @property items The content items for this page
 * @property nextPage Cursor/token for fetching the next page, null if no more pages
 * @property nextItemOffset The starting position (1-based) for items in the next page.
 *                          Used to assign correct positions when paginating.
 * @property fromCache Whether this data came from cache
 */
data class PlaylistPage<T>(
    val items: List<T>,
    val nextPage: Page?,
    val nextItemOffset: Int = 1,
    val fromCache: Boolean = false
)
