package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.VideoLength
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
            durationMinutes = duration,
            uploadedDaysAgo = daysAgo,
            description = "Curated video about $category (uploaded $daysAgo days ago)."
        )
    }

    override suspend fun fetchContent(
        type: ContentType,
        page: Int,
        pageSize: Int,
        filters: FilterState
    ): CursorResponse {
        val sourceItems: List<ContentItem> = when (type) {
            ContentType.HOME, ContentType.VIDEOS -> videos.map { it.first }
            ContentType.CHANNELS -> List(40) { index ->
                val category = categories[index % categories.size]
                ContentItem.Channel(
                    id = "channel-$index",
                    name = "Channel $category #$index",
                    category = category,
                    subscribers = 10_000 + index * 250
                )
            }
            ContentType.PLAYLISTS -> List(30) { index ->
                val category = categories[index % categories.size]
                ContentItem.Playlist(
                    id = "playlist-$index",
                    title = "$category playlist #$index",
                    category = category,
                    itemCount = 12 + index
                )
            }
        }

        val filtered = sourceItems.filter { item ->
            when (item) {
                is ContentItem.Video -> filters.matchesVideo(item)
                is ContentItem.Channel -> filters.category?.let { item.category == it } ?: true
                is ContentItem.Playlist -> filters.category?.let { item.category == it } ?: true
            }
        }

        val fromIndex = page * pageSize
        val toIndex = min(fromIndex + pageSize, filtered.size)
        val pageItems = if (fromIndex >= filtered.size) emptyList() else filtered.subList(fromIndex, toIndex)
        val hasNext = toIndex < filtered.size

        return CursorResponse(pageItems, hasNext)
    }

    private fun FilterState.matchesVideo(video: ContentItem.Video): Boolean {
        val categoryMatch = category?.let { video.category == it } ?: true
        val lengthMatch = when (videoLength) {
            VideoLength.ANY -> true
            VideoLength.UNDER_FOUR_MIN -> video.durationMinutes < 4
            VideoLength.FOUR_TO_TWENTY_MIN -> video.durationMinutes in 4..20
            VideoLength.OVER_TWENTY_MIN -> video.durationMinutes > 20
        }
        val publishedMatch = when (publishedDate) {
            PublishedDate.ANY -> true
            PublishedDate.LAST_24_HOURS -> video.uploadedDaysAgo <= 1
            PublishedDate.LAST_7_DAYS -> video.uploadedDaysAgo <= 7
            PublishedDate.LAST_30_DAYS -> video.uploadedDaysAgo <= 30
        }
        val sortMatch = true
        return categoryMatch && lengthMatch && publishedMatch && sortMatch
    }
}
