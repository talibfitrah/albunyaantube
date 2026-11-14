package com.albunyaan.tube.data.channel

/**
 * Repository for fetching channel detail data directly from NewPipeExtractor.
 * This screen does not use backend API calls - all data comes from NewPipe.
 */
interface ChannelDetailRepository {
    /**
     * Fetch channel header information including metadata for About tab.
     *
     * @param channelId YouTube channel ID (e.g., "UC..." format)
     * @param forceRefresh If true, bypasses cache and fetches fresh data
     * @return Channel header data
     */
    suspend fun getChannelHeader(channelId: String, forceRefresh: Boolean = false): ChannelHeader

    /**
     * Fetch videos from the channel's uploads playlist (UU). The Videos tab's paged source is [getVideosViaChannelTab]
     * (09675e2a); it loads this only when the channel tab's first page gives nothing, then pages on here with this
     * playlist's own tokens.
     *
     * @param channelId YouTube channel ID
     * @param page Pagination cursor, null for first page
     * @return Paginated list of videos
     */
    suspend fun getVideos(channelId: String, page: Page?): ChannelPage<ChannelVideo>

    /**
     * Fetch the channel's Videos tab (~30 items a page). The Videos tab's own path: page 1 with [page] null, then
     * its continuation, which pages to the end (09675e2a). Its tokens are valid only here, never in [getVideos].
     */
    suspend fun getVideosViaChannelTab(channelId: String, page: Page? = null): ChannelPage<ChannelVideo>

    /**
     * Fetch live streams from the channel's Live tab.
     *
     * @param channelId YouTube channel ID
     * @param page Pagination cursor, null for first page
     * @return Paginated list of live/upcoming streams
     */
    suspend fun getLiveStreams(channelId: String, page: Page?): ChannelPage<ChannelLiveStream>

    /**
     * Fetch shorts from the channel's Shorts tab.
     *
     * @param channelId YouTube channel ID
     * @param page Pagination cursor, null for first page
     * @return Paginated list of shorts
     */
    suspend fun getShorts(channelId: String, page: Page?): ChannelPage<ChannelShort>

    /**
     * Fetch playlists from the channel's Playlists tab.
     *
     * @param channelId YouTube channel ID
     * @param page Pagination cursor, null for first page
     * @return Paginated list of playlists
     */
    suspend fun getPlaylists(channelId: String, page: Page?): ChannelPage<ChannelPlaylist>

    /**
     * Fetch full about information for the channel.
     * This may include additional data not in the header (location, join date, etc.)
     *
     * @param channelId YouTube channel ID
     * @param forceRefresh If true, bypasses cache and fetches fresh data
     * @return Full channel header with about information
     */
    suspend fun getAbout(channelId: String, forceRefresh: Boolean = false): ChannelHeader
}
