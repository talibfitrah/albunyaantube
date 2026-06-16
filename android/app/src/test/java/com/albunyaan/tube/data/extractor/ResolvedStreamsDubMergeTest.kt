package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedStreamsDubMergeTest {

    private fun vrStreams(lang: String?, type: AudioTrackKind?) = ResolvedStreams(
        streamId = "v",
        videoTracks = emptyList(),
        audioTracks = listOf(
            AudioTrack("https://vr", "audio/mp4", 129000, null, language = lang, trackType = type)
        ),
        durationSeconds = 100
    )

    @Test fun appends_lazy_non_original_dubs_and_lights_globe() {
        val out = vrStreams("en", AudioTrackKind.ORIGINAL).withDubLanguages(
            listOf(
                DubLanguage("en", "English original", isOriginal = true),
                DubLanguage("ar", "Arabic", isOriginal = false),
                DubLanguage("fr", "French", isOriginal = false),
            )
        )
        assertEquals(3, out.audioTracks.size) // vr-en + lazy ar + lazy fr (enumerate's en dropped)
        val ar = out.audioTracks.first { it.language == "ar" }
        assertEquals(AudioTrackSource.WEB_DUB, ar.source)
        assertEquals("", ar.url)
        assertEquals(AudioTrackSource.VR_NATIVE, out.audioTracks.first { it.language == "en" }.source)
        assertEquals(3, out.availableAudioLanguages().size) // globe shows: en, ar, fr
    }

    @Test fun fewer_than_two_dubs_is_noop() {
        val vr = vrStreams("en", AudioTrackKind.ORIGINAL)
        assertEquals(vr, vr.withDubLanguages(listOf(DubLanguage("en", "English original", true))))
    }

    @Test fun does_not_duplicate_language_vr_already_exposes() {
        val out = vrStreams("ar", AudioTrackKind.ORIGINAL).withDubLanguages(
            listOf(
                DubLanguage("ar", "Arabic original", isOriginal = true),
                DubLanguage("en", "English", isOriginal = false),
            )
        )
        assertEquals(2, out.audioTracks.size) // vr-ar + lazy en
        assertEquals(1, out.audioTracks.count { it.language == "ar" })
    }
}
