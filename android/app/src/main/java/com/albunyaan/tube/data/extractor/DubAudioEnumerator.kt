package com.albunyaan.tube.data.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import javax.inject.Inject
import javax.inject.Singleton

/** One dub language exposed by the MWEB innertube player response. */
data class DubLanguage(val languageCode: String, val displayName: String, val isOriginal: Boolean)

/**
 * Lists which dub (audio-language) tracks a video exposes, via a single MWEB
 * innertube `/player` call. Listing needs no poToken and no nsig — only
 * streaming the tracks does (see [DubAudioResolver]). MWEB is used because it
 * returns the full dub list with direct URLs (verified in the spike:
 * memory/player-dubs-phase2-spike.md). The call requires a bootstrapped
 * visitorData (a bare request returns UNPLAYABLE).
 */
@Singleton
class DubAudioEnumerator @Inject constructor() {

    @Volatile private var visitorData: String? = null
    @Volatile private var cookieHeader: String? = null
    private val lock = Any()

    /**
     * Returns the distinct dub languages for [videoId], or empty on any failure /
     * fewer than two languages. Never throws — a failed enumerate just leaves the
     * globe hidden (identical to today's behavior).
     */
    suspend fun enumerate(videoId: String): List<DubLanguage> {
        val json = fetchMwebPlayerResponse(videoId) ?: return emptyList()
        val dubs = parseDubLanguages(json)
        android.util.Log.i(TAG, "enumerate $videoId -> ${dubs.size} dub langs")
        return dubs
    }

