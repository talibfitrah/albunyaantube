package com.albunyaan.tube.data.extractor

import android.util.Log
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe

/**
 * Resolves YouTube stream URLs via the **ANDROID_VR** innertube client.
 *
 * Why this exists: around 2026-06-06 YouTube began requiring a GVS **poToken** on the WEB / IOS /
 * ANDROID clients that NewPipeExtractor (0.26.2, the latest release) uses. Streams fetched without
 * an accepted token return HTTP 403 — the endless "Resolving stream…/حل البث" loop — and the
 * app's WebView-minted *web-context* token is rejected for the iOS client. The ANDROID_VR client is
 * NOT poToken-gated and returns directly-playable URLs (no JS signature deciphering), so it
 * sidesteps the whole problem — and needs no WebView, which also removes the Android <=28
 * renderer-crash failure mode.
 *
 * The request must look like a real session or YouTube answers `LOGIN_REQUIRED "Sign in to confirm
 * you're not a bot"`. To pass we (1) bootstrap a `visitorData` + consent/visitor cookies from the
 * watch page, then (2) POST the player request with the current ANDROID_VR client version. The
 * client version is YouTube-sensitive (stale versions get bot-flagged) — see [CLIENT_VERSION].
 *
 * Recipe verified end-to-end against live YouTube on 2026-06-06 (status OK, 22 adaptive formats,
 * HTTP 206 on fetch).
 */
