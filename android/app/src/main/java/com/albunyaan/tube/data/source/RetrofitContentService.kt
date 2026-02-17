package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.SortOption
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.model.HomeFeedResult
import com.albunyaan.tube.data.model.mappers.toDomain
import com.albunyaan.tube.data.model.mappers.toDomainContentItems
import com.albunyaan.tube.data.extractor.MetadataHydrator
import com.albunyaan.tube.data.source.api.ContentApi
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
        // API Contract: GET /api/v1/content
        // - When type parameter is omitted (null), the API returns mixed content
        //   (channels, playlists, videos) suitable for home/featured sections.
        // - Category filter applies regardless of type.
        // See: docs/architecture/api-specification.yaml for the full contract.
        val typeParam = if (type == ContentType.ALL) null else type.name
        val response = api.fetchContent(
            type = typeParam,
            cursor = cursor,
            limit = pageSize,
            category = filters.category,
            length = filters.videoLength.toQueryValue(),
            date = filters.publishedDate.toQueryValue(),
            sort = filters.sortOption.toQueryValue()
        )
        // Use mapper to convert generated DTOs to domain models
        val baseItems = response.data.toDomainContentItems()
        val hydratedItems = runCatching { metadataHydrator.hydrate(type, baseItems) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { baseItems }
        return CursorResponse(hydratedItems, CursorResponse.PageInfo(response.pageInfo.nextCursor))
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
        SortOption.MOST_POPULAR -> "MOST_POPULAR"
        SortOption.NEWEST -> "NEWEST"
    }

    override suspend fun fetchHomeFeed(
        cursor: String?,
        categoryLimit: Int,
        contentLimit: Int
    ): HomeFeedResult {
        val response = api.fetchHomeFeed(cursor, categoryLimit, contentLimit)
        val sections = response.data.map { it.toDomain() }
        return HomeFeedResult(
            sections = sections,
            nextCursor = response.pageInfo.nextCursor,
            hasMore = response.pageInfo.hasNext
        )
    }

    override suspend fun search(query: String, type: String?, limit: Int): List<ContentItem> {
        val dtos = api.search(query, type, limit)
        // Use mapper to convert generated DTOs to domain models
        return dtos.toDomainContentItems()
    }

    override suspend fun fetchCategories(): List<Category> {
        val response = api.fetchCategories()
        // Filter to only top-level categories (those without parentId)
        val topLevelCategories = response.filter { it.parentCategoryId == null }
        return topLevelCategories.map { apiCategory ->
            // Check if this category has any subcategories
            val hasSubcategories = response.any { it.parentCategoryId == apiCategory.id }
            // Use mapper and update hasSubcategories (which is computed)
            apiCategory.toDomain().copy(hasSubcategories = hasSubcategories)
        }
    }

    override suspend fun fetchSubcategories(parentId: String): List<Category> {
        val response = api.fetchCategories()
        // Filter to only categories with matching parentId and use mapper
        return response
            .filter { it.parentCategoryId == parentId }
            .map { it.toDomain() }
    }
}
