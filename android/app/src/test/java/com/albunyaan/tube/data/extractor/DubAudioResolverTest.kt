package com.albunyaan.tube.data.extractor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric: parseCandidates uses org.json (stubbed in plain JVM tests). SDK 31, project convention. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DubAudioResolverTest {

    private val candidates = listOf(
        DubStreamCandidate("ar", 49_000, "u-ar-lo", "audio/mp4"),
        DubStreamCandidate("ar", 129_000, "u-ar-hi", "audio/mp4"),
        DubStreamCandidate("fr", 129_000, "u-fr", "audio/webm"),
    )

    @Test fun picks_highest_bitrate_for_language() {
        assertEquals("u-ar-hi", DubAudioResolver.selectAudioStream(candidates, "ar")?.url)
    }

    @Test fun returns_null_when_language_absent() {
        assertNull(DubAudioResolver.selectAudioStream(candidates, "de"))
    }

    @Test fun returns_null_for_empty_candidates() {
        assertNull(DubAudioResolver.selectAudioStream(emptyList(), "ar"))
    }

    @Test fun appendPot_uses_ampersand_when_query_present() {
        assertEquals("https://x/y?a=1&pot=TKN", DubAudioResolver.appendPot("https://x/y?a=1", "TKN"))
    }

    @Test fun appendPot_uses_question_when_no_query() {
        assertEquals("https://x/y?pot=TKN", DubAudioResolver.appendPot("https://x/y", "TKN"))
    }

    @Test fun parseCandidates_filters_to_language_audio_with_direct_url() {
        val json = JSONObject(
            """{"streamingData":{"adaptiveFormats":[
                {"mimeType":"audio/mp4","bitrate":129000,"url":"u-ar-hi","audioTrack":{"id":"ar.3"}},
                {"mimeType":"audio/webm","bitrate":49000,"url":"u-ar-lo","audioTrack":{"id":"ar.3"}},
                {"mimeType":"audio/mp4","bitrate":129000,"url":"u-fr","audioTrack":{"id":"fr.3"}},
                {"mimeType":"audio/mp4","bitrate":129000,"signatureCipher":"sig","audioTrack":{"id":"ar.3"}},
                {"mimeType":"video/mp4","bitrate":500000,"url":"v"}
            ]}}"""
        )
        val ar = DubAudioResolver.parseCandidates(json, "ar")
        assertEquals(2, ar.size) // two direct-url ar audio formats; cipher-only + fr + video excluded
        assertEquals(setOf("u-ar-hi", "u-ar-lo"), ar.map { it.url }.toSet())
        assertEquals(129000, DubAudioResolver.selectAudioStream(ar, "ar")?.bitrate)
    }
}
