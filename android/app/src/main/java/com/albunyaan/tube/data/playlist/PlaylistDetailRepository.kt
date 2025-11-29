package com.albunyaan.tube.data.playlist

import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.download.DownloadPolicy

/**
 * Repository for fetching playlist detail data directly from NewPipeExtractor.
 * This screen does not use backend API calls - all data comes from NewPipe.
 *
 * Albunyaan-specific metadata (category, excluded, downloadPolicy) is passed from
 * the caller since it comes from navigation arguments (backend data from content list).
 */
interface PlaylistDetailRepository {
    /**
     * Fetch playlist header information.
     *
     * @param playlistId YouTube playlist ID (e.g., "PL..." format)
     * @param forceRefresh If true, bypasses cache and fetches fresh data
     * @param category Category name from nav args (backend data)
     * @param excluded Whether playlist is excluded (from nav args)
     * @param downloadPolicy Download policy from nav args
     * @return Playlist header data
     */
    suspend fun getHeader(
        playlistId: String,
        forceRefresh: Boolean = false,
        category: String? = null,
        excluded: Boolean = false,
        downloadPolicy: DownloadPolicy = DownloadPolicy.ENABLED
    ): PlaylistHeader

    /**
     * Fetch videos from the playlist.
     *
     * @param playlistId YouTube playlist ID
     * @param page Pagination cursor, null for first page
     * @param itemOffset Starting position (1-based) for items in this page.
     *                   Used to assign correct playlist positions when paginating.
     * @return Paginated list of playlist items (videos)
     */
    suspend fun getItems(
        playlistId: String,
        page: Page?,
        itemOffset: Int = 1
    ): PlaylistPage<PlaylistItem>
}
