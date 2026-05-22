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

    /**
     * Resolve a NewPipe uploader URL to its canonical UC... channel ID.
     *
     * Required because NewPipe returns uploader URLs in several forms
     * (`/channel/UC...`, `/@handle`, `/c/name`, `/user/name`). Only the
     * first is directly a UC id; the others must be resolved via a
     * NewPipe channel-page fetch. The registry HEAD lookup keys on UC
     * ids, so non-canonical strings silently 404 the gate.
     *
     * Implementations should:
     * - Return the UC id immediately for canonical URLs (no network).
     * - For non-canonical URLs, perform a NewPipe `ChannelInfo.getInfo`
     *   fetch off the header critical path.
     * - Cache successful resolutions (canonical ids are effectively
     *   immutable per channel).
     * - Return null on unresolvable URLs (the caller treats null as
     *   "channel link stays hidden").
     *
     * @param uploaderUrl Raw URL from NewPipe, may be null/blank.
     * @return Canonical UC channel ID, or null if unresolvable.
     */
    suspend fun resolveCanonicalChannelId(uploaderUrl: String?): String?
}
