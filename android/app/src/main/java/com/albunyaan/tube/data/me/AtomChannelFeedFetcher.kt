package com.albunyaan.tube.data.me

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ChannelFeedFetcher] for the Me-tab refresh layer (spec §4.1).
 *
 * Hits YouTube's per-channel ATOM endpoint
 * (`https://www.youtube.com/feeds/videos.xml?channel_id=...`) which returns
 * a tiny XML body that does not require executing YouTube's anti-bot JS.
 * Conditional GETs (If-None-Match / If-Modified-Since) keep most refresh
 * ticks at HTTP 304 with zero body — the bandwidth + signal that lets us
 * stay under YouTube's per-IP limits even with 30 subscribed channels.
 *
 * Note on threading: callers (e.g. [MeFeedRepository.refreshOne]) already
 * dispatch this on Dispatchers.IO. The fetcher additionally wraps the
 * blocking `execute()` in `withContext(Dispatchers.IO)` for defence-in-depth
 * against future direct callers — same pattern as [NewPipeChannelFeedFetcher].
 */
@Singleton
class AtomChannelFeedFetcher @VisibleForTesting internal constructor(
    private val client: OkHttpClient,
    private val parser: AtomFeedParser,
    /**
     * Test seam: callers can supply a `baseUrlOverride` (e.g. a MockWebServer
     * URL). Production binding goes through the [Inject] secondary constructor
     * and leaves this null so the real youtube.com endpoint is used. Not
     * exposed to Hilt — the @Inject constructor below omits it.
     */
    private val baseUrlOverride: String?,
) : ChannelFeedFetcher {

    @Inject
    constructor(client: OkHttpClient, parser: AtomFeedParser) : this(
        client = client,
        parser = parser,
        baseUrlOverride = null,
    )

    override suspend fun fetchLatest(
        channelUrl: String,
        priorEtag: String?,
        priorLastModified: String?,
    ): ChannelFeedFetcher.FetchResult = withContext(Dispatchers.IO) {
        val channelId = CHANNEL_ID_REGEX.find(channelUrl)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("Cannot extract channelId from $channelUrl")

        val base = baseUrlOverride ?: DEFAULT_BASE_URL
        val url = "${base}feeds/videos.xml?channel_id=$channelId"

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/atom+xml")
        priorEtag?.let { builder.header("If-None-Match", it) }
        priorLastModified?.let { builder.header("If-Modified-Since", it) }

        client.newCall(builder.build()).execute().use { response ->
            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")
            when (val code = response.code) {
                304 -> ChannelFeedFetcher.FetchResult.NotModified(etag, lastModified)
                in 200..299 -> {
                    val body = response.body ?: throw IOException("empty body")
                    val parsed = parser.parse(body.byteStream()).take(MAX_ITEMS)
                    ChannelFeedFetcher.FetchResult.Items(parsed, etag, lastModified)
                }
                else -> throw IOException("HTTP $code")
            }
        }
    }

    companion object {
        private const val MAX_ITEMS = 30
        private const val DEFAULT_BASE_URL = "https://www.youtube.com/"

        /**
         * Matches `/channel/UC...` (24-char id, leading `UC` + 22 chars from
         * the Base64-URL alphabet) anywhere in the URL. Anchored at the
         * `/channel/` segment so an arbitrary URL containing those chars
         * elsewhere will not falsely match.
         */
        internal val CHANNEL_ID_REGEX = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""")
    }
}
