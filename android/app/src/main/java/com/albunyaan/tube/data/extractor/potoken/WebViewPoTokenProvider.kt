package com.albunyaan.tube.data.extractor.potoken

import android.content.Context
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Supplies WebView-minted poTokens to NewPipeExtractor's YouTube client, fixing the HTTP 403
 * "Resolving stream…" loop caused by YouTube requiring a GVS PO Token on stream URLs.
 *
 * **Version contract:** NewPipeExtractor 0.26.2's `YoutubeStreamExtractor` consumes the token only
 * via [getAndroidClientPoToken] / [getIosClientPoToken] (verified in bytecode — it never calls
 * `getWebClientPoToken`). NewPipe *master* moved to a web-client poToken; that newer contract is
 * NOT what this app's pinned 0.26.2 uses. So we return the same WebView-minted token from every
 * streaming-client getter, letting the extractor apply it to whichever client it actually fetches
 * streams with. The app keeps the iOS client on (`getIosClientPoToken`): on-device it returns the
 * full HD/4K adaptive ladder (up to 16 video-only reps, 2160p) once the token is supplied, whereas
 * the android client is SABR-gutted to a single 360p muxed stream. The web-context visitorData +
 * token is accepted by the iOS client (verified: pot applied, HTTP 206, full ladder).
 *
 * The extractor calls these methods synchronously from a background extraction thread, so we bridge
 * the suspend [PoTokenGenerator] with [runBlocking]. This must never be invoked on the main thread
 * (it would deadlock); extraction always runs on Dispatchers.IO via GlobalStreamResolver.
 */
class WebViewPoTokenProvider(private val context: Context) : PoTokenProvider {

    private val webViewSupported: Boolean by lazy { supportsWebView() }

    @Volatile
    private var webViewBadImpl = false

