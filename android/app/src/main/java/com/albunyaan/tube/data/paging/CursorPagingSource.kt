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

/** Placeholder PagingSource implementation returning empty data. */
class CursorPagingSource : PagingSource<Int, Any>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Any> {
        return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
    }

    override fun getRefreshKey(state: PagingState<Int, Any>): Int? = null
}
