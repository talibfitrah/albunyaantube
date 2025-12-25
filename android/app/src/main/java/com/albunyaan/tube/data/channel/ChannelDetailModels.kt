package com.albunyaan.tube.data.channel

import java.time.Instant

/**
 * Channel header information including metadata for the About tab.
 * Maps NewPipe ChannelInfo to UI-friendly data.
 */
data class ChannelHeader(
    val id: String,
    val title: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val subscriberCount: Long?,
    val shortDescription: String?,
    val summaryLine: String?,
    val fullDescription: String?,
    val links: List<ChannelLink>,
    val location: String?,
    val joinedDate: Instant?,
    val totalViews: Long?,
    val isVerified: Boolean,
    val tags: List<String>
)

/**
 * External link from a channel's About section.
 */
data class ChannelLink(
    val name: String,
    val url: String
)

/**
 * Video from a channel's Videos tab.
 */
data class ChannelVideo(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val viewCount: Long?,
    val publishedTime: String?,
    val uploaderName: String?
)

/**
 * Short-form video from a channel's Shorts tab.
 * Optimized for 9:16 vertical thumbnail display.
 */
data class ChannelShort(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val viewCount: Long?,
    val durationSeconds: Int?,
    val publishedTime: String?
)

/**
 * Live stream or upcoming stream from a channel's Live tab.
 *
 * For past/recorded streams: [durationSeconds] and [publishedTime] are populated.
 * For live streams: [isLiveNow] is true, [durationSeconds] is null (or 0).
 * For upcoming streams: [isUpcoming] is true, [scheduledStartTime] may be populated.
 */
data class ChannelLiveStream(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val isLiveNow: Boolean,
    val isUpcoming: Boolean,
    val scheduledStartTime: Instant?,
    val viewCount: Long?,
    val uploaderName: String?,
    /** Duration in seconds for past/recorded streams. Null for live/upcoming. */
    val durationSeconds: Int?,
    /** Human-readable publish/stream time from NewPipe (e.g., "2 weeks ago", "Streamed 3 days ago") */
    val publishedTime: String?
)

/**
 * Playlist from a channel's Playlists tab.
 */
data class ChannelPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val itemCount: Long?,
    val description: String?,
    val uploaderName: String?
)

/**
 * Generic paginated page response for channel content.
 *
 * @param T The type of items in this page
 * @property items The content items for this page
 * @property nextPage Cursor/token for fetching the next page, null if no more pages
 * @property fromCache Whether this data came from cache
 */
data class ChannelPage<T>(
    val items: List<T>,
    val nextPage: Page?,
    val fromCache: Boolean = false
)

/**
 * Represents pagination state for fetching next pages.
 * Wraps NewPipe's Page object.
 *
 * Note: This is not a data class because ByteArray requires custom equals/hashCode.
 */
class Page(
    val url: String?,
    val id: String?,
    val ids: List<String>?,
    val cookies: Map<String, String>?,
    val body: ByteArray? = null
) {
    companion object {
        fun fromNewPipePage(page: org.schabi.newpipe.extractor.Page?): Page? {
            if (page == null) return null
            return Page(
                url = page.url,
                id = page.id,
                ids = page.ids,
                cookies = page.cookies,
                body = page.body
            )
        }
    }

    fun toNewPipePage(): org.schabi.newpipe.extractor.Page {
        return org.schabi.newpipe.extractor.Page(url, id, ids, cookies, body)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Page) return false
        return url == other.url &&
                id == other.id &&
                ids == other.ids &&
                cookies == other.cookies &&
                (body?.contentEquals(other.body) ?: (other.body == null))
    }

    override fun hashCode(): Int {
        var result = url?.hashCode() ?: 0
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + (ids?.hashCode() ?: 0)
        result = 31 * result + (cookies?.hashCode() ?: 0)
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Page(url=$url, id=$id, ids=$ids, cookies=$cookies, body=${body?.size ?: 0} bytes)"
    }
}

/**
 * Enumeration of channel tabs supported by the detail screen.
 *
 * Note: Posts/Community tab is NOT included because NewPipeExtractor
 * does not support YouTube Community Posts extraction.
 * See: https://teamnewpipe.github.io/NewPipeExtractor/javadoc/org/schabi/newpipe/extractor/channel/tabs/ChannelTabs.html
 */
enum class ChannelTab {
    VIDEOS,
    LIVE,
    SHORTS,
    PLAYLISTS,
    ABOUT
}
