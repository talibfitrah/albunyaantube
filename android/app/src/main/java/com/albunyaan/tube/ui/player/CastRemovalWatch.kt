package com.albunyaan.tube.ui.player

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cubic K4: a cast live keeps playing on the TV after the player screen closes, and closing it pops the player, which
 * clears its ViewModel. The cast session lives in the app-wide [CastContext], so the removal heartbeat for a cast live
 * moves here, app lifetime, until the cast ends or the receiver gets another video. Main thread (the Cast API's).
 */
@Singleton
class CastRemovalWatch internal constructor(
    private val scope: CoroutineScope,
    /** A cast session is current (suspended during a reconnect included): no client then is a gap, not an end. */
    private val sessionUp: () -> Boolean = { true },
    private val client: () -> RemoteMediaClient?,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        MainScope(),
        sessionUp = { session(context) != null },
        client = { session(context)?.remoteMediaClient },
    )

    private companion object {
        fun session(context: Context) =
            try { CastContext.getSharedInstance(context).sessionManager.currentCastSession }
            catch (_: Exception) { null }   // Cast unavailable (no Play services)
    }

    /** The video and URL this app last loaded to the receiver (iOS `loadedClaim`); null after the session ends. */
    var loaded: Pair<String, String>? = null

    private var watchedId: String? = null
    private var job: Job? = null

    /**
     * The receiver plays [id], which this app cast: the only video a removal may stop there (Cubic R4-2). Its media
     * must still be the URL this app loaded (Codex r3): another sender's load on the same TV, or a load the receiver
     * never took, is not ours to stop.
     */
    fun casting(id: String) = receiver(id) == Receiver.OURS

    /**
     * Codex r5: OURS, the receiver plays this app's load of [id] (a removal may stop it). GONE, another load of ours or
     * other media on the receiver (a watch ends). UNKNOWN, no session or media status right now (a reconnect, a pending
     * load, ours finished): a watch waits for the next tick without asking, and nothing is stopped.
     */
    private enum class Receiver { OURS, GONE, UNKNOWN }

    private fun receiver(id: String): Receiver {
        val (loadedId, loadedUrl) = loaded ?: return Receiver.GONE
        if (id != loadedId) return Receiver.GONE
        // Cubic r11 / Codex r6: no session is the end (a closed player's fragment never hears it); no client in a
        // current session is a reconnect gap
        val client = client() ?: return if (sessionUp()) Receiver.UNKNOWN else Receiver.GONE
        val url = client.mediaInfo?.contentId ?: return Receiver.UNKNOWN
        if (url != loadedUrl) return Receiver.GONE
        return if (client.hasMediaSession()) Receiver.OURS else Receiver.UNKNOWN
    }

    /** An admin removal of [id]: stops the receiver only while it plays this app's load of [id]. */
    fun stop(id: String) { if (casting(id)) client()?.stop() }

    /**
     * From a closed player: ask [removed] each [intervalMs] while [id] is cast; a removal stops the receiver. Taken while
     * this app's load of [id] is still pending on a live session (Codex r4: the screen closed before the receiver
     * reported it); each tick then needs the receiver to play it.
     */
    fun watch(id: String, intervalMs: Long, removed: suspend () -> Boolean) {
        if (receiver(id) == Receiver.GONE) return
        job?.cancel()
        watchedId = id
        job = scope.launch {
            while (true) {
                delay(intervalMs)
                when (receiver(id)) {
                    Receiver.GONE -> return@launch
                    Receiver.UNKNOWN -> continue
                    Receiver.OURS -> if (removed() && casting(id)) { stop(id); return@launch }
                }
            }
        }
    }

    /** The reopened player watches [id] itself again. */
    fun release(id: String) { if (watchedId == id) job?.cancel() }
}
