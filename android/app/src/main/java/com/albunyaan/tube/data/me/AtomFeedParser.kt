package com.albunyaan.tube.data.me

import com.albunyaan.tube.data.me.ChannelFeedFetcher.ChannelFeedItem
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject

/**
 * Parser for YouTube's per-channel ATOM feed
 * (`https://www.youtube.com/feeds/videos.xml?channel_id=...`).
 *
 * Extracts the five fields we actually need for the Me-tab feed:
 *  - `<yt:videoId>` → 11-char videoId
 *  - `<title>` → entry title (the per-entry title, not the feed-level one)
 *  - `<media:thumbnail url="..."/>` → thumbnailUrl
 *  - `<published>` ISO-8601 → uploadedAt millis
 *  - `<link rel="alternate" href="...">` containing `/shorts/` → isShort=true
 *
 * Duration and view count are not part of the ATOM feed and are returned as
 * null. The Me-tab UI (T4) hides those fields for that reason.
 *
 * Defensive against malformed XML: any throwable mid-stream returns whatever
 * entries were already parsed without crashing — the network layer relies on
 * this to recover from truncated bodies. Namespace-aware parsing uses local
 * names (`parser.name`) so the same code matches `<yt:videoId>` and
 * `<videoId>` consistently.
 */
class AtomFeedParser @Inject constructor() {

    fun parse(input: InputStream): List<ChannelFeedItem> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        val out = mutableListOf<ChannelFeedItem>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "entry") {
                    parseEntry(parser)?.let { out.add(it) }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {
            // Defensive: malformed mid-parse — return what we have. The
            // fetcher contract is "never crash on a partial body".
        }
        return out
    }

    private fun parseEntry(parser: XmlPullParser): ChannelFeedItem? {
        var videoId: String? = null
        var title: String? = null
        var thumbnailUrl: String? = null
        var publishedMs: Long? = null
        var linkHref: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "entry")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "videoId" -> if (videoId == null) videoId = parser.nextText()?.trim()
                    "title" -> if (title == null) title = parser.nextText()?.trim()
                    "published" -> if (publishedMs == null) {
                        publishedMs = parsePublished(parser.nextText())
                    }
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel")
                        if ((rel == null || rel == "alternate") && linkHref == null) {
                            linkHref = parser.getAttributeValue(null, "href")
                        }
                    }
                    "thumbnail" -> {
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && thumbnailUrl == null) thumbnailUrl = url
                    }
                }
            }
            if (parser.next() == XmlPullParser.END_DOCUMENT) break
        }

        if (videoId.isNullOrBlank() || title.isNullOrBlank()) return null
        val isShort = linkHref?.contains("/shorts/") == true
        return ChannelFeedItem(
            videoId = videoId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = null,
            viewCount = null,
            uploadedAt = publishedMs,
            isShort = isShort,
        )
    }

    private fun parsePublished(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
