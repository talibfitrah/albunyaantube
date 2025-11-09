package com.albunyaan.tube.data.paging

import androidx.paging.Pager
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType

interface ContentPagingRepository {
    fun pager(type: ContentType, filters: FilterState): Pager<String, ContentItem>
}

