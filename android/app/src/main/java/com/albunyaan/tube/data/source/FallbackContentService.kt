package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse

class FallbackContentService(
    private val primary: ContentService,
    private val fallback: ContentService
) : ContentService {
    override suspend fun fetchContent(
        type: ContentType,
        cursor: String?,
        pageSize: Int,
        filters: FilterState
    ): CursorResponse = try {
        primary.fetchContent(type, cursor, pageSize, filters)
    } catch (_: Throwable) {
        fallback.fetchContent(type, cursor, pageSize, filters)
    }

    override suspend fun search(query: String, type: String?, limit: Int): List<ContentItem> = try {
        primary.search(query, type, limit)
    } catch (_: Throwable) {
        fallback.search(query, type, limit)
    }
}
