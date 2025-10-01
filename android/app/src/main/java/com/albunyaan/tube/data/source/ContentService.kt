package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse

interface ContentService {
    suspend fun fetchContent(
        type: ContentType,
        page: Int,
        pageSize: Int,
        filters: FilterState
    ): CursorResponse
}
