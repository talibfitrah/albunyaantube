package com.albunyaan.tube.data.extractor

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live-stream detection for [AndroidVrStreamResolver]. ANDROID_VR builds synthetic DASH from
 * fixed byte ranges, which cannot represent a moving live edge — so the resolver must DEFER live
 * (and post-live DVR) to the NewPipe live path (which yields LIVE_STREAM + a real hls/dash
 * manifest). The bug these tests lock down: `isLive` was hardcoded false, so live streams were
 * silently resolved as broken VOD and never reached the working NewPipe live branch.
 *
 * Detection is keyed on authoritative live metadata (isLive / isPostLiveDvr / liveStreamability /
 * hlsManifestUrl) — NOT on `dashManifestUrl`, which YouTube also serves for ordinary VODs, and
 * NOT on `isLiveContent`, which stays true for finished recordings of past streams.
 *
 * Robolectric is required because real `org.json` parsing is only available under it (project
 * convention; matches the sibling [AndroidVrSubtitleParsingTest] pinned to SDK 31).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidVrLiveDetectionTest {

    private fun isLive(
        playabilityStatus: String?,
        videoDetails: String?,
        streamingData: String
    ): Boolean = AndroidVrStreamResolver.isLiveStream(
        playabilityStatus?.let { JSONObject(it) },
        videoDetails?.let { JSONObject(it) },
        JSONObject(streamingData)
    )

    @Test
    fun `live when videoDetails isLive flag set`() {
        assertTrue(
            isLive(
                playabilityStatus = """{"status":"OK"}""",
                videoDetails = """{"isLive":true,"lengthSeconds":"0"}""",
                streamingData = """{"adaptiveFormats":[]}"""
            )
        )
    }

    @Test
    fun `live when post-live DVR flag set`() {
        assertTrue(
            isLive(
                playabilityStatus = """{"status":"OK"}""",
                videoDetails = """{"isPostLiveDvr":true,"lengthSeconds":"0"}""",
                streamingData = """{"adaptiveFormats":[]}"""
            )
        )
    }

    @Test
    fun `live when playabilityStatus carries a liveStreamability renderer`() {
        assertTrue(
            isLive(
                playabilityStatus = """{"status":"OK","liveStreamability":{"liveStreamabilityRenderer":{}}}""",
                videoDetails = """{"lengthSeconds":"0"}""",
                streamingData = """{"adaptiveFormats":[]}"""
            )
        )
    }

    @Test
    fun `live when hls manifest url present`() {
        assertTrue(
            isLive(
                playabilityStatus = """{"status":"OK"}""",
                videoDetails = """{"lengthSeconds":"0"}""",
                streamingData = """{"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/x.m3u8","adaptiveFormats":[]}"""
            )
        )
    }

    /**
     * False-positive guard (the codex P2): YouTube serves a server-side DASH manifest for ORDINARY
     * VODs too (yt-dlp's `youtube_include_dash_manifest`), so `dashManifestUrl` alone must NOT mark
     * a stream live — that would needlessly drop a normal VOD off the fast ANDROID_VR path.
     */
    @Test
    fun `not live for VOD carrying only a server dash manifest`() {
        assertFalse(
            isLive(
                playabilityStatus = """{"status":"OK"}""",
                videoDetails = """{"lengthSeconds":"212"}""",
                streamingData = """{"dashManifestUrl":"https://manifest.googlevideo.com/api/manifest/dash/x","adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4"}]}"""
            )
        )
    }

    @Test
    fun `not live for ordinary VOD with only adaptive formats`() {
        assertFalse(
            isLive(
                playabilityStatus = """{"status":"OK"}""",
                videoDetails = """{"lengthSeconds":"212"}""",
                streamingData = """{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4"}],"formats":[]}"""
            )
        )
    }

    /**
     * False-positive guard: a finished VOD of a *past* live stream keeps `isLiveContent=true`
     * but has NO rolling manifest / liveStreamability — it must stay on the fast ANDROID_VR
     * byte-range path, not be needlessly deferred to NewPipe.
     */
    @Test
    fun `not live for finished recording of a past live stream`() {
        assertFalse(
            isLive(
                playabilityStatus = """{"status":"OK"}""",
                videoDetails = """{"isLiveContent":true,"isLive":false,"lengthSeconds":"3600"}""",
                streamingData = """{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4"}]}"""
            )
        )
    }

    @Test
    fun `not live when videoDetails and playabilityStatus missing entirely`() {
        assertFalse(
            isLive(
                playabilityStatus = null,
                videoDetails = null,
                streamingData = """{"adaptiveFormats":[{"itag":18,"mimeType":"video/mp4"}]}"""
            )
        )
    }
}
