package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.SortOption
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import kotlin.math.min

class FakeContentService : ContentService {

    private val categories = listOf("Knowledge", "Kids", "Quran", "Stories")

    private val videos = List(80) { index ->
        val category = categories[index % categories.size]
        val duration = when (index % 3) {
            0 -> 3
            1 -> 12
            else -> 28
        }
        val daysAgo = (index % 20) + 1
        ContentItem.Video(
            id = "video-$index",
            title = "Daily inspiration #$index",
            category = category,
            durationSeconds = duration,
            uploadedDaysAgo = daysAgo,
            description = "Curated video about $category (uploaded $daysAgo days ago)."
        )
    }

    private val channels = List(40) { index ->
        val category = categories[index % categories.size]
        ContentItem.Channel(
            id = "channel-$index",
            name = "Channel $category #$index",
            category = category,
            subscribers = 10_000 + index * 250
        )
    }

    private val playlists = List(30) { index ->
        val category = categories[index % categories.size]
        ContentItem.Playlist(
            id = "playlist-$index",
            title = "$category playlist #$index",
            category = category,
            itemCount = 12 + index
        )
    }

    override suspend fun fetchContent(
        type: ContentType,
        cursor: String?,
        pageSize: Int,
        filters: FilterState
    ): CursorResponse {
        val sourceItems: List<ContentItem> = when (type) {
            ContentType.HOME, ContentType.VIDEOS -> videos
            ContentType.CHANNELS -> channels
            ContentType.PLAYLISTS -> playlists
        }

        val filtered = sourceItems.filter { item ->
            when (item) {
                is ContentItem.Video -> filters.matchesVideo(item)
                is ContentItem.Channel -> filters.category?.let { item.category == it } ?: true
                is ContentItem.Playlist -> filters.category?.let { item.category == it } ?: true
            }
        }.sortByOption(filters.sortOption)

        val pageIndex = cursor?.toIntOrNull() ?: 0
        val fromIndex = pageIndex * pageSize
        val toIndex = min(fromIndex + pageSize, filtered.size)
        val pageItems = if (fromIndex >= filtered.size) emptyList() else filtered.subList(fromIndex, toIndex)
        val nextCursor = if (toIndex < filtered.size) (pageIndex + 1).toString() else null

        return CursorResponse(pageItems, CursorResponse.PageInfo(nextCursor))
    }

    private fun FilterState.matchesVideo(video: ContentItem.Video): Boolean {
        val categoryMatch = category?.let { video.category == it } ?: true
        // Duration thresholds in seconds: 4 min = 240s, 20 min = 1200s
        val lengthMatch = when (videoLength) {
            VideoLength.ANY -> true
            VideoLength.UNDER_FOUR_MIN -> video.durationSeconds < 240
            VideoLength.FOUR_TO_TWENTY_MIN -> video.durationSeconds in 240..1200
            VideoLength.OVER_TWENTY_MIN -> video.durationSeconds > 1200
        }
        val publishedMatch = when (publishedDate) {
            PublishedDate.ANY -> true
            PublishedDate.LAST_24_HOURS -> video.uploadedDaysAgo <= 1
            PublishedDate.LAST_7_DAYS -> video.uploadedDaysAgo <= 7
            PublishedDate.LAST_30_DAYS -> video.uploadedDaysAgo <= 30
        }
        return categoryMatch && lengthMatch && publishedMatch
    }

    private fun List<ContentItem>.sortByOption(option: SortOption): List<ContentItem> = when (option) {
        SortOption.DEFAULT -> this
        SortOption.NEWEST -> sortedBy {
            when (it) {
                is ContentItem.Video -> it.uploadedDaysAgo
                else -> 0
            }
        }
        SortOption.MOST_POPULAR -> sortedByDescending {
            when (it) {
                is ContentItem.Channel -> it.subscribers
                is ContentItem.Playlist -> it.itemCount
                is ContentItem.Video -> 100 - it.uploadedDaysAgo
            }
        }
    }

    override suspend fun search(query: String, type: String?, limit: Int): List<ContentItem> {
        val searchQuery = query.lowercase()
        val allItems = videos + channels + playlists

        return allItems.filter { item ->
            when (item) {
                is ContentItem.Video -> item.title.lowercase().contains(searchQuery) ||
                                       item.category.lowercase().contains(searchQuery)
                is ContentItem.Channel -> item.name.lowercase().contains(searchQuery) ||
                                         item.category.lowercase().contains(searchQuery)
                is ContentItem.Playlist -> item.title.lowercase().contains(searchQuery) ||
                                          item.category.lowercase().contains(searchQuery)
            }
        }.take(limit)
    }

    override suspend fun fetchCategories(): List<Category> {
        return categories.map { category ->
            Category(
                id = category.lowercase(),
                name = category,
                hasSubcategories = false
            )
        }
    }

    override suspend fun fetchSubcategories(parentId: String): List<Category> {
        // Fake service doesn't have hierarchical categories, return empty list
        return emptyList()
    }
}
