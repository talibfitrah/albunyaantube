package com.albunyaan.tube.data.paging

/**
 * Defines the Paging 3 contract for the primary Android tabs. The concrete implementation will:
 * - Provide PagingData streams for Home/Channels/Playlists/Videos using a shared `CursorPagingSource`.
 * - Expose refresh triggers when global filters (category, search query) change.
 * - Bridge backend cursor pagination with Paging 3 `RemoteMediator` to store cached pages in Room.
 */
import androidx.paging.Pager
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem

interface ContentPagingRepository {
    fun homePager(filters: FilterState): Pager<Int, ContentItem>
    fun channelsPager(filters: FilterState): Pager<Int, ContentItem>
    fun playlistsPager(filters: FilterState): Pager<Int, ContentItem>
    fun videosPager(filters: FilterState): Pager<Int, ContentItem>
}
