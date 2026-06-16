package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackSource
import com.albunyaan.tube.data.extractor.ResolvedStreams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubMergeDecisionTest {

    private fun streams(vararg audio: AudioTrack) = ResolvedStreams(
        streamId = "v",
        videoTracks = emptyList(),
        audioTracks = audio.toList(),
        durationSeconds = 100,
    )

    @Test fun resolved_web_dub_triggers_merge() {
        assertTrue(
            DashSourceBuilder.isWebDubMerge(
                streams(
                    AudioTrack("https://vr", "audio/mp4", 129000, null),
                    AudioTrack(
                        "https://dub", "audio/mp4", 129000, null,
                        language = "ar", source = AudioTrackSource.WEB_DUB
                    ),
                )
            )
        )
    }

    @Test fun vr_native_only_does_not_merge() {
        assertFalse(DashSourceBuilder.isWebDubMerge(streams(AudioTrack("https://vr", "audio/mp4", 129000, null))))
    }

    @Test fun lazy_unresolved_web_dub_does_not_merge() {
        assertFalse(
            DashSourceBuilder.isWebDubMerge(
                streams(AudioTrack("", "audio/mp4", null, null, language = "ar", source = AudioTrackSource.WEB_DUB))
            )
        )
    }
}
