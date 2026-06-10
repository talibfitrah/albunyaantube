package com.albunyaan.tube.data.extractor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parser tests for [DubAudioEnumerator]. Robolectric is required because real
 * `org.json` parsing is only available under it (the JVM stub returns default
 * values; project convention — mirrors AndroidVrSubtitleParsingTest, SDK 31).
 *
 * Fixtures mirror the MWEB innertube player response verified in the spike
 * (memory/player-dubs-phase2-spike.md): each adaptive audio format carries an
 * `audioTrack {id, displayName, audioIsDefault}`; the language is the part of
 * `id` before the dot (e.g. "ar.3" -> "ar").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DubAudioEnumeratorTest {

    private fun resp(formats: String) =
        JSONObject("""{"streamingData":{"adaptiveFormats":[$formats]}}""")

    @Test fun parses_distinct_dub_languages_with_original_flag() {
        val json = resp(
            """
            {"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"en.4","displayName":"English original","audioIsDefault":true}},
            {"itag":140,"mimeType":"audio/mp4","audioTrack":{"id":"en.4","displayName":"English original","audioIsDefault":true}},
            {"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"ar.3","displayName":"Arabic"}},
            {"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"fr.3","displayName":"French"}},
            {"itag":137,"mimeType":"video/mp4"}
            """.trimIndent()
        )
        val langs = DubAudioEnumerator.parseDubLanguages(json)
        assertEquals(3, langs.size)
        assertEquals(setOf("en", "ar", "fr"), langs.map { it.languageCode }.toSet())
        assertEquals(true, langs.first { it.languageCode == "en" }.isOriginal)
        assertEquals(false, langs.first { it.languageCode == "ar" }.isOriginal)
    }

    @Test fun single_audio_track_returns_empty() {
        val json = resp(
            """{"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"en.4","displayName":"English original","audioIsDefault":true}}"""
        )
        assertEquals(emptyList<DubLanguage>(), DubAudioEnumerator.parseDubLanguages(json))
    }

    @Test fun no_audioTrack_metadata_returns_empty() {
        assertEquals(emptyList<DubLanguage>(), DubAudioEnumerator.parseDubLanguages(resp("""{"itag":139}""")))
    }

    @Test fun missing_streamingData_returns_empty() {
        assertEquals(emptyList<DubLanguage>(), DubAudioEnumerator.parseDubLanguages(JSONObject("{}")))
    }
}
