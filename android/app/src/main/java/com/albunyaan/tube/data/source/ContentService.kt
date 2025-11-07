package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse

interface ContentService {
    suspend fun fetchContent(
        type: ContentType,
        cursor: String?,
        pageSize: Int,
        filters: FilterState
    ): CursorResponse

    suspend fun search(query: String, type: String? = null, limit: Int = 20): List<ContentItem>

    suspend fun fetchCategories(): List<Category>

    suspend fun fetchSubcategories(parentId: String): List<Category>
}
