package com.albunyaan.tube.data.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.extractor.nsig.NsigSolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import javax.inject.Inject
import javax.inject.Singleton

/** A web-client audio stream candidate for one language (direct MWEB URL, pre-nsig/pot). */
data class DubStreamCandidate(
    val languageCode: String,
    val bitrate: Int,
    val url: String,
    val mimeType: String?,
    /** SegmentBase byte ranges + itag/codec for DASH MPD injection (architecture B). Null if the
     *  format lacks initRange/indexRange (then the dub falls back to the progressive merge path). */
    val dashMetadata: SyntheticDashMetadata? = null,
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
    private val poTokenProvider: PoTokenProvider,
    private val nsigSolver: NsigSolver,
) {

    /**
     * Resolve the chosen language's audio stream. Returns null on ANY failure so the
     * caller falls back to the VR original audio (never breaks playback). Must be called
     * off the main thread (poToken minting blocks on a WebView).
     */
    suspend fun resolveDubAudio(videoId: String, languageCode: String): AudioTrack? =
        withContext(Dispatchers.IO) {
            try {
                // Fetch the player response for the SAME player nsig will use — a stale/mismatched
                // signatureTimestamp mints an `n` challenge for a different player than the one
                // transforming it, which 403s every segment from byte 0.
                val sts = nsigSolver.signatureTimestamp()
                // MWEB gives direct (rawUrl) dub URLs; WEB/web_safari are now SABR-only for dubs.
                val json = enumerator.fetchMwebPlayerResponse(videoId, sts, DubAudioEnumerator.DubClient.MWEB)
                    ?: return@withContext null
                val candidates = parseCandidates(json, languageCode)
                android.util.Log.d(
                    "DubFlow",
                    "dub candidates $videoId/$languageCode: ${candidates.size} direct-URL audio formats" +
                        if (candidates.isEmpty()) " | ${describeAudioFormats(json, languageCode)}" else "",
                )
                val best = selectAudioStream(candidates, languageCode)
                    ?: return@withContext null
                // (1) nsig: deobfuscate the `n` throttling param by running the FULL player JS in a
                // WebView. NewPipe's Rhino extractor is broken on the current player (extraction +
                // scope starvation — see memory/player-dubs-phase2-spike.md); the WebView solver works.
                val nsigUrl = nsigSolver.deobfuscateUrl(best.url) ?: run {
                    android.util.Log.w(TAG, "nsig solve failed $videoId/$languageCode")
                    return@withContext null
                }
                // (2) GVS poToken bound to the videoId — without it every segment 403s. A backend-minted
                // videoId-bound pot is sps=3 (sustains, proven on-device); fetchServerPot hits the prod
                // backend endpoint in every build (plus a local bgutil stand-in in debug). The on-device
                // WebView pot is only sps=2 (1 MB preview cap) — a dub built on it plays then 403s
                // mid-stream, which breaks playback — so it is a DEBUG-ONLY fallback. In release we use
                // the server pot or keep the VR original (never ship a language that fails mid-stream).
                val serverPot = fetchServerPot(videoId)
                val pot = serverPot
                    ?: (if (BuildConfig.DEBUG) poTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken else null)
                    ?: return@withContext null
                val finalUrl = appendPot(nsigUrl, pot)
                android.util.Log.d(
                    "DubFlow",
                    "dubURL $videoId/$languageCode potSrc=${if (serverPot != null) "SERVER(sps3)" else "webview(sps2)"} url=$finalUrl"
                )
                AudioTrack(
                    url = finalUrl,
                    mimeType = best.mimeType,
                    bitrate = best.bitrate,
                    codec = best.dashMetadata?.codec,
                    syntheticDashMetadata = best.dashMetadata,
                    language = languageCode,
                    trackType = AudioTrackKind.DUBBED,
                    source = AudioTrackSource.WEB_DUB,
                )
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "dub resolve failed $videoId/$languageCode: ${t.message}")
                null
            }
        }

    /**
     * A single bare OkHttp client for pot fetches — deliberately NOT the app's injected client: no app
     * interceptors/auth headers (these go to our backend and, in debug, the local sidecar). Reused across
     * calls so each dub pick doesn't spin up a fresh dispatcher + connection pool.
     */
    private val httpClient = OkHttpClient()

    /**
     * Fetch a videoId-bound GVS poToken from the backend pot service (sps=3, sustains). Debug stand-in
     * is a local bgutil server reached via `adb reverse tcp:4416 tcp:4416`. Returns null on any failure
     * so the caller falls back to the on-device WebView pot (a failed dub then keeps the VR original).
     */
    private fun fetchServerPot(videoId: String): String? {
        val encodedId = java.net.URLEncoder.encode(videoId, "UTF-8")
        // Production: the backend pot endpoint (BotGuard sidecar behind it) — `/api/v1/dub-potoken`.
        fetchPot(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/v1/dub-potoken?videoId=$encodedId", null)
            ?.let { return it }
        // Debug stand-in: the local bgutil sidecar reached via `adb reverse tcp:4416 tcp:4416`, used
        // while the prod endpoint isn't deployed. No production fallback — the on-device WebView pot
        // is sps=2 (1 MB cap) and would just fail the late ranges, so we keep VR original instead.
        return if (BuildConfig.DEBUG) {
            fetchPot("http://localhost:4416/get_pot", JSONObject().put("content_binding", videoId).toString())
        } else {
            null
        }
    }

    /** Fetch a `{"poToken":"…"}` endpoint (GET when [jsonBody] is null, else POST). Null on any failure. */
    private fun fetchPot(url: String, jsonBody: String?): String? = try {
        val builder = Request.Builder().url(url)
        if (jsonBody != null) builder.post(jsonBody.toRequestBody("application/json".toMediaType()))
        httpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else JSONObject(resp.body?.string() ?: "").optString("poToken").ifEmpty { null }
        }
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "server pot fetch failed ($url): ${t.message}")
        null
    }

    /**
     * Resolve ALL dub languages in one pass — one MWEB fetch, ONE nsig solve (the `n=` is identical
     * across every format, so one solve covers all 14), one pot. Returns a fully-resolved [AudioTrack]
     * per language. Used to PREWARM at enumerate so a later language pick is instant (skips the ~3 s
     * per-switch nsig). Empty on any failure (caller keeps the lazy single-language resolve path).
     */
    suspend fun resolveAllDubAudio(videoId: String): List<AudioTrack> = withContext(Dispatchers.IO) {
        try {
            val sts = nsigSolver.signatureTimestamp()
            val json = enumerator.fetchMwebPlayerResponse(videoId, sts, DubAudioEnumerator.DubClient.MWEB)
                ?: return@withContext emptyList()
            val bestByLang = parseAllCandidates(json)
            if (bestByLang.isEmpty()) return@withContext emptyList()
            // `n` is normally identical across a video's formats, but don't assume it: solve each
            // DISTINCT n once (cached in solvedByRawN — usually one entry) and map each URL with ITS OWN
            // solved value. A blind shared-n replace would no-op, then 403 on switch, for any language
            // whose n differs. Release uses the sps=3 server pot only (sps=2 WebView pot 403s mid-stream).
            val pot = fetchServerPot(videoId)
                ?: (if (BuildConfig.DEBUG) poTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken else null)
                ?: return@withContext emptyList()
            val solvedByRawN = HashMap<String, String?>()
            val out = bestByLang.values.mapNotNull { c ->
                val rawN = N_PARAM_REGEX.find(c.url)?.groupValues?.get(1)
                val solvedN = if (rawN == null) null else solvedByRawN.getOrPut(rawN) { nsigSolver.solveN(rawN) }
                if (rawN == null || solvedN == null) {
                    android.util.Log.w(TAG, "prewarm: nsig solve failed for ${c.languageCode}, skipping")
                    return@mapNotNull null
                }
                val nsigUrl = c.url.replaceFirst("n=$rawN", "n=$solvedN")
                AudioTrack(
                    url = appendPot(nsigUrl, pot),
                    mimeType = c.mimeType,
                    bitrate = c.bitrate,
                    codec = c.dashMetadata?.codec,
                    syntheticDashMetadata = c.dashMetadata,
                    language = c.languageCode,
                    trackType = AudioTrackKind.DUBBED,
                    source = AudioTrackSource.WEB_DUB,
                )
            }
            android.util.Log.d("DubFlow", "prewarm resolved ${out.size} dub languages for $videoId (1 nsig, 1 pot)")
            out
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "resolveAllDubAudio failed $videoId: ${t.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "DubAudioResolver"
        private val N_PARAM_REGEX = Regex("[?&]n=([^&]+)")

        /** Best direct-URL candidate (highest bitrate) per dub language. */
        fun parseAllCandidates(json: JSONObject): Map<String, DubStreamCandidate> {
            val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                ?: return emptyMap()
            val byLang = HashMap<String, DubStreamCandidate>()
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType", "")
                if (!mime.startsWith("audio")) continue
                val at = f.optJSONObject("audioTrack") ?: continue
                val code = at.optString("id", "").substringBefore('.')
                if (code.isEmpty()) continue
                val url = f.optString("url", "")
                if (url.isEmpty()) continue
                val c = DubStreamCandidate(code, f.optInt("bitrate", 0), url, mime, parseDashMetadata(f, mime))
                val existing = byLang[code]
                if (existing == null || c.bitrate > existing.bitrate) byLang[code] = c
            }
            return byLang
        }

        /** Diagnostic: summarise audio formats + how the target language's dub is delivered. */
        fun describeAudioFormats(json: JSONObject, languageCode: String): String {
            val sd = json.optJSONObject("streamingData") ?: return "no streamingData"
            val formats = sd.optJSONArray("adaptiveFormats") ?: return "no adaptiveFormats"
            var audio = 0
            var langMatch = 0
            var withUrl = 0
            var withCipher = 0
            var sampleCipher = ""
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                if (!f.optString("mimeType").startsWith("audio")) continue
                audio++
                val at = f.optJSONObject("audioTrack") ?: continue
                if (at.optString("id").substringBefore('.') != languageCode) continue
                langMatch++
                if (f.optString("url").isNotEmpty()) withUrl++
                val cipher = f.optString("signatureCipher")
                if (cipher.isNotEmpty()) {
                    withCipher++
                    if (sampleCipher.isEmpty()) sampleCipher = cipher.take(60)
                }
            }
            return "audioFmts=$audio lang[$languageCode]=$langMatch url=$withUrl cipher=$withCipher " +
                "topSabr=${sd.has("serverAbrStreamingUrl")} sample=$sampleCipher"
        }

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
                out.add(DubStreamCandidate(code, f.optInt("bitrate", 0), url, mime, parseDashMetadata(f, mime)))
            }
            return out
        }

        /** Extract SegmentBase ranges (init/index) + itag/codec from a MWEB adaptiveFormat for DASH
         *  MPD injection. Null if ranges are absent (dub then uses the progressive merge fallback). */
        fun parseDashMetadata(f: JSONObject, mime: String): SyntheticDashMetadata? {
            val init = f.optJSONObject("initRange") ?: return null
            val index = f.optJSONObject("indexRange") ?: return null
            val initStart = init.optString("start").toLongOrNull() ?: return null
            val initEnd = init.optString("end").toLongOrNull() ?: return null
            val indexStart = index.optString("start").toLongOrNull() ?: return null
            val indexEnd = index.optString("end").toLongOrNull() ?: return null
            val codec = Regex("codecs=\"?([^\";]+)").find(mime)?.groupValues?.get(1)
            return SyntheticDashMetadata(
                itag = f.optInt("itag"),
                initStart = initStart, initEnd = initEnd,
                indexStart = indexStart, indexEnd = indexEnd,
                approxDurationMs = f.optString("approxDurationMs").toLongOrNull(),
                codec = codec,
            )
        }

        /** Append the GVS poToken as `&pot=` (or `?pot=` if the URL has no query yet). */
        fun appendPot(url: String, pot: String): String =
            url + (if (url.contains('?')) "&" else "?") + "pot=" + pot
    }
}
