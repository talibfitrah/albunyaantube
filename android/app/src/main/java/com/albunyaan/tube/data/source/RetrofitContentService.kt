package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.SortOption
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.extractor.MetadataHydrator
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.ContentDto
import kotlinx.coroutines.CancellationException

class RetrofitContentService(
    private val api: ContentApi,
    private val metadataHydrator: MetadataHydrator
) : ContentService {

    override suspend fun fetchContent(
        type: ContentType,
        cursor: String?,
        pageSize: Int,
        filters: FilterState
    ): CursorResponse {
        val response = api.fetchContent(
            type = type.name,
            cursor = cursor,
            limit = pageSize,
            category = filters.category,
            length = filters.videoLength.toQueryValue(),
            date = filters.publishedDate.toQueryValue(),
            sort = filters.sortOption.toQueryValue()
        )
        val baseItems = response.data.mapNotNull { it.toModel() }
        val hydratedItems = runCatching { metadataHydrator.hydrate(type, baseItems) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { baseItems }
        return CursorResponse(hydratedItems, response.pageInfo.nextCursor)
    }

    private fun ContentDto.toModel(): ContentItem? {
        return when (type?.uppercase()) {
            "VIDEO" -> ContentItem.Video(
                id = id,
                title = title.orEmpty(),
                category = category.orEmpty(),
                durationMinutes = durationMinutes ?: 0,
                uploadedDaysAgo = uploadedDaysAgo ?: 0,
                description = description.orEmpty()
            )
            "CHANNEL" -> ContentItem.Channel(
                id = id,
                name = name ?: title.orEmpty(),
                category = category.orEmpty(),
                subscribers = subscribers ?: 0
            )
            "PLAYLIST" -> ContentItem.Playlist(
                id = id,
                title = title.orEmpty(),
                category = category.orEmpty(),
                itemCount = itemCount ?: 0
            )
            else -> null
        }
    }

    private fun VideoLength.toQueryValue(): String? = when (this) {
        VideoLength.ANY -> null
        VideoLength.UNDER_FOUR_MIN -> "SHORT"
        VideoLength.FOUR_TO_TWENTY_MIN -> "MEDIUM"
        VideoLength.OVER_TWENTY_MIN -> "LONG"
    }

    private fun PublishedDate.toQueryValue(): String? = when (this) {
        PublishedDate.ANY -> null
        PublishedDate.LAST_24_HOURS -> "LAST_24_HOURS"
        PublishedDate.LAST_7_DAYS -> "LAST_7_DAYS"
        PublishedDate.LAST_30_DAYS -> "LAST_30_DAYS"
    }

    private fun SortOption.toQueryValue(): String? = when (this) {
        SortOption.DEFAULT -> null
        SortOption.MOST_POPULAR -> "POPULAR"
        SortOption.NEWEST -> "NEWEST"
    }

    override suspend fun search(query: String, type: String?, limit: Int): List<ContentItem> {
        val response = api.search(query, type, limit)
        return response.results.mapNotNull { it.toModel() }
    }

    override suspend fun fetchCategories(): List<com.albunyaan.tube.ui.categories.Category> {
        val response = api.fetchCategories()
        // Filter to only top-level categories (those without parentId)
        val topLevelCategories = response.filter { it.parentId == null }
        return topLevelCategories.map { categoryDto ->
            // Check if this category has any subcategories
            val hasSubcategories = response.any { it.parentId == categoryDto.id }
            com.albunyaan.tube.ui.categories.Category(
                id = categoryDto.id,
                name = categoryDto.name,
                hasSubcategories = hasSubcategories
            )
        }
    }

    override suspend fun fetchSubcategories(parentId: String): List<com.albunyaan.tube.ui.categories.Category> {
        val response = api.fetchCategories()
        // Filter to only categories with matching parentId
        return response
            .filter { it.parentId == parentId }
            .map { categoryDto ->
                com.albunyaan.tube.ui.categories.Category(
                    id = categoryDto.id,
                    name = categoryDto.name,
                    hasSubcategories = false // Subcategories don't have further children in current design
                )
            }
    }
}
