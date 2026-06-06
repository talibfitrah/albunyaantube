package com.albunyaan.tube.data.extractor.potoken

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.albunyaan.tube.BuildConfig
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
        if (!webViewSupported || webViewBadImpl) {
            return null
        }
        return try {
            obtainPoToken(videoId = videoId, forceRecreate = false)
        } catch (e: BadWebViewException) {
            Log.e(TAG, "Could not obtain poToken because WebView is broken; disabling for session", e)
            webViewBadImpl = true
            null
        } catch (e: Throwable) {
            // A null token surfaces as the pre-fix behavior (tokenless URLs); do not crash
            // extraction by rethrowing transient BotGuard/network failures.
            Log.e(TAG, "Failed to obtain poToken for $videoId", e)
            null
        }
    }

    /**
     * @param forceRecreate recreate [poTokenGenerator] from scratch, used when the previous
     * generator threw while minting a token (e.g. the WebView content was lost in the background).
     */
    private fun obtainPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val generator: PoTokenGenerator
        val visitorData: String
        val streamingPot: String
        val hasBeenRecreated: Boolean

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

            generator = poTokenGenerator!!
            visitorData = poTokenVisitorData!!
            streamingPot = poTokenStreamingPot!!
            hasBeenRecreated = shouldRecreate
        }

        val playerPot = try {
            // Not under [lock]: the generator can mint multiple player tokens in parallel. The only
            // ordering requirement (streaming token generated first) is satisfied above.
            runBlocking { generator.generatePoToken(videoId) }
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // Already recreated this round; nothing more we can do.
                throw throwable
            }
            Log.e(TAG, "Failed to obtain poToken, recreating generator and retrying", throwable)
            return obtainPoToken(videoId = videoId, forceRecreate = true)
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "poToken for $videoId obtained (player + streaming + visitorData)")
        }

        return PoTokenResult(visitorData, playerPot, streamingPot)
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
    }
}
