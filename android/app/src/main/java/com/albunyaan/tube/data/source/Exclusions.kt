package com.albunyaan.tube.data.source

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.RegistryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner bug 2026-11-03: what the dashboard excluded from a registry channel (its Videos, Shorts and Live lists; its
 * Playlists tab) or playlist. The app lists those items on the device from YouTube, so every such list drops these
 * ids. `GET /api/v1/channels|playlists/{id}` is also the availability gate (410 = gone), so one answer serves both:
 * the header gate, the tab gate and the first page of a screen open share one call per id.
 *
 * Fail open: a failed or slow answer never blocks a list. A failure keeps the last answer's lists (a stale list beats
 * showing what the admin hid) and is asked again after [FAILURE_MS], never cached as "nothing excluded" for long.
 */
@Singleton
class Exclusions @VisibleForTesting internal constructor(
    private val api: ContentApi,
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject constructor(api: ContentApi) :
        this(api, SystemClock::elapsedRealtime, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /**
     * [code]: 2xx registered, 404 not registered (or a PERSONAL grant this user does not hold: no exclusions known, never
     * gone), 410 gone; null with [error] when the call failed. The shared OkHttp client signs the GET with the user's
     * Firebase Bearer, so a grantee gets their PERSONAL item's lists.
     */
    class Entry(
        val code: Int?,
        val videoIds: Set<String>,
        val playlistIds: Set<String>,
        val error: Exception?,
        internal val until: Long,
    ) {
        val registered: Boolean get() = code != null && code in 200..299

        /** [code], or the failure thrown so the gate's caller decides (it fails open). */
        fun codeOrThrow(): Int = code ?: throw checkNotNull(error)
    }

    // ponytail: process-lifetime LRU guarded by its own monitor; a bigger app would key a Room table instead.
    private val answers = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MEMO_SIZE
    }
    private val inFlight = HashMap<String, Deferred<Entry>>()

    /** Channel [id]'s answer. [fresh]: a screen open asks again (joining an ask already running) so a new exclusion shows. */
    suspend fun channel(id: String, fresh: Boolean = false): Entry = lookup("channel:$id", fresh) { api.getChannel(id) }

    suspend fun playlist(id: String, fresh: Boolean = false): Entry = lookup("playlist:$id", fresh) { api.getPlaylist(id) }

    /** The video ids the lists of these channels and playlists drop. An id with no answer within [WAIT_MS] adds none. */
    suspend fun videoIds(channelIds: Collection<String> = emptyList(), playlistIds: Collection<String> = emptyList()): Set<String> =
        coroutineScope {
            val asks = channelIds.filter { it.isNotBlank() }.distinct().map { async { waited { channel(it) } } } +
                playlistIds.filter { it.isNotBlank() }.distinct().map { async { waited { playlist(it) } } }
            asks.awaitAll().flatMapTo(HashSet()) { it?.videoIds.orEmpty() }
        }

    /** The playlists a channel's Playlists tab drops, the same fail-open way. */
    suspend fun playlistIds(channelId: String): Set<String> = waited { channel(channelId) }?.playlistIds.orEmpty()

    // The ask keeps running in [scope] after the wait gives up, so its answer still serves the next page.
    private suspend fun waited(ask: suspend () -> Entry): Entry? = withTimeoutOrNull(WAIT_MS) { ask() }

    private suspend fun lookup(key: String, fresh: Boolean, call: suspend () -> Response<RegistryEntry>): Entry {
        val job = synchronized(answers) {
            inFlight[key] ?: run {
                val held = answers[key]
                if (!fresh && held != null && held.until > now()) return held
                scope.async(start = CoroutineStart.LAZY) {
                    fetch(call, held).also { synchronized(answers) { answers[key] = it; inFlight.remove(key) } }
                }.also { inFlight[key] = it }
            }
        }
        return job.await()
    }

    private suspend fun fetch(call: suspend () -> Response<RegistryEntry>, held: Entry?): Entry = try {
        val r = call()
        val body = r.body()
        when {
            r.isSuccessful -> Entry(
                r.code(), body?.excludedVideoIds.orEmpty().toSet(), body?.excludedPlaylistIds.orEmpty().toSet(), null,
                now() + ANSWER_MS,
            )
            r.code() == 404 || r.code() == 410 -> Entry(r.code(), emptySet(), emptySet(), null, now() + ANSWER_MS)
            else -> failed(HttpException(r), held)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(e, held) // IOException, a Moshi error
    }

    private fun failed(e: Exception, held: Entry?) =
        Entry(null, held?.videoIds.orEmpty(), held?.playlistIds.orEmpty(), e, now() + FAILURE_MS)

    companion object {
        private const val ANSWER_MS = 5 * 60_000L // a list read away from a screen open (Me feed, player, next page)
        private const val FAILURE_MS = 30_000L
        private const val WAIT_MS = 2_000L // ponytail: a list waits at most this long; raise it if field RTT is higher
        private const val MEMO_SIZE = 500
    }
}
