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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
     * gone), 410 gone. A failed call sets [error] and keeps the last answer's code, null when there was none. The
     * shared OkHttp client signs the GET with the user's Firebase Bearer, so a grantee gets their PERSONAL item's lists.
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
    // ponytail: one app-wide cap on concurrent background asks, so a mixed-uploader playlist page cannot burst the
    // backend; gate calls and playlist asks skip it.
    private val permits = Semaphore(MAX_CONCURRENT)

    /** Bumped each time an answer changes a list, so a list already shown (the Me feed) can filter again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /** Channel [id]'s answer. [fresh]: a screen open asks again (joining an ask already running) so a new exclusion shows. */
    suspend fun channel(id: String, fresh: Boolean = false): Entry =
        lookup("channel:$id", fresh, queued = !fresh) { api.getChannel(id) }

    // A playlist ask is one per page, never a burst: it skips the queue, so its own exclusions beat the wait.
    suspend fun playlist(id: String, fresh: Boolean = false): Entry =
        lookup("playlist:$id", fresh, queued = false) { api.getPlaylist(id) }

    /**
     * The video ids the lists of these channels and playlists drop. An id with no answer within [WAIT_MS] adds its last
     * answer's lists, or none if it never had one.
     */
    suspend fun videoIds(channelIds: Collection<String> = emptyList(), playlistIds: Collection<String> = emptyList()): Set<String> =
        coroutineScope {
            val asks = channelIds.filter { it.isNotBlank() }.distinct().map { async { waited("channel:$it") { channel(it) } } } +
                playlistIds.filter { it.isNotBlank() }.distinct().map { async { waited("playlist:$it") { playlist(it) } } }
            asks.awaitAll().flatMapTo(HashSet()) { it?.videoIds.orEmpty() }
        }

    /** The playlists a channel's Playlists tab drops, the same fail-open way. */
    suspend fun playlistIds(channelId: String): Set<String> =
        waited("channel:$channelId") { channel(channelId) }?.playlistIds.orEmpty()

    // The ask keeps running in [scope] after the wait gives up, so its answer still serves the next page.
    private suspend fun waited(key: String, ask: suspend () -> Entry): Entry? =
        withTimeoutOrNull(WAIT_MS) { ask() } ?: synchronized(answers) { answers[key] }

    private suspend fun lookup(
        key: String,
        fresh: Boolean,
        queued: Boolean,
        call: suspend () -> Response<RegistryEntry>,
    ): Entry {
        val job = synchronized(answers) {
            inFlight[key] ?: run {
                val held = answers[key]
                if (!fresh && held != null && held.until > now()) return held
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        fetch(call, held, queued).also {
                            synchronized(answers) { answers[key] = it }
                            // Only a changed list re-filters what is on screen; an unchanged or empty re-ask must not,
                            // or a memo that evicts its own keys would re-ask forever.
                            if (it.videoIds != held?.videoIds.orEmpty() || it.playlistIds != held?.playlistIds.orEmpty()) {
                                _changes.update { n -> n + 1 }
                            }
                        }
                    } finally {
                        synchronized(answers) { inFlight.remove(key) }
                    }
                }.also { inFlight[key] = it }
            }
        }
        return job.await()
    }

    // [queued]: a list's background channel ask waits for a permit; a gate call or a playlist ask never does.
    private suspend fun fetch(call: suspend () -> Response<RegistryEntry>, held: Entry?, queued: Boolean): Entry = try {
        val r = if (queued) permits.withPermit { call() } else call()
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

    // Keeps the last answer's status too: one blip must not make a registered channel read as unregistered.
    private fun failed(e: Exception, held: Entry?) =
        Entry(held?.code, held?.videoIds.orEmpty(), held?.playlistIds.orEmpty(), e, now() + FAILURE_MS)

    companion object {
        private const val ANSWER_MS = 5 * 60_000L // a list read away from a gate call (Me feed, next page)
        private const val FAILURE_MS = 30_000L
        private const val WAIT_MS = 2_000L // ponytail: a list waits at most this long; raise it if field RTT is higher
        private const val MEMO_SIZE = 500
        private const val MAX_CONCURRENT = 4
    }
}