    /**
     * Fetch the raw MWEB player response (bootstrapped visitorData + POST). Shared by
     * [enumerate] (lists languages) and [DubAudioResolver] (extracts the chosen audio
     * stream). Returns null on any failure. No poToken, no nsig — listing/URL extraction
     * is free; only streaming the URLs needs the pot + nsig.
     */
    suspend fun fetchMwebPlayerResponse(
        videoId: String,
        signatureTimestamp: Int = SIGNATURE_TIMESTAMP,
        client: DubClient = DubClient.MWEB,
    ): JSONObject? = withContext(Dispatchers.IO) {
        // Validate at the boundary: videoId is interpolated raw into the player-request JSON template, so
        // reject anything that isn't a real 11-char YouTube id (defense-in-depth — it's always app-sourced).
        if (!VIDEO_ID_REGEX.matches(videoId)) {
            android.util.Log.w(TAG, "fetchMwebPlayerResponse: invalid videoId format, skipping")
            return@withContext null
        }
        try {
            val session = ensureSession()
            val headers = linkedMapOf(
                "Content-Type" to listOf("application/json"),
                "User-Agent" to listOf(client.userAgent),
                "X-Youtube-Client-Name" to listOf(client.clientNameHeader),
                "X-Youtube-Client-Version" to listOf(client.clientVersion),
                "X-Goog-Visitor-Id" to listOf(session.visitorData),
                "Origin" to listOf(client.origin),
                "Cookie" to listOf(session.cookieHeader),
            )
            val body = playerRequestBody(videoId, session.visitorData, signatureTimestamp, client)
            val resp = NewPipe.getDownloader().post(PLAYER_URL, headers, body.toByteArray(Charsets.UTF_8))
            android.util.Log.i(TAG, "${client.clientName} fetch $videoId: ${resp.responseBody().length}b status=${resp.responseCode()}")
            JSONObject(resp.responseBody())
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "${client.clientName} player fetch failed for $videoId: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private data class Session(val visitorData: String, val cookieHeader: String)

    // DRY follow-up: this mirrors AndroidVrStreamResolver.ensureSession. Kept separate so the
    // working VR resolve path is untouched; a shared YoutubeSessionProvider could unify them later.
    private fun ensureSession(): Session {
        synchronized(lock) {
            val vd = visitorData
            val ck = cookieHeader
            if (vd != null && ck != null) return Session(vd, ck)
            val headers = linkedMapOf(
                "User-Agent" to listOf(WEB_USER_AGENT),
                "Cookie" to listOf("SOCS=CAI"),
            )
            val resp = NewPipe.getDownloader().get(BOOTSTRAP_URL, headers)
            val bodyVd = VISITOR_DATA_REGEX.find(resp.responseBody())?.groupValues?.get(1)
                ?: throw java.io.IOException("MWEB bootstrap: no visitorData")
            val visitorCookie = setCookieValue(resp.responseHeaders(), "VISITOR_INFO1_LIVE")
            val cookie = buildString {
                append("SOCS=CAI")
                if (visitorCookie != null) append("; VISITOR_INFO1_LIVE=").append(visitorCookie)
            }
            visitorData = bodyVd
            cookieHeader = cookie
            return Session(bodyVd, cookie)
        }
    }

    // String template: every interpolated value is quote/backslash-free, so no JSON escaping needed.
    // signatureTimestamp MUST match the player the caller uses for nsig — otherwise the URL's `n`
    // challenge is minted for a different player than the one transforming it → segment fetch 403s.
    private fun playerRequestBody(
        videoId: String,
        visitorData: String,
        signatureTimestamp: Int,
        client: DubClient,
    ): String =
        "{\"context\":{\"client\":{" +
            "\"clientName\":\"${client.clientName}\",\"clientVersion\":\"${client.clientVersion}\"," +
            "\"userAgent\":\"${client.userAgent}\"," +
            "\"hl\":\"en\",\"timeZone\":\"UTC\",\"utcOffsetMinutes\":0," +
            "\"visitorData\":\"$visitorData\"}}," +
            "\"videoId\":\"$videoId\"," +
            "\"playbackContext\":{\"contentPlaybackContext\":{" +
            "\"html5Preference\":\"HTML5_PREF_WANTS\",\"signatureTimestamp\":$signatureTimestamp}}," +
            "\"contentCheckOk\":true,\"racyCheckOk\":true}"

    /**
     * Innertube client used to mint dub URLs. [MWEB] lists languages cheaply but its direct URLs are
     * SABR-gated to a ~1 MB preview (403 past the boundary). [WEB_SAFARI] — the WEB client with a
     * Safari User-Agent — is served sustainable full direct URLs (verified end-to-end in
     * memory/player-dubs-phase2-spike.md RE-INVESTIGATION: full 58 MB dub). The segment-fetch UA must
     * equal [userAgent] (the URL is bound to the minting client's UA).
     */
    enum class DubClient(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientNameHeader: String,
        val origin: String,
    ) {
        MWEB(
            clientName = "MWEB",
            clientVersion = "2.20250120.00.00",
            userAgent = com.albunyaan.tube.util.HttpConstants.YOUTUBE_MWEB_USER_AGENT,
            clientNameHeader = "2",
            origin = "https://m.youtube.com",
        ),
        WEB_SAFARI(
            clientName = "WEB",
            clientVersion = "2.20250120.00.00",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/15.6 Safari/605.1.15",
            clientNameHeader = "1",
            origin = "https://www.youtube.com",
        ),
    }

    companion object {
        private const val TAG = "DubAudioEnumerator"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val BOOTSTRAP_URL = "https://www.youtube.com/?themeRefresh=1"
        private const val SIGNATURE_TIMESTAMP = 20606
        private val VISITOR_DATA_REGEX = Regex("\"visitorData\":\"([^\"]+)\"")
        private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

        private fun setCookieValue(headers: Map<String, List<String>>, name: String): String? {
            val cookies = headers.entries
                .firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }
                ?.value ?: return null
            val prefix = "$name="
            return cookies.firstOrNull { it.startsWith(prefix) }
                ?.substringAfter(prefix)
                ?.substringBefore(";")
        }

        /**
         * Parse distinct dub languages from a player response. Returns empty when
         * the video has fewer than two audio languages (no picker needed) — the
         * `availableAudioLanguages()` gate then keeps the globe hidden.
         */
        fun parseDubLanguages(json: JSONObject): List<DubLanguage> {
            val formats = json.optJSONObject("streamingData")
                ?.optJSONArray("adaptiveFormats") ?: return emptyList()
            val byCode = LinkedHashMap<String, DubLanguage>()
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val at = f.optJSONObject("audioTrack") ?: continue
                // id is like "en.4" / "ar.3"; the language is the part before the dot.
                val code = at.optString("id", "").substringBefore('.')
                    .takeIf { it.isNotBlank() } ?: continue
                val display = at.optString("displayName", "").ifBlank { code }
                val isOriginal = at.optBoolean("audioIsDefault", false) ||
                    display.trim().endsWith("original", ignoreCase = true)
                if (!byCode.containsKey(code)) {
                    byCode[code] = DubLanguage(code, display, isOriginal)
                }
            }
            return if (byCode.size <= 1) emptyList() else byCode.values.toList()
        }
    }
}
