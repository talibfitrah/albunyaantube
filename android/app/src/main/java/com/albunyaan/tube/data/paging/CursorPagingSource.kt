package com.albunyaan.tube.data.paging

/**
 * Placeholder for a PagingSource that bridges backend cursor pagination with Paging 3.
 * Implementation notes:
 * - Use backend endpoint parameters (`cursor`, `limit`) and map Paging 3 `LoadParams` to backend cursors.
 * - When `LoadType.REFRESH`, pass null cursor to fetch first page; for `APPEND`, forward the `nextCursor` from previous response.
 * - Errors should surface descriptive messages for offline / HTTP failure scenarios.
 */
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService

class CursorPagingSource(
    private val service: ContentService,
    private val type: ContentType,
    private val filters: FilterState,
    private val pageSize: Int
) : PagingSource<Int, ContentItem>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ContentItem> {
        return try {
            val page = params.key ?: 0
            val response = service.fetchContent(type, page, pageSize, filters)
            LoadResult.Page(
                data = response.items,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (response.hasNext) page + 1 else null
            )
        } catch (t: Throwable) {
            LoadResult.Error(t)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ContentItem>): Int? {
        return state.anchorPosition?.let { position ->
            val page = state.closestPageToPosition(position)
            page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
        }
    }
}
