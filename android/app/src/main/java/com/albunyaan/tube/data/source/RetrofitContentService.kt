package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.SortOption
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.model.HomeFeedResult
import com.albunyaan.tube.data.model.mappers.toDomainContentItems
import com.albunyaan.tube.data.model.mappers.toDomain
import com.albunyaan.tube.data.source.api.ContentApi
import kotlinx.coroutines.delay
import retrofit2.HttpException

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
        val response = retryOnceWhileIndexBuilds {
            api.fetchContent(
                type = typeParam,
                cursor = cursor,
                limit = pageSize,
                category = filters.category,
                length = filters.videoLength.toQueryValue(),
                date = filters.publishedDate.toQueryValue(),
                sort = filters.sortOption.toQueryValue(),
                query = query?.takeIf { it.isNotBlank() }
            )
        }
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

    override suspend fun search(query: String, type: String?, limit: Int): ContentSearchResult {
        val response = retryOnceWhileIndexBuilds {
            // Response<T> stops Retrofit throwing on non-2xx; keep the old failure behaviour.
            api.search(query, type, limit).also { if (!it.isSuccessful) throw HttpException(it) }
        }
        return ContentSearchResult(
            items = checkNotNull(response.body()) { "search: HTTP ${response.code()} with no body" }.toDomainContentItems(),
            match = SearchMatch.fromHeader(response.headers()["X-Search-Match"])
        )
    }

    /**
     * A restarted server answers 503 + Retry-After while its search index builds. Wait that long (≤ 10 s; the wait is
     * cancelled with the caller, e.g. by the next keystroke) and try once more; any other failure, or a second 503,
     * goes to the caller.
     */
    private suspend fun <T> retryOnceWhileIndexBuilds(call: suspend () -> T): T = try {
        call()
    } catch (e: HttpException) {
        val seconds = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
        if (e.code() != 503 || seconds == null || seconds !in 0..10) throw e
        delay(seconds * 1_000)
        call()
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
            // 410 Gone: explicitly admin-blocked — hard stop, never fail-open.
            response.code() == 410 -> false
            // 404: not in registry yet; fail-open so NewPipe can resolve directly.
            response.code() == 404 -> true
            else -> throw retrofit2.HttpException(response)
        }
    }

    override suspend fun isInApprovedRegistry(type: AvailabilityCheckType, youtubeId: String): Boolean {
        return try {
            val response = when (type) {
                AvailabilityCheckType.CHANNEL -> api.checkChannelAvailable(youtubeId)
                AvailabilityCheckType.PLAYLIST -> api.checkPlaylistAvailable(youtubeId)
                AvailabilityCheckType.VIDEO -> api.checkVideoAvailable(youtubeId)
            }
            response.isSuccessful
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Coroutine scope cancellation (ViewModel cleared / user navigated
            // away) must propagate so structured concurrency works. The broad
            // catch below would otherwise swallow it and the caller would
            // proceed to write into a cleared ViewModel.
            throw ce
        } catch (e: Exception) {
            false
        }
    }
}
