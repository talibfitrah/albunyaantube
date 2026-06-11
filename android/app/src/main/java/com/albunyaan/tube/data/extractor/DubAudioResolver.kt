package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.data.extractor.potoken.WebViewPoTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import javax.inject.Inject
import javax.inject.Singleton

/** A web-client audio stream candidate for one language (direct MWEB URL, pre-nsig/pot). */
data class DubStreamCandidate(
    val languageCode: String,
    val bitrate: Int,
    val url: String,
    val mimeType: String?,
)

/**
 * Resolves ONE chosen dub language to a streamable audio [AudioTrack].
 *
 * NewPipe v0.26.2 fetches only the ANDROID/iOS clients for streams (the WEB client is
 * used for microformat JSON only), so it cannot supply dubs. Instead we hand-roll the
 * MWEB resolve (direct dub URLs, verified in the spike), then apply the two transforms a
 * web-family URL needs to stream: NewPipe's PUBLIC nsig solver
 * ([YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated]) and the
 * videoId-bound GVS web poToken ([WebViewPoTokenProvider.getWebClientPoToken]). The VR
 * video stays the playback spine — only the audio is sourced here.
 * See memory/player-dubs-phase2-spike.md (FINDING 5).
 */
@Singleton
class DubAudioResolver @Inject constructor(
    private val enumerator: DubAudioEnumerator,
    private val poTokenProvider: WebViewPoTokenProvider,
) {

    /**
     * Resolve the chosen language's audio stream. Returns null on ANY failure so the
     * caller falls back to the VR original audio (never breaks playback). Must be called
     * off the main thread (poToken minting blocks on a WebView).
     */
    suspend fun resolveDubAudio(videoId: String, languageCode: String): AudioTrack? =
        withContext(Dispatchers.IO) {
            try {
                val json = enumerator.fetchMwebPlayerResponse(videoId) ?: return@withContext null
                val best = selectAudioStream(parseCandidates(json, languageCode), languageCode)
                    ?: return@withContext null
                // (1) nsig: deobfuscate the `n` throttling param via NewPipe's player JS.
                val nsigUrl = YoutubeJavaScriptPlayerManager
                    .getUrlWithThrottlingParameterDeobfuscated(videoId, best.url)
                // (2) GVS web poToken bound to the videoId — without it every segment 403s.
                val pot = poTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken
                    ?: return@withContext null
                AudioTrack(
                    url = appendPot(nsigUrl, pot),
                    mimeType = best.mimeType,
                    bitrate = best.bitrate,
                    codec = null,
                    language = languageCode,
                    trackType = AudioTrackKind.DUBBED,
                    source = AudioTrackSource.WEB_DUB,
                )
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "dub resolve failed $videoId/$languageCode: ${t.message}")
                null
            }
        }

    companion object {
        private const val TAG = "DubAudioResolver"

        /** Pick the highest-bitrate candidate matching [languageCode], or null if absent. */
        fun selectAudioStream(
            candidates: List<DubStreamCandidate>,
            languageCode: String,
        ): DubStreamCandidate? =
            candidates.filter { it.languageCode == languageCode }.maxByOrNull { it.bitrate }

        /** Parse the audio-only MWEB formats for [languageCode] (direct URLs only). */
        fun parseCandidates(json: JSONObject, languageCode: String): List<DubStreamCandidate> {
            val formats = json.optJSONObject("streamingData")
                ?.optJSONArray("adaptiveFormats") ?: return emptyList()
            val out = ArrayList<DubStreamCandidate>()
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType", "")
                if (!mime.startsWith("audio")) continue
                val at = f.optJSONObject("audioTrack") ?: continue
                val code = at.optString("id", "").substringBefore('.')
                if (code != languageCode) continue
                val url = f.optString("url", "")
                if (url.isEmpty()) continue // skip signatureCipher-only entries; MWEB gives direct URLs
                out.add(DubStreamCandidate(code, f.optInt("bitrate", 0), url, mime))
            }
            return out
        }

        /** Append the GVS poToken as `&pot=` (or `?pot=` if the URL has no query yet). */
        fun appendPot(url: String, pot: String): String =
            url + (if (url.contains('?')) "&" else "?") + "pot=" + pot
    }
}
