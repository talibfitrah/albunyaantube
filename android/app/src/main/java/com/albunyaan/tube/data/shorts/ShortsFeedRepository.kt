package com.albunyaan.tube.data.shorts

import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ShortsFeedRepository @Inject constructor(
    @Named("real") private val contentService: ContentService,
    private val channelDetailRepository: ChannelDetailRepository
) {

    /**
     * Channel-scoped pagination tokens. The public API exposes cursors as
     * [String] for parity with the global feed, but [ChannelDetailRepository]
     * speaks in [Page] objects — so we stash them here keyed by a synthetic
     * token that we hand back as [ShortsPage.nextCursor].
     */
    private val channelPageTokens = ConcurrentHashMap<String, Page>()

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

    /**
     * Fetches the next page of shorts for a specific channel. Channel metadata
     * is left blank — the ViewModel decorates items with the channel header
     * it already has in hand (avoids a redundant fetch per page).
     */
    suspend fun loadChannelShortsPage(
        channelId: String,
        cursor: String?,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): ShortsPage {
        val page: Page? = cursor?.let { channelPageTokens.remove(it) }
        val channelPage = channelDetailRepository.getShorts(channelId, page)
        val items = channelPage.items.take(pageSize).map { s: ChannelShort ->
            ShortsItem(
                id = s.id,
                title = s.title,
                channelId = "",
                channelName = "",
                channelAvatarUrl = null,
                thumbnailUrl = s.thumbnailUrl,
                durationSeconds = s.durationSeconds ?: 0
            )
        }
        val nextCursor = channelPage.nextPage?.let { nextPage ->
            val token = UUID.randomUUID().toString()
            channelPageTokens[token] = nextPage
            token
        }
        return ShortsPage(items, nextCursor)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 10
    }
}
