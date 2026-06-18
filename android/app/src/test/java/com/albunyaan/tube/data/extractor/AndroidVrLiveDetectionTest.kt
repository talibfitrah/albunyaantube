package com.albunyaan.tube.data.extractor

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Narrowed live-stream detection for [AndroidVrStreamResolver]. ANDROID_VR builds synthetic DASH
 * from fixed byte ranges and hardcodes isLive=false, so a genuinely live stream resolved here would
 * play as broken VOD; such streams must DEFER to the NewPipe live-manifest path. Detection is keyed
 * STRICTLY on the authoritative videoDetails.isLive / isPostLiveDvr flags.
 *
 * Regression lock (2026-06-18): detection deliberately does NOT consider
 * playabilityStatus.liveStreamability or streamingData.hlsManifestUrl. YouTube attaches both to
 * some ordinary VODs, and the earlier broad version wrongly deferred those VODs onto the NewPipe
 * fallback (which strips dub audio tracks), regressing smooth dub switching. The narrowed signature
 * only accepts videoDetails, so those VOD-matching fields are structurally out of reach; these
 * tests prove a normal VOD is never classified live.
 *
 * Robolectric is required because real org.json parsing is only available under it (project
 * convention; matches sibling AndroidVrSubtitleParsingTest pinned to SDK 31).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidVrLiveDetectionTest {

    private fun isLive(videoDetails: String?): Boolean =
        AndroidVrStreamResolver.isLiveStream(videoDetails?.let { JSONObject(it) })

    @Test
    fun `live when videoDetails isLive flag set`() {
        assertTrue(isLive("""{"isLive":true,"lengthSeconds":"0"}"""))
    }

    @Test
    fun `live when videoDetails isPostLiveDvr flag set`() {
        assertTrue(isLive("""{"isPostLiveDvr":true,"lengthSeconds":"0"}"""))
    }

    @Test
    fun `not live for ordinary VOD with neither flag`() {
        assertFalse(isLive("""{"lengthSeconds":"600","title":"Some VOD"}"""))
    }

    @Test
    fun `not live when isLive and isPostLiveDvr both explicitly false`() {
        assertFalse(isLive("""{"isLive":false,"isPostLiveDvr":false,"lengthSeconds":"600"}"""))
    }

    @Test
    fun `not live when videoDetails missing`() {
        assertFalse(isLive(null))
    }

    @Test
    fun `not live for empty videoDetails`() {
        assertFalse(isLive("""{}"""))
    }

    @Test
    fun `live when isLive is a string-valued true (org json coercion)`() {
        // YouTube has historically sent booleans either as real booleans or quoted strings;
        // org.json's optBoolean coerces "true"/"false" case-insensitively. Lock that so the
        // narrowed detector can't silently miss a live stream over a JSON type quirk.
        assertTrue(isLive("""{"isLive":"true"}"""))
    }
}
