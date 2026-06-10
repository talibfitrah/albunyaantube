package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamModelsAudioSourceTest {
    @Test fun audioTrack_defaults_to_vr_native() {
        val t = AudioTrack(url = "https://x", mimeType = "audio/mp4", bitrate = 1, codec = null)
        assertEquals(AudioTrackSource.VR_NATIVE, t.source)
    }

    @Test fun lazy_web_dub_entry_carries_language_without_url() {
        val dub = AudioTrack(
            url = "", mimeType = null, bitrate = null, codec = null,
            language = "ar", trackName = "Arabic",
            trackType = AudioTrackKind.DUBBED, source = AudioTrackSource.WEB_DUB
        )
        assertEquals(AudioTrackSource.WEB_DUB, dub.source)
        assertEquals("ar", dub.language)
        assertTrue(dub.url.isEmpty())
    }
}
