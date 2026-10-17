package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType

/** RV I11: an ended broadcast that can still be extracted (VIDEO_STREAM with past live content) is not live. */
class LiveStreamTypeTest {
    @Test fun `only live stream types are live`() {
        assertTrue(NewPipeExtractorClient.isLiveStreamType(StreamType.LIVE_STREAM))
        assertTrue(NewPipeExtractorClient.isLiveStreamType(StreamType.AUDIO_LIVE_STREAM))
        assertFalse(NewPipeExtractorClient.isLiveStreamType(StreamType.VIDEO_STREAM))
        assertFalse(NewPipeExtractorClient.isLiveStreamType(StreamType.POST_LIVE_STREAM))
    }
}
