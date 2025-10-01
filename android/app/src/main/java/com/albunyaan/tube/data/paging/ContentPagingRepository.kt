package com.albunyaan.tube.data.paging

/**
 * Defines the Paging 3 contract for the primary Android tabs. The concrete implementation will:
 * - Provide PagingData streams for Home/Channels/Playlists/Videos using a shared `CursorPagingSource`.
 * - Expose refresh triggers when global filters (category, search query) change.
 * - Bridge backend cursor pagination with Paging 3 `RemoteMediator` to store cached pages in Room.
 */
interface ContentPagingRepository {
    fun homeStream(): Any // TODO: replace with Flow<PagingData<HomeItem>>
    fun channelsStream(category: String?): Any
    fun playlistsStream(category: String?): Any
    fun videosStream(query: String?): Any
}
