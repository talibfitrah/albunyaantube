package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.data.source.api.LiveItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Spec §9.1, on device: a channel's current live from its `/live` page, whose canonical link is the live's watch URL,
 * or the channel's own URL when nothing is live. Same answer shape as the backend's lives: a list = an answer
 * (empty = not live), null = no answer (the caller asks the backend). Fetched through the app's NewPipe downloader.
 */
@Singleton
class LiveChannelResolver internal constructor(private val downloader: Downloader, private val io: CoroutineDispatcher) {
    @Inject constructor(downloader: RateLimitedDownloader) : this(downloader, Dispatchers.IO)

    /** At most [BUDGET_MS], so the backend fallback still fits the recovery's budget. */
    suspend fun lives(channelId: String): List<LiveItem>? {
        if (!CHANNEL_ID.matches(channelId)) return null
        // The NewPipe Downloader has no per-call cancel: the download itself ends at the same budget (BoundedGet's call
        // timeout), so a fetch the caller stopped waiting for never outlives it.
        return withTimeoutOrNull(BUDGET_MS) { CoroutineScope(io).async { fetch(channelId) }.await() }
    }

    private fun fetch(channelId: String): List<LiveItem>? =
        try {
            val headers = mapOf(
                "User-Agent" to listOf(USER_AGENT),
                "Accept-Language" to listOf("en"),
            ) + YoutubeParsingHelper.getCookieHeader()                     // the extractor's own consent cookie
            // Player: a recovery the user is waiting on, never gated by the background cooldown (spec D1).
            val response = NewPipePriorityContext.with(Priority.PLAYER) {
                // No localization: get(url, headers) would replace this Accept-Language with NewPipe's.
                downloader.execute(BoundedGet("https://www.youtube.com/channel/$channelId/live", headers, BUDGET_MS, MAX_BYTES))
            }
            if (response.responseCode() !in 200..299) null else parse(channelId, response.responseBody())
        } catch (e: Exception) {                                            // network, timeout, too big, ReCaptcha: no answer
            null
        }

    companion object {
        private const val BUDGET_MS = 5_000L
        /** iOS's cap. The 2026-11-02 pages were ~1.3 MB. */
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val VIDEO_DETAILS = "\"videoDetails\":{"
        /** Always after channelId in the player response; ends the videoDetails segment. */
        private const val SHORT_DESCRIPTION = "\"shortDescription\""
        private const val DETAILS_MAX_CHARS = 64 * 1024
        private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        private val CANONICAL = Regex("""<link rel="canonical" href="([^"]+)"""")
        private val WATCH = Regex("""https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})""")
        private val OG_TITLE = Regex("""<meta property="og:title" content="([^"]*)"""")
        private val META_TITLE = Regex("""<meta name="title" content="([^"]*)"""")
        private val ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos);")
        private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
        /** A desktop Firefox, as the 2026-11-02 probes used. */
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        /**
         * Live: its id and title ("" for none, as the backend sends). Not live: empty. Anything else (upcoming, consent, bot
         * check, another channel's video): null. Live = the player response's videoDetails names the canonical id, this
         * channel and `"isLive":true`; an `"isLive":true` elsewhere on the page counts for nothing.
         */
        internal fun parse(channelId: String, html: String): List<LiveItem>? {
            val canonical = CANONICAL.find(html)?.groupValues?.get(1) ?: return null
            if (canonical == "https://www.youtube.com/channel/$channelId") return emptyList()
            val id = WATCH.matchEntire(canonical)?.groupValues?.get(1) ?: return null
            val start = html.indexOf(VIDEO_DETAILS).takeIf { it >= 0 } ?: return null
            val window = html.substring(start, minOf(html.length, start + DETAILS_MAX_CHARS))
            val details = window.substring(0, window.indexOf(SHORT_DESCRIPTION).takeIf { it >= 0 } ?: return null)
            // An upcoming waiting room has a watch canonical too, but no "isLive":true.
            if (listOf("\"videoId\":\"$id\"", "\"channelId\":\"$channelId\"", "\"isLive\":true").any { it !in details }) return null
            val title = (OG_TITLE.find(html) ?: META_TITLE.find(html))?.groupValues?.get(1)?.let(::unescape).orEmpty()
            return listOf(LiveItem(id, title))
        }

        private fun unescape(s: String) = ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            val code = when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)
                e.startsWith("#") -> e.drop(1).toIntOrNull()
                else -> return@replace NAMED.getValue(e)
            }
            code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        }
    }
}
