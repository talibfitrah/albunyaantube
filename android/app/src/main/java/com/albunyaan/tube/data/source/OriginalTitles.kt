package com.albunyaan.tube.data.source

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.data.source.api.ContentApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner issue 1(b): the original titles our backend holds (`GET /api/v1/titles`) for the video ids an on-device
 * channel or playlist page lists. NewPipe lists those pages at en-US (`NewPipeExtractorClient` localization), so
 * YouTube machine-translates Arabic and Dutch titles. Every failure is silent: rows keep their list titles, and a
 * page whose every chunk fails or misses the cap quiets this for [QUIET_MS], so a down server costs one slow page a
 * minute, never a retry storm.
 */
@Singleton
class OriginalTitles @VisibleForTesting internal constructor(
    private val api: ContentApi,
    private val now: () -> Long,
) {
    @Inject constructor(api: ContentApi) : this(api, SystemClock::elapsedRealtime)

    /** `title == null`: the server did not know the id when asked; asked again after [until]. */
    private class Entry(val title: String?, val until: Long)

    // ponytail: process-lifetime LRU. A known title is kept for the session (titles rarely change); an unknown id follows
    // the answer's max-age (60 s partial, 1 h complete). Exists because the player re-fetches the playlist's page 1 before
    // playback; without it every playlist play waits one more RTT.
    private val memo = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MEMO_SIZE
    }
    @Volatile private var quietUntil = 0L

    /** [items] with each title the server knows swapped for the original; every other item unchanged. */
    suspend fun <T> retitle(items: List<T>, id: (T) -> String, withTitle: (T, String) -> T): List<T> {
        val titles = lookup(items.map(id))
        return if (titles.isEmpty()) items else items.map { item -> titles[id(item)]?.let { withTitle(item, it) } ?: item }
    }

    private suspend fun lookup(ids: List<String>): Map<String, String> {
        val wanted = ids.filter(VIDEO_ID::matches).distinct()
        val t = now()
        val ask = synchronized(memo) { wanted.filter { memo[it]?.let { e -> e.title == null && e.until <= t } ?: true } }
        if (ask.isNotEmpty() && t >= quietUntil) {
            // Every chunk is asked at once under its own cap (iOS `TitlesClient` parity): a failed chunk costs only its ids.
            val answered = coroutineScope {
                ask.chunked(MAX_IDS).map { chunk -> async { fetch(chunk, t) } }.awaitAll()
            }.filterNotNull()
            if (answered.isEmpty()) quietUntil = t + QUIET_MS // no chunk answered: a down or slow server
            synchronized(memo) { answered.forEach(memo::putAll) }
        }
        return synchronized(memo) { wanted.mapNotNull { id -> memo[id]?.title?.let { id to it } }.toMap() }
    }

    /** The memo entries for one chunk, or null when it threw, missed the cap or got a non-2xx (400, 503). */
    private suspend fun fetch(chunk: List<String>, t: Long): Map<String, Entry>? {
        val r = try {
            withTimeoutOrNull(TIMEOUT_MS) { api.fetchTitles(chunk.joinToString(",")) } ?: return null
        } catch (e: CancellationException) {
            throw e // the caller gave up, not the server: never quiet for it
        } catch (e: Exception) {
            return null // IOException, a Moshi error: silent
        }
        val body = r.body()?.takeIf { r.isSuccessful } ?: return null
        val until = t + r.raw().cacheControl.maxAgeSeconds.coerceAtLeast(60) * 1_000L
        return chunk.associateWith { Entry(body[it], until) }
    }

    companion object {
        const val MAX_IDS = 50 // PublicContentController.MAX_TITLE_IDS
        private const val TIMEOUT_MS = 1_500L // ponytail: 1.5 s cap; raise it if field RTT to the API is higher
        private const val QUIET_MS = 60_000L
        private const val MEMO_SIZE = 2_000
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
