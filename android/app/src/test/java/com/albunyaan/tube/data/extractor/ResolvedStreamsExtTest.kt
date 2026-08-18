package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedStreamsExtTest {

    private fun audio(
        url: String = "https://example.test/a.m4a",
        bitrate: Int? = 128_000,
        language: String? = null,
        trackName: String? = null,
        trackType: AudioTrackKind? = null
    ): AudioTrack = AudioTrack(
        url = url,
        mimeType = "audio/mp4",
        bitrate = bitrate,
        codec = "mp4a.40.2",
        language = language,
        trackName = trackName,
        trackType = trackType
    )

    private fun resolved(tracks: List<AudioTrack>): ResolvedStreams = ResolvedStreams(
        streamId = "stream",
        videoTracks = emptyList(),
        audioTracks = tracks,
        durationSeconds = 30
    )

    @Test
    fun `empty tracks yields empty list`() {
        assertEquals(emptyList<AudioLanguageOption>(), resolved(emptyList()).availableAudioLanguages())
    }

    @Test
    fun `single track yields empty list — no picker needed`() {
        val options = resolved(listOf(audio(language = "en"))).availableAudioLanguages()
        assertEquals(emptyList<AudioLanguageOption>(), options)
    }

    @Test
    fun `two unlabeled tracks collapse into one und entry with highest bitrate`() {
        val lo = audio(url = "https://a", bitrate = 96_000, language = null)
        val hi = audio(url = "https://b", bitrate = 256_000, language = null)
        val options = resolved(listOf(lo, hi)).availableAudioLanguages()
        assertEquals(1, options.size)
        assertEquals("und", options[0].language)
        assertEquals(hi, options[0].representative)
    }

    @Test
    fun `original english plus arabic dub sorts english first`() {
        val en = audio(language = "en", trackType = AudioTrackKind.ORIGINAL, bitrate = 128_000)
        val ar = audio(language = "ar", trackType = AudioTrackKind.DUBBED, bitrate = 128_000)
        val options = resolved(listOf(ar, en)).availableAudioLanguages()
        assertEquals(2, options.size)
        assertEquals("en", options[0].language)
        assertTrue("English must be flagged as original", options[0].isOriginal)
        assertEquals("ar", options[1].language)
        assertEquals(false, options[1].isOriginal)
    }

    @Test
    fun `multiple bitrates per language collapse per language to top bitrate`() {
        val en128 = audio(url = "en128", language = "en", bitrate = 128_000)
        val en256 = audio(url = "en256", language = "en", bitrate = 256_000)
        val ar128 = audio(url = "ar128", language = "ar", bitrate = 128_000)
        val ar256 = audio(url = "ar256", language = "ar", bitrate = 256_000)
        val es96 = audio(url = "es96", language = "es", bitrate = 96_000)
        val es192 = audio(url = "es192", language = "es", bitrate = 192_000)

        val options = resolved(listOf(en128, en256, ar128, ar256, es96, es192)).availableAudioLanguages()
        assertEquals(3, options.size)
        val byLang = options.associateBy { it.language }
        assertEquals(en256, byLang["en"]?.representative)
        assertEquals(ar256, byLang["ar"]?.representative)
        assertEquals(es192, byLang["es"]?.representative)
    }

    @Test
    fun `display name falls back to trackName when language tag unknown`() {
        val x = audio(url = "x1", language = null, trackName = "Director Commentary")
        val y = audio(url = "x2", language = null, trackName = "Director Commentary", bitrate = 64_000)
        val options = resolved(listOf(x, y)).availableAudioLanguages()
        assertEquals(1, options.size)
        assertNotNull(options[0].displayName)
        assertTrue("display name should be non-blank", options[0].displayName.isNotBlank())
    }

    @Test
    fun `real NewPipe dub shape surfaces every language with the original first`() {
        // Pinned to what NewPipeExtractor 0.26.5 actually returns for a multi-dub video
        // (measured 2026-08-18 on DW-00ckCAPI: 70 audio streams, 14 distinct locales, each
        // tagged ORIGINAL/DUBBED). Before the ANDROID_VR retirement the resolve exposed a
        // single language and the dubs had to be re-fetched by hand, so this is the shape the
        // picker must keep handling — several bitrates per language, one original among them.
        val langs = listOf(
            "ar", "bn", "de", "es", "fa", "fr", "hi", "id", "ru", "tr", "ur", "uz", "zh",
        )
        val tracks = buildList {
            // multiple bitrates per language, as YouTube serves them
            langs.forEach { code ->
                add(audio(url = "https://example.test/$code-low.m4a", bitrate = 48_000,
                    language = code, trackType = AudioTrackKind.DUBBED))
                add(audio(url = "https://example.test/$code-high.m4a", bitrate = 128_000,
                    language = code, trackType = AudioTrackKind.DUBBED))
            }
            add(audio(url = "https://example.test/en.m4a", bitrate = 128_000,
                language = "en", trackType = AudioTrackKind.ORIGINAL))
        }

        val options = resolved(tracks).availableAudioLanguages()

        assertEquals("every distinct language must be offered exactly once",
            langs.size + 1, options.size)
        assertEquals("the original language must sort first", "en", options.first().language)
        assertTrue("the original must be flagged as such", options.first().isOriginal)
        assertEquals("each language collapses to one option",
            options.size, options.map { it.language }.distinct().size)
        // the picker plays the representative, so it must be the best bitrate of that language
        val arabic = options.first { it.language == "ar" }
        assertEquals(128_000, arabic.representative.bitrate)
        assertEquals(AudioTrackKind.DUBBED, arabic.trackKind)
    }
}
