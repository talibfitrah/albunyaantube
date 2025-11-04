package com.albunyaan.tube.data.paging

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
) : PagingSource<String, ContentItem>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, ContentItem> {
        return try {
            val cursor = params.key
            val response = service.fetchContent(type, cursor, pageSize, filters)
            LoadResult.Page(
                data = response.data,
                prevKey = null,
                nextKey = response.pageInfo?.nextCursor
            )
        } catch (t: Throwable) {
            LoadResult.Error(t)
        }
    }

    override fun getRefreshKey(state: PagingState<String, ContentItem>): String? = null
}
