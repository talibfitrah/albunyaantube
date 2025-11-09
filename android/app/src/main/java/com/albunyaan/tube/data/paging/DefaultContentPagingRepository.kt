package com.albunyaan.tube.data.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService

private const val PAGE_SIZE = 20

class DefaultContentPagingRepository(
    private val service: ContentService
) : ContentPagingRepository {

    override fun pager(type: ContentType, filters: FilterState): Pager<String, ContentItem> {
        return Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            CursorPagingSource(service, type, filters, PAGE_SIZE)
        }
    }
}