    private val lock = Any()
    private var poTokenVisitorData: String? = null
    private var poTokenStreamingPot: String? = null
    private var poTokenGenerator: PoTokenGenerator? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = poTokenOrNull(videoId)

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = poTokenOrNull(videoId)

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = poTokenOrNull(videoId)

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    private fun poTokenOrNull(videoId: String): PoTokenResult? {
        // runBlocking here would deadlock on the main thread: the WebView callbacks that resolve the
        // awaited deferred are posted to the main looper, which is the very thread we'd be blocking.
        // Extraction always runs on Dispatchers.IO, so a main-thread call is a programming error —
        // degrade to a tokenless result (ANDROID_VR fallback) rather than ANR.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "poToken requested on main thread; returning null to avoid deadlock ($videoId)")
            return null
        }
        if (!webViewSupported || webViewBadImpl) {
            Log.w(TAG, "poToken requested but unavailable (supported=$webViewSupported, bad=$webViewBadImpl) for $videoId")
            return null
        }
        Log.i(TAG, "poToken requested for $videoId")
        var lastError: Throwable? = null
        // Retry across full generator recreation. On Android <= 28 the WebView renderer is often
        // low-memory-killed on the first attempt (while ExoPlayer/Cronet start up), but a fresh
        // WebView a few hundred ms later usually survives. Renderer death is now reported
        // immediately (see PoTokenWebView.onRenderProcessGone) instead of stalling for the full
        // timeout, so these retries are cheap. Without this, one failure yielded a null token and
        // the tokenless stream URL 403-looped forever ("Resolving stream…" / حل البث).
        repeat(MAX_GENERATION_ATTEMPTS) { attempt ->
            try {
                return obtainPoToken(videoId = videoId, forceRecreate = attempt > 0)
            } catch (e: BadWebViewException) {
                Log.e(TAG, "WebView is broken; disabling poToken for this session", e)
                webViewBadImpl = true
                return null
            } catch (e: InterruptedException) {
                // The blocking await was interrupted because the coroutine driving extraction was
                // cancelled (e.g. screen rotation tearing down the caller's scope mid-fetch) — the
                // NewPipe #12045 crash class. The WebView/generator is app-scoped and survives, so we
                // just abandon THIS fetch: restore the interrupt flag (so the outer cancellation is
                // still observed) and degrade to a tokenless result instead of crashing. Do NOT retry
                // — the thread is being torn down.
                Thread.currentThread().interrupt()
                Log.w(TAG, "poToken interrupted by lifecycle cancellation for $videoId; degrading to tokenless")
                return null
            } catch (e: Throwable) {
                lastError = e
                // Retrying while NewPipe's downloader is in rate-limit cooldown only deepens the
                // cooldown and is guaranteed to fail (the visitorData POST is what's blocked). Bail
                // immediately, keep the existing generator, and let the caller back off naturally.
                if (e.message?.contains("cooldown", ignoreCase = true) == true) {
                    Log.w(TAG, "poToken aborted for $videoId: downloader in cooldown, not retrying")
                    return null
                }
                Log.e(TAG, "poToken attempt ${attempt + 1}/$MAX_GENERATION_ATTEMPTS failed for $videoId", e)
                if (attempt < MAX_GENERATION_ATTEMPTS - 1) {
                    // Interruptible backoff: if cancellation arrives during the wait, bail cleanly
                    // (restore the interrupt flag) instead of letting InterruptedException escape
                    // unprotected into NewPipe's extraction call.
                    try {
                        runBlocking { delay(RETRY_BACKOFF_MS * (attempt + 1)) }
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.w(TAG, "poToken backoff interrupted for $videoId; degrading to tokenless")
                        return null
                    }
                }
            }
        }
        // Give up: drop the (likely dead) generator so the next playback attempt starts clean.
        // A null token surfaces as the pre-fix behavior (tokenless URLs); never crash extraction.
        synchronized(lock) {
            runCatching { poTokenGenerator?.close() }
            poTokenGenerator = null
            poTokenStreamingPot = null
        }
        Log.e(TAG, "Gave up obtaining poToken for $videoId after $MAX_GENERATION_ATTEMPTS attempts", lastError)
        return null
    }

    /**
     * @param forceRecreate recreate [poTokenGenerator] from scratch, used when the previous
     * generator threw while minting a token (e.g. the WebView content was lost in the background).
     */
    private fun obtainPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (generator, visitorData, streamingPot) = ensureWarmGenerator(forceRecreate)

        // Not under [lock]: the generator can mint multiple player tokens in parallel. The only
        // ordering requirement (streaming token generated first) is satisfied in ensureWarmGenerator.
        // Any failure (incl. a renderer kill) propagates to poTokenOrNull(), which recreates+retries.
        val playerPot = runBlocking { generator.generatePoToken(videoId) }

        // GVS (streaming-data) poToken binding. YouTube's `html5_generate_content_po_token`
        // experiment — active on a subset of videos (e.g. One4kids / "Zaky's Learning Club") —
        // requires the GVS `&pot=` token bound to the VIDEO ID, not the visitorData. Those videos
        // are also ANDROID_VR-UNPLAYABLE, so they fall through to this NewPipe poToken path; a
        // visitorData-bound GVS token then makes every segment fetch 403 (verified the video plays
        // with a video-id-bound GVS token via yt-dlp + bgutil). The video-id binding is exactly the
        // player token's binding, so reuse [playerPot] for the streaming slot. This only affects the
        // fallback path (the ANDROID_VR primary uses no poToken at all). visitorData is still passed
        // as arg 1 for the session (X-Goog-Visitor-Id) header.
        val streamingDataPot = playerPot

        Log.i(
            TAG,
            "poToken minted for $videoId " +
                "(visitorData=${visitorData.length}, player=${playerPot.length}, " +
                "streaming=${streamingDataPot.length}, gvsBinding=videoId; warmStreaming=${streamingPot.length})"
        )

        return PoTokenResult(visitorData, playerPot, streamingDataPot)
    }

    /**
     * Ensure the BotGuard generator exists and has minted its (video-independent) streaming token,
     * recreating it when missing / expired / [forceRecreate]. Returns
     * (generator, visitorData, streamingPoToken). The slow WebView init + BotGuard challenge runs
     * under [lock].
     */
    private fun ensureWarmGenerator(forceRecreate: Boolean): Triple<PoTokenGenerator, String, String> {
        synchronized(lock) {
            val shouldRecreate =
                poTokenGenerator == null || forceRecreate || poTokenGenerator!!.isExpired()

            if (shouldRecreate) {
                val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
                innertubeClientRequestInfo.clientInfo.clientVersion =
                    YoutubeParsingHelper.getClientVersion()

                poTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    innertubeClientRequestInfo,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false,
                )

                // tear down the previous generator (no-op if null)
                poTokenGenerator?.let { old -> runCatching { old.close() } }

                poTokenGenerator = runBlocking { PoTokenWebView.newPoTokenGenerator(context) }

                // The streaming poToken must be generated exactly once, before any player tokens.
                poTokenStreamingPot =
                    runBlocking { poTokenGenerator!!.generatePoToken(poTokenVisitorData!!) }
            }

            return Triple(poTokenGenerator!!, poTokenVisitorData!!, poTokenStreamingPot!!)
        }
    }

    private fun supportsWebView(): Boolean = try {
        // CookieManager.getInstance() throws if the system has no usable WebView implementation.
        CookieManager.getInstance()
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Device has no usable WebView; poToken generation unavailable", t)
        false
    }

    companion object {
        private const val TAG = "WebViewPoTokenProvider"

        /**
         * How many times to mint a token, recreating the WebView from scratch each round. Tuned for
         * Android <= 28, where the out-of-process renderer is frequently low-memory-killed during
         * the player startup spike; renderer death now fails fast, so these retries are cheap.
         */
        private const val MAX_GENERATION_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 300L
    }
}
