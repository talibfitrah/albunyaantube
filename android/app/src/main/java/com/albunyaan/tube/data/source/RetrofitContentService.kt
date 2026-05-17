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
import com.albunyaan.tube.data.model.mappers.toDomainContentItems
import com.albunyaan.tube.data.model.mappers.toDomain
import com.albunyaan.tube.data.source.api.ContentApi

class RetrofitContentService(
    private val api: ContentApi
) : ContentService {

    override suspend fun fetchContent(
        type: ContentType,
        cursor: String?,
        pageSize: Int,
        filters: FilterState,
        query: String?
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
            sort = filters.sortOption.toQueryValue(),
            query = query?.takeIf { it.isNotBlank() }
        )
        // Use mapper to convert generated DTOs to domain models
        val items = response.data.toDomainContentItems()
        return CursorResponse(items, CursorResponse.PageInfo(response.pageInfo.nextCursor))
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
        contentLimit: Int,
        category: String?
    ): HomeFeedResult {
        val response = api.fetchHomeFeed(cursor, categoryLimit, contentLimit, category)
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
        val topLevelCategories = response.filter { it.parentId == null }
        return topLevelCategories.map { cat ->
            val hasSubcategories = response.any { it.parentId == cat.id }
            cat.toDomain().copy(hasSubcategories = hasSubcategories)
        }
    }

    override suspend fun fetchSubcategories(parentId: String): List<Category> {
        val response = api.fetchCategories()
        return response
            .filter { it.parentId == parentId }
            .map { it.toDomain() }
    }

    override suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean {
        val response = when (type) {
            AvailabilityCheckType.CHANNEL -> api.checkChannelAvailable(youtubeId)
            AvailabilityCheckType.PLAYLIST -> api.checkPlaylistAvailable(youtubeId)
            AvailabilityCheckType.VIDEO -> api.checkVideoAvailable(youtubeId)
        }
        return when {
            response.isSuccessful -> true
            // 410 Gone: backend confirmed content was explicitly admin-archived/blocked.
            // Hard block for any type — never fail-open on a deliberate admin action.
            response.code() == 410 -> false
            // 404: content is not in the backend registry or not yet approved.
            // Fail-open for all types so NewPipe can resolve the item directly.
            // Videos not in the standalone registry (channel-sourced), channels navigated
            // from a playlist, and playlists fetched from a channel's Playlists tab all
            // return 404 but are valid YouTube content that NewPipe can serve.
            // The backend signals a deliberate admin block via 410, not 404.
            response.code() == 404 -> true
            else -> throw retrofit2.HttpException(response)
        }
    }
}
