package com.albunyaan.tube.data.paging

/**
 * Defines the Paging 3 contract for the primary Android tabs. The concrete implementation will:
 * - Provide PagingData streams for Home/Channels/Playlists/Videos using a shared `CursorPagingSource`.
 * - Expose refresh triggers when global filters (category, search query) change.
 * - Bridge backend cursor pagination with Paging 3 `RemoteMediator` to store cached pages in Room.
 */
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

interface ContentPagingRepository {
    fun homeStream(): Flow<PagingData<Any>>
    fun channelsStream(category: String?): Flow<PagingData<Any>>
    fun playlistsStream(category: String?): Flow<PagingData<Any>>
    fun videosStream(query: String?): Flow<PagingData<Any>>
}