class AndroidVrStreamResolver(
    private val clock: () -> Long
) {

    private val lock = Any()
    @Volatile private var visitorData: String? = null
    @Volatile private var cookieHeader: String? = null

    /**
     * @return resolved streams for [videoId], or null if ANDROID_VR can't play it (caller should
     * fall back to the existing NewPipe path).
     */
    fun resolve(videoId: String, urlGeneratedAt: Long): ResolvedStreams? {
        return try {
            var response = requestPlayer(videoId, ensureSession(forceRefresh = false))
            // A stale/blocked visitorData surfaces as LOGIN_REQUIRED — refresh the session once.
            if (playabilityStatus(response) == "LOGIN_REQUIRED") {
                Log.w(TAG, "ANDROID_VR LOGIN_REQUIRED for $videoId; refreshing session and retrying")
                response = requestPlayer(videoId, ensureSession(forceRefresh = true))
            }
            mapResponse(videoId, response, urlGeneratedAt)
        } catch (t: Throwable) {
            Log.w(TAG, "ANDROID_VR resolve failed for $videoId (falling back to NewPipe)", t)
            null
        }
    }

    private fun mapResponse(videoId: String, json: JSONObject, urlGeneratedAt: Long): ResolvedStreams? {
        val playabilityStatus = json.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optStringOrNull("status")
        if (status != null && status != "OK") {
            Log.w(TAG, "ANDROID_VR playabilityStatus=$status for $videoId")
            return null
        }
        val streamingData = json.optJSONObject("streamingData") ?: return null

        // Live (and post-live DVR) streams use a rolling live manifest instead of the fixed
        // byte-range adaptiveFormats this resolver builds synthetic DASH from. A static byte-range
        // VOD can't represent a moving live edge, so ANDROID_VR was silently playing live as broken
        // VOD (isLive was hardcoded false → DashSourceBuilder treated it as VOD → no playback).
        // Defer live to the NewPipe path, which consumes YouTube's real live manifest — verified:
        // NewPipe yields LIVE_STREAM + dash/hls for a live video and DashSourceBuilder's live
        // branch plays it.
        val videoDetails = json.optJSONObject("videoDetails")
        if (isLiveStream(playabilityStatus, videoDetails, streamingData)) {
            Log.i(TAG, "ANDROID_VR sees live stream $videoId; deferring to NewPipe for live manifest")
            return null
        }

        val adaptive = streamingData.optJSONArray("adaptiveFormats")
        val progressive = streamingData.optJSONArray("formats")

        val videoTracks = ArrayList<VideoTrack>()
        val audioTracks = ArrayList<AudioTrack>()

        // Adaptive (separate) video + audio. Keep BOTH mp4 (AAC) and webm (Opus) audio.
        // The multi-rep MPD generator selects the highest-resolution video codec family —
        // which for most modern videos is VP9 (webm-only) — and then container-matches the
        // audio AdaptationSet to the chosen video container. Dropping webm audio here starved
        // every VP9/webm ladder of compatible audio → NO_COMPATIBLE_AUDIO → MPD generation
        // failed → progressive 360p fallback + a re-prepare loop on high-res selection. The
        // generator already filters audio by container, so supplying both containers is safe.
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                val url = f.optStringOrNull("url") ?: continue // VR returns direct urls
                val mime = f.optStringOrNull("mimeType") ?: continue
                when {
                    mime.startsWith("video/") -> videoTracks.add(videoTrack(f, url, mime, videoOnly = true))
                    mime.startsWith("audio/") -> audioTracks.add(audioTrack(f, url, mime))
                }
            }
        }

        // Progressive muxed (itag 18/22) as a last-resort fallback — also a direct VR url.
        if (progressive != null) {
            for (i in 0 until progressive.length()) {
                val f = progressive.optJSONObject(i) ?: continue
                val url = f.optStringOrNull("url") ?: continue
                val mime = f.optStringOrNull("mimeType") ?: continue
                if (mime.startsWith("video/")) videoTracks.add(videoTrack(f, url, mime, videoOnly = false))
            }
        }

        if (videoTracks.isEmpty() && audioTracks.isEmpty()) return null

        val durationSeconds = videoDetails?.optStringOrNull("lengthSeconds")?.toIntOrNull()

        val subtitleTracks = try {
            parseSubtitleTracks(json)
        } catch (t: Throwable) {
            // Captions are non-essential: a parse hiccup must never sink an
            // otherwise-playable VR resolve (which would drop to the inferior
            // NewPipe fallback). Degrade to no-subtitles instead.
            Log.w(TAG, "ANDROID_VR caption parse failed for $videoId; continuing without subtitles", t)
            emptyList()
        }
        Log.i(
            TAG,
            "ANDROID_VR resolved $videoId: ${videoTracks.size} video, ${audioTracks.size} audio, " +
                "${subtitleTracks.size} subtitle tracks"
        )
        return ResolvedStreams(
            streamId = videoId,
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            durationSeconds = durationSeconds,
            hlsUrl = null,
            dashUrl = null,
            isLive = false,
            urlGeneratedAt = urlGeneratedAt,
            extractionClient = ExtractionClient.ANDROID_VR
        )
    }

    private fun videoTrack(f: JSONObject, url: String, mime: String, videoOnly: Boolean): VideoTrack {
        val codec = extractCodec(mime)
        return VideoTrack(
            url = url,
            mimeType = mime,
            width = f.optInt("width", 0).takeIf { it > 0 },
            height = f.optInt("height", 0).takeIf { it > 0 },
            bitrate = f.optInt("bitrate", 0).takeIf { it > 0 },
            qualityLabel = f.optStringOrNull("qualityLabel"),
            fps = f.optInt("fps", 0).takeIf { it > 0 },
            isVideoOnly = videoOnly,
            syntheticDashMetadata = if (videoOnly) dashMetadata(f, codec) else null,
            codec = codec
        )
    }

    private fun audioTrack(f: JSONObject, url: String, mime: String): AudioTrack {
        val codec = extractCodec(mime)
        return AudioTrack(
            url = url,
            mimeType = mime,
            bitrate = f.optInt("bitrate", 0).takeIf { it > 0 },
            codec = codec,
            syntheticDashMetadata = dashMetadata(f, codec)
        )
    }

    /** Build synthetic-DASH ranges from the VR format's byte ranges (strings in YouTube JSON). */
    private fun dashMetadata(f: JSONObject, codec: String?): SyntheticDashMetadata? {
        val init = f.optJSONObject("initRange") ?: return null
        val index = f.optJSONObject("indexRange") ?: return null
        val meta = SyntheticDashMetadata(
            itag = f.optInt("itag", -1),
            initStart = init.optStringOrNull("start")?.toLongOrNull() ?: return null,
            initEnd = init.optStringOrNull("end")?.toLongOrNull() ?: return null,
            indexStart = index.optStringOrNull("start")?.toLongOrNull() ?: return null,
            indexEnd = index.optStringOrNull("end")?.toLongOrNull() ?: return null,
            approxDurationMs = f.optStringOrNull("approxDurationMs")?.toLongOrNull(),
            codec = codec
        )
        return meta.takeIf { it.hasValidRanges() }
    }

    private fun requestPlayer(videoId: String, session: Session): JSONObject {
        val body = playerRequestBody(videoId, session.visitorData)
        val headers = linkedMapOf(
            "Content-Type" to listOf("application/json"),
            "User-Agent" to listOf(USER_AGENT),
            "X-Youtube-Client-Name" to listOf("28"),
            "X-Youtube-Client-Version" to listOf(CLIENT_VERSION),
            "X-Goog-Visitor-Id" to listOf(session.visitorData),
            "Origin" to listOf("https://www.youtube.com"),
            "Cookie" to listOf(session.cookieHeader)
        )
        val response = NewPipe.getDownloader().post(PLAYER_URL, headers, body.toByteArray(Charsets.UTF_8))
        if (response.responseCode() != 200) {
            throw java.io.IOException("ANDROID_VR player HTTP ${response.responseCode()}")
        }
        return JSONObject(response.responseBody())
    }

    private fun playabilityStatus(json: JSONObject): String? =
        json.optJSONObject("playabilityStatus")?.optStringOrNull("status")

    /** Bootstrap (or reuse) a visitorData + consent/visitor cookies from the watch page. */
    private fun ensureSession(forceRefresh: Boolean): Session {
        synchronized(lock) {
            val vd = visitorData
            val ck = cookieHeader
            if (!forceRefresh && vd != null && ck != null) return Session(vd, ck)

            val headers = linkedMapOf(
                "User-Agent" to listOf(WEB_USER_AGENT),
                "Cookie" to listOf("SOCS=CAI")
            )
            val resp = NewPipe.getDownloader().get(BOOTSTRAP_URL, headers)
            val bodyVd = VISITOR_DATA_REGEX.find(resp.responseBody())?.groupValues?.get(1)
                ?: throw java.io.IOException("ANDROID_VR bootstrap: no visitorData")
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

    private data class Session(val visitorData: String, val cookieHeader: String)

    // Built as a string template: every interpolated value (videoId, visitorData, constants) is
    // quote/backslash-free, so no JSON escaping is required.
    private fun playerRequestBody(videoId: String, visitorData: String): String =
        "{\"context\":{\"client\":{" +
            "\"clientName\":\"ANDROID_VR\"," +
            "\"clientVersion\":\"$CLIENT_VERSION\"," +
            "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\"," +
            "\"androidSdkVersion\":32," +
            "\"userAgent\":\"$USER_AGENT\"," +
            "\"osName\":\"Android\",\"osVersion\":\"12L\"," +
            "\"hl\":\"en\",\"timeZone\":\"UTC\",\"utcOffsetMinutes\":0," +
            "\"visitorData\":\"$visitorData\"" +
            "}}," +
            "\"videoId\":\"$videoId\"," +
            "\"playbackContext\":{\"contentPlaybackContext\":{" +
            "\"html5Preference\":\"HTML5_PREF_WANTS\",\"signatureTimestamp\":$SIGNATURE_TIMESTAMP}}," +
            "\"contentCheckOk\":true,\"racyCheckOk\":true}"

    companion object {
        private const val TAG = "AndroidVrResolver"

        // ANDROID_VR client. clientVersion is YouTube-sensitive: stale versions get bot-flagged
        // (1.61.48 failed, 1.65.10 worked on 2026-06-06). Keep in sync with yt-dlp's ANDROID_VR.
        private const val CLIENT_VERSION = "1.65.10"
        private const val USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val BOOTSTRAP_URL = "https://www.youtube.com/?themeRefresh=1"
        private const val SIGNATURE_TIMESTAMP = 20606

        private val VISITOR_DATA_REGEX = Regex("\"visitorData\":\"([^\"]+)\"")

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

        /**
         * True if the player response describes a currently-live or post-live-DVR stream — those
         * use a rolling live manifest the byte-range synthetic-DASH path here can't represent, so
         * they must be deferred to the NewPipe live path.
         *
         * Classified from authoritative live *metadata*, not manifest presence alone:
         *  - `videoDetails.isLive` — currently live.
         *  - `videoDetails.isPostLiveDvr` — the just-ended DVR window (still a rolling manifest).
         *  - `playabilityStatus.liveStreamability` — YouTube attaches this renderer to live/DVR
         *    content; it is absent on ordinary VODs.
         *  - `streamingData.hlsManifestUrl` — YouTube emits HLS only for live/DVR, never plain VOD.
         *
         * Deliberately NOT keyed on `isLiveContent` (stays true for finished VODs of past streams,
         * which play fine on the fast byte-range path) and NOT on `dashManifestUrl` — YouTube also
         * serves server-side DASH manifests for ordinary VODs (yt-dlp's `youtube_include_dash_manifest`),
         * so its presence is not proof of live and would needlessly drop VODs to the slower path.
         */
        internal fun isLiveStream(
            playabilityStatus: JSONObject?,
            videoDetails: JSONObject?,
            streamingData: JSONObject
        ): Boolean =
            videoDetails?.optBoolean("isLive", false) == true ||
                videoDetails?.optBoolean("isPostLiveDvr", false) == true ||
                playabilityStatus?.has("liveStreamability") == true ||
                streamingData.optStringOrNull("hlsManifestUrl") != null

        private fun setCookieValue(
            headers: Map<String, List<String>>,
            name: String
        ): String? {
            val cookies = headers.entries
                .firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }
                ?.value ?: return null
            val prefix = "$name="
            return cookies.firstOrNull { it.startsWith(prefix) }
                ?.substringAfter(prefix)
                ?.substringBefore(";")
        }

        private const val SUBTITLE_FORMAT = "vtt"
        /**
         * Upper bound on parsed caption tracks. Each track becomes a
         * SingleSampleMediaSource merged into the player's MergingMediaSource, so an
         * absurd captionTracks[] (a compromised/MITM'd player response) would fan out
         * unbounded. Real videos carry a handful of base tracks (manual + ASR) — the
         * 100+ auto-translate languages live in a separate translationLanguages[] we
         * never read — so this ceiling sits far above any ordinary video's base
         * track count and should not affect real content.
         */
        private const val MAX_CAPTION_TRACKS = 100
        private val FMT_PARAM_REGEX = Regex("([?&])fmt=[^&]*")

        /**
         * Parse YouTube's caption list from the ANDROID_VR player response into
         * [SubtitleTrack]s. The Oculus client returns the same
         * `captions.playerCaptionsTracklistRenderer.captionTracks[]` block as the
         * WEB client; the resolver simply never read it, which is why the CC
         * button went dark once ANDROID_VR became the primary resolve path.
         * Side-loading and the CC button are already wired downstream
         * (DashSourceBuilder.wrapWithSideLoadSubtitles + the players' visibility
         * gates), so populating this list is all that is required to restore it.
         */
        internal fun parseSubtitleTracks(json: JSONObject): List<SubtitleTrack> {
            val captionTracks = json.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")
                ?: return emptyList()
            val trackCount = minOf(captionTracks.length(), MAX_CAPTION_TRACKS)
            val result = ArrayList<SubtitleTrack>(trackCount)
            for (i in 0 until trackCount) {
                val track = captionTracks.optJSONObject(i) ?: continue
                val baseUrl = track.optStringOrNull("baseUrl") ?: continue
                val languageCode = track.optStringOrNull("languageCode") ?: continue
                result.add(
                    SubtitleTrack(
                        url = withVttFormat(baseUrl),
                        languageCode = languageCode,
                        languageName = captionDisplayName(track) ?: languageCode,
                        format = SUBTITLE_FORMAT,
                        isAutoGenerated = track.optStringOrNull("kind") == "asr"
                    )
                )
            }
            return result
        }

        /** Caption display name from `name.simpleText`, else concatenated `name.runs[].text`. */
        private fun captionDisplayName(track: JSONObject): String? {
            val name = track.optJSONObject("name") ?: return null
            name.optStringOrNull("simpleText")?.let { return it }
            val runs = name.optJSONArray("runs") ?: return null
            val text = buildString {
                for (i in 0 until runs.length()) {
                    runs.optJSONObject(i)?.optStringOrNull("text")?.let { append(it) }
                }
            }
            return text.ifEmpty { null }
        }

        /**
         * Rewrite a timedtext `baseUrl` to request WebVTT. YouTube's caption urls
         * carry a default `fmt=srv3` (XML); we must REPLACE that param, not append
         * a second `fmt` — YouTube honours the first occurrence, so an append
         * yields XML and ExoPlayer cannot parse it as WebVTT.
         */
        internal fun withVttFormat(baseUrl: String): String =
            if (FMT_PARAM_REGEX.containsMatchIn(baseUrl)) {
                FMT_PARAM_REGEX.replace(baseUrl) { "${it.groupValues[1]}fmt=vtt" }
            } else {
                baseUrl + (if (baseUrl.contains('?')) "&" else "?") + "fmt=vtt"
            }

        private fun extractCodec(mimeType: String): String? =
            Regex("codecs=\"([^\"]+)\"").find(mimeType)?.groupValues?.get(1)
    }
}
