package com.albunyaan.tube.data.shorts

import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import java.util.Collections
import java.util.UUID
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
     *
     * Bounded to the [MAX_TOKENS] most-recently-accessed entries via an
     * access-ordered [LinkedHashMap] so long sessions that issue tokens
     * faster than they consume them (e.g. rapid channel switching) can't
     * grow the map indefinitely and leak one [Page] object per stale cursor.
     *
     * The map isn't thread-safe on its own once accessOrder = true mutates
     * ordering on every read, so all access is gated by a
     * [Collections.synchronizedMap] wrapper and an explicit `synchronized`
     * block on the wrapper whenever we do read-modify-write.
     */
    private val channelPageTokens: MutableMap<String, Page> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean {
                return size > MAX_TOKENS
            }
        }
    )

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
     *
     * Cursor semantics: a non-null [cursor] is a token previously handed out
     * by this repository for the same channel. Tokens are stored in a
     * bounded LRU (32 entries) and consumed on first lookup. If a cursor was
     * evicted (rare — only after >32 distinct channel-pagination sessions
     * without consuming) or comes from a different process lifetime, lookup
     * yields null and we transparently restart from page 1. The caller will
     * see duplicate item ids; DiffUtil collapses them, so the UX impact is a
     * minor "jump" rather than data corruption. This fallback is intentional
     * to keep the UX recoverable; callers that need strict pagination can
     * detect a missing cursor via [channelPageTokenCountForTest] before the call.
     */
    suspend fun loadChannelShortsPage(
        channelId: String,
        cursor: String?,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): ShortsPage {
        val page: Page? = cursor?.let {
            val resolved = synchronized(channelPageTokens) { channelPageTokens.remove(it) }
            if (resolved == null) {
                android.util.Log.w(
                    "ShortsFeedRepository",
                    "Channel cursor evicted/unknown ($it) — restarting from page 1"
                )
            }
            resolved
        }
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
            synchronized(channelPageTokens) { channelPageTokens[token] = nextPage }
            token
        }
        return ShortsPage(items, nextCursor)
    }

    // --- Test-only introspection / drivers for LRU coverage ---

    /** Test-only: number of currently-held channel page tokens. */
    internal fun channelPageTokenCountForTest(): Int =
        synchronized(channelPageTokens) { channelPageTokens.size }

    /** Test-only: whether the given token is still cached. */
    internal fun containsChannelPageTokenForTest(token: String): Boolean =
        synchronized(channelPageTokens) { channelPageTokens.containsKey(token) }

    /**
     * Test-only: directly insert a token/page mapping to drive LRU eviction
     * assertions without spinning up the channel-detail repository.
     */
    internal fun putChannelPageTokenForTest(token: String, page: Page) {
        synchronized(channelPageTokens) { channelPageTokens[token] = page }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 10

        /**
         * Upper bound on cached channel-page tokens. A user would have to
         * switch through 32 channels without ever consuming a "next page"
         * cursor to trigger eviction, which safely exceeds normal usage while
         * keeping retained memory bounded.
         */
        internal const val MAX_TOKENS = 32
    }
}
