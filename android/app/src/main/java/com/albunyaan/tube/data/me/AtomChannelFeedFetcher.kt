package com.albunyaan.tube.data.me

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Default [ChannelFeedFetcher] for the Me-tab refresh layer (spec §4.1).
 *
 * Hits YouTube's per-channel ATOM endpoint
 * (`https://www.youtube.com/feeds/videos.xml?channel_id=...`) which returns
 * a tiny XML body that does not require executing YouTube's anti-bot JS.
 * Conditional GETs (If-None-Match / If-Modified-Since) keep most refresh
 * ticks at HTTP 304 with zero body — the bandwidth + signal that lets us
 * stay under YouTube's per-IP limits even with 30 subscribed channels.
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
    ): ChannelFeedFetcher.FetchResult {
        val channelId = CHANNEL_ID_REGEX.find(channelUrl)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("Cannot extract channelId from $channelUrl")

        val base = baseUrlOverride ?: DEFAULT_BASE_URL
        val url = "${base}feeds/videos.xml?channel_id=$channelId"

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/atom+xml")
        priorEtag?.let { builder.header("If-None-Match", it) }
        priorLastModified?.let { builder.header("If-Modified-Since", it) }

        // Use suspendCancellableCoroutine so coroutine cancellation (e.g. from
        // withTimeout in MeFeedRepository.refreshOne) propagates to OkHttp and
        // cancels the in-flight TCP connection, preventing zombie threads from
        // exceeding the MAX_CONCURRENT semaphore bound.
        val response = suspendCancellableCoroutine<Response> { cont ->
            val call = client.newCall(builder.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
            })
        }

        return response.use { r ->
            val etag = r.header("ETag")
            val lastModified = r.header("Last-Modified")
            when (val code = r.code) {
                304 -> ChannelFeedFetcher.FetchResult.NotModified(etag, lastModified)
                in 200..299 -> {
                    val body = r.body ?: throw IOException("empty body")
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
