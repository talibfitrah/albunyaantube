package com.albunyaan.tube.data.source

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.auth.AuthState
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.RegistryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val user: () -> String? = { null },
) {
    @Inject constructor(api: ContentApi, auth: AuthRepository) : this(
        api, SystemClock::elapsedRealtime, CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { (auth.authState.value as? AuthState.SignedIn)?.uid },
    )

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
    // Each running ask, and whether it waits for a permit
    private val inFlight = HashMap<String, Pair<Deferred<Entry>, Boolean>>()
    // ponytail: app-wide caps on concurrent background asks, so a mixed-uploader playlist page or the Me feed's saved
    // playlists cannot burst the backend. Gate calls and one playlist's own page ask skip them.
    private val channelPermits = Semaphore(MAX_CONCURRENT)
    private val playlistPermits = Semaphore(MAX_CONCURRENT_PLAYLISTS)
    // The signed-in user the answers are for, and a count of its changes (guarded by [answers]): PERSONAL lists never
    // carry over to another. An ask is kept only within its own sign-in: one that spanned a sign-out and back in may
    // have been signed as the other user.
    private var owner: String? = null
    private var generation = 0

    /** Bumped each time an answer changes a list, so a list already shown (the Me feed) can filter again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /**
     * Channel [id]'s answer. [fresh]: a screen open asks again (joining an uncapped ask already running, never a queued
     * list ask) so a new exclusion shows.
     */
    suspend fun channel(id: String, fresh: Boolean = false): Entry =
        lookup("channel:$id", fresh, if (fresh) null else channelPermits) { api.getChannel(id) }

    suspend fun playlist(id: String, fresh: Boolean = false): Entry = askPlaylist(id, fresh, permits = null)

    private suspend fun askPlaylist(id: String, fresh: Boolean, permits: Semaphore?): Entry =
        lookup("playlist:$id", fresh, permits) { api.getPlaylist(id) }

    /**
     * The video ids the lists of these channels and playlists drop. An id with no answer within [WAIT_MS] adds its last
     * answer's lists, or none if it never had one.
     */
    suspend fun videoIds(channelIds: Collection<String> = emptyList(), playlistIds: Collection<String> = emptyList()): Set<String> =
        coroutineScope {
            val playlists = playlistIds.filter { it.isNotBlank() }.distinct()
            // Several playlists at once (the Me feed's saved ones) queue under their cap; one playlist's own page ask
            // never waits behind them.
            val cap = playlistPermits.takeIf { playlists.size > 1 }
            val asks = channelIds.filter { it.isNotBlank() }.distinct().map { async { waited("channel:$it") { channel(it) } } } +
                playlists.map { async { waited("playlist:$it") { askPlaylist(it, fresh = false, cap) } } }
            asks.awaitAll().flatMapTo(HashSet()) { it?.videoIds.orEmpty() }
        }

    /**
     * Whether channel [channelId] hides [videoId] from a playback with no channel context. Specific beats general: an
     * exclusion always hides; a gone channel (410) hides only a video not [approved] on its own (asked only then).
     * [fresh] asks again (a stream resolve); a saved copy reads the memo first. Fails open like the lists.
     */
    suspend fun hides(channelId: String, videoId: String, fresh: Boolean = true, approved: suspend () -> Boolean): Boolean =
        waited("channel:$channelId") { channel(channelId, fresh) }
            ?.let { videoId in it.videoIds || (it.code == 410 && !approved()) } == true

    /** The playlists a channel's Playlists tab drops, the same fail-open way. */
    suspend fun playlistIds(channelId: String): Set<String> =
        waited("channel:$channelId") { channel(channelId) }?.playlistIds.orEmpty()

    // The ask keeps running in [scope] after the wait gives up, so its answer still serves the next page.
    private suspend fun waited(key: String, ask: suspend () -> Entry): Entry? =
        withTimeoutOrNull(WAIT_MS) { ask() } ?: synchronized(answers) { answers[key].takeIf { user() == owner } }

    /** [permits]: the cap a background ask waits under; null for a gate call, which never queues. */
    private suspend fun lookup(
        key: String,
        fresh: Boolean,
        permits: Semaphore?,
        call: suspend () -> Response<RegistryEntry>,
    ): Entry {
        val job = synchronized(answers) {
            // Checked on every read, so a sign-out or another sign-in never reads the last user's PERSONAL lists.
            val asker = user()
            if (asker != owner) {
                answers.clear()
                inFlight.clear()
                owner = asker
                generation++
            }
            val asked = generation
            // An uncapped ask (a gate, one playlist's own page) never joins an ask waiting for a permit behind a burst.
            inFlight[key]?.takeUnless { permits == null && it.second }?.first ?: run {
                val held = answers[key]
                if (!fresh && held != null && held.until > now()) return held
                // The newest answer, not the one this ask started from: a gate may have stored one meanwhile.
                val last = { synchronized(answers) { answers[key].takeIf { asked == generation } ?: held } }
                val ask = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        fetch(call, last, permits).also {
                            val changed = synchronized(answers) {
                                val before = last()
                                (asked == generation).also { mine -> if (mine) answers[key] = it } &&
                                    (it.videoIds != before?.videoIds.orEmpty() || it.playlistIds != before?.playlistIds.orEmpty())
                            }
                            // Only a changed list re-filters what is on screen; an unchanged or empty re-ask must not,
                            // or a memo that evicts its own keys would re-ask forever.
                            if (changed) _changes.update { n -> n + 1 }
                        }
                    } finally {
                        val self = coroutineContext[Job]
                        synchronized(answers) { if (inFlight[key]?.first === self) inFlight.remove(key) }
                    }
                }
                inFlight[key] = ask to (permits != null)
                ask
            }
        }
        return job.await()
    }

    private suspend fun fetch(call: suspend () -> Response<RegistryEntry>, last: () -> Entry?, permits: Semaphore?): Entry = try {
        val r = if (permits != null) {
            permits.withPermit {
                // A gate may have answered while this ask waited its turn: no second call for that id
                last()?.takeIf { it.error == null && it.until > now() }?.let { return it }
                call()
            }
        } else call()
        val body = r.body()
        when {
            r.isSuccessful -> Entry(
                r.code(), body?.excludedVideoIds.orEmpty().toSet(), body?.excludedPlaylistIds.orEmpty().toSet(), null,
                now() + ANSWER_MS,
            )
            r.code() == 404 || r.code() == 410 -> Entry(r.code(), emptySet(), emptySet(), null, now() + ANSWER_MS)
            else -> failed(HttpException(r), last())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(e, last()) // IOException, a Moshi error
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
        private const val MAX_CONCURRENT_PLAYLISTS = 2
    }
}
