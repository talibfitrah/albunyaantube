package com.albunyaan.tube.data.extractor.nsig

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import org.schabi.newpipe.extractor.NewPipe

/**
 * Deobfuscates YouTube streaming-URL throttling (`n`) parameters via [NsigWebView].
 *
 * Web-family dub audio URLs (the only fully-streamable dub source — iOS/Android are SABR-capped at
 * 1 MB, MWEB is SABR-skipped) carry an `n=` param that must be transformed by running the player's
 * JS, or every segment 403s from byte 0. NewPipe v0.26.2's Rhino extractor mis-extracts the
 * function on current players, so we fetch the full player code and solve it in a WebView (V8).
 * See memory/player-dubs-phase2-spike.md.
 *
 * The player JS (~2.7 MB) is video-independent and version-stable, so it is fetched once and cached
 * by player hash. The WebView is created lazily and reused; on any failure it is dropped so the
 * next call recreates it. Every method returns null / the original URL on failure so the dub path
 * falls back to VR original audio and never breaks playback.
 */
@Singleton
class NsigSolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private data class PlayerCode(val hash: String, val code: String, val signatureTimestamp: Int)

    private val fetchLock = Mutex()
    @Volatile private var cachedPlayer: PlayerCode? = null

    private val webViewLock = Mutex()
    @Volatile private var webView: NsigWebView? = null

    /**
     * Replace the `n=` throttling param in [url] with its deobfuscated value. Returns the URL
     * unchanged if it has no `n=` param, or null if solving fails (caller should then abandon the
     * dub and keep VR original). Must be called off the main thread.
     */
    suspend fun deobfuscateUrl(url: String): String? {
        val match = N_PARAM_REGEX.find(url) ?: return url // no n= param: nothing to transform
        val n = match.groupValues[1]
        val solved = solveN(n) ?: return null
        // Replace only the n= value (URL-safe chars, no re-encoding), keeping the rest intact.
        return url.substring(0, match.range.first) +
            match.value.replace("n=$n", "n=$solved") +
            url.substring(match.range.last + 1)
    }

    /** Solve a single throttling parameter. Null on any failure. */
    suspend fun solveN(n: String): String? = withContext(Dispatchers.IO) {
        val player = try {
            fetchPlayerCode()
        } catch (t: Throwable) {
            Log.w(TAG, "nsig player fetch failed: ${t.javaClass.simpleName} ${t.message}")
            return@withContext null
        }
        // Retry across full WebView recreation. Under live playback the sandbox renderer is often
        // low-memory-killed (ExoPlayer + PoTokenWebView + NsigWebView contend), surfacing as a
        // renderer-gone / timeout on the FIRST solve; a fresh WebView a moment later usually survives.
        // Without this, a transient kill returned null and the dub silently fell back to VR original
        // (the "selected a dub and nothing happened" symptom).
        repeat(SOLVE_ATTEMPTS) { attempt ->
            try {
                val solved = ensureWebView().solve(player.code, listOf(n))[n]
                Log.i(
                    TAG,
                    "nsig solved (${n.take(10)}… -> ${solved?.take(10)}…)" +
                        if (attempt > 0) " [attempt ${attempt + 1}]" else "",
                )
                return@withContext solved
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "nsig solve attempt ${attempt + 1}/$SOLVE_ATTEMPTS failed for n=${n.take(12)}: " +
                        "${t.javaClass.simpleName} ${t.message}",
                )
                // Drop the (possibly dead) WebView so the next attempt starts clean.
                webViewLock.withLock {
                    runCatching { webView?.close() }
                    webView = null
                }
            }
        }
        null
    }

    /** Pre-warm the player fetch + WebView so the first user-visible solve is fast (Phase: prewarm). */
    suspend fun prewarm() {
        runCatching {
            withContext(Dispatchers.IO) {
                fetchPlayerCode()
                ensureWebView()
            }
        }
    }

    /**
     * The signatureTimestamp of the SAME player used for nsig. The WEB `/player` request must send
     * this sts (not NewPipe's, which may cache a different player version) — otherwise the server
     * mints the streaming URL for one player while we transform `n` with another, and every segment
     * 403s. Returns 0 on failure (the request then omits a meaningful sts).
     */
    suspend fun signatureTimestamp(): Int = withContext(Dispatchers.IO) {
        runCatching { fetchPlayerCode().signatureTimestamp }.getOrDefault(0)
    }

    private suspend fun ensureWebView(): NsigWebView {
        webView?.let { return it }
        return webViewLock.withLock {
            webView ?: NsigWebView.create(context).also { webView = it }
        }
    }

    private suspend fun fetchPlayerCode(): PlayerCode = fetchLock.withLock {
        // Reuse the first fetched player for the whole process. iframe_api returns ROTATING hashes
        // (YouTube A/B-tests players), so re-fetching per call yields a DIFFERENT player for the
        // `/player` sts vs the nsig transform — a mismatch that 403s every segment. One player, both
        // uses. (Staleness across a long session is a P5 refinement: invalidate on repeated 403.)
        cachedPlayer?.let { return@withLock it }
        val downloader = NewPipe.getDownloader()
        val iframe = downloader.get(IFRAME_API_URL).responseBody()
        val hash = PLAYER_HASH_REGEX.find(iframe)?.groupValues?.get(1)
            ?: throw java.io.IOException("nsig: no player hash in iframe_api")
        val baseJsUrl = "https://www.youtube.com/s/player/$hash/$PLAYER_VARIANT/base.js"
        val code = downloader.get(baseJsUrl).responseBody()
        val sts = STS_REGEX.find(code)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        Log.i(TAG, "fetched player $hash (${code.length} chars, sts=$sts)")
        PlayerCode(hash, code, sts).also { cachedPlayer = it }
    }

    companion object {
        private const val TAG = "NsigSolver"
        // Recreate-and-retry count for a solve, to ride out low-memory renderer kills during playback.
        private const val SOLVE_ATTEMPTS = 3
        private const val IFRAME_API_URL = "https://www.youtube.com/iframe_api"
        // ias variant matches NewPipe's fetch and is solver-compatible (verified). en_US locale.
        private const val PLAYER_VARIANT = "player_ias.vflset/en_US"
        // iframe_api embeds the URL with escaped slashes (\/s\/player\/HASH\/), so allow an
        // optional backslash before each slash.
        private val PLAYER_HASH_REGEX = Regex("""\\?/s\\?/player\\?/([0-9a-fA-F]{8})""")
        private val N_PARAM_REGEX = Regex("[?&]n=([^&]+)")
        private val STS_REGEX = Regex("(?:signatureTimestamp|sts)[\"']?\\s*[:=]\\s*(\\d{4,7})")
    }
}
