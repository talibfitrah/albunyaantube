package com.albunyaan.tube.data.shorts

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ShortsFeedRepository @Inject constructor(
    @Named("real") private val contentService: ContentService
) {
    suspend fun loadFeedPage(cursor: String?, pageSize: Int = DEFAULT_PAGE_SIZE): ShortsPage {
        val filters = FilterState(videoLength = VideoLength.UNDER_FOUR_MIN)
        val response = contentService.fetchContent(ContentType.VIDEOS, cursor, pageSize, filters)
        val items = response.data.filterIsInstance<ContentItem.Video>().map { v ->
            ShortsItem(
                id = v.id,
                title = v.title,
                channelId = "",
                channelName = "",
                channelAvatarUrl = null,
                thumbnailUrl = v.thumbnailUrl,
                durationSeconds = v.durationSeconds
            )
        }
        return ShortsPage(items, response.pageInfo?.nextCursor)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 10
    }
}
