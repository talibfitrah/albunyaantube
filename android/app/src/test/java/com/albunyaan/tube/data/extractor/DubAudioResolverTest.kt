package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
