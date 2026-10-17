package com.albunyaan.tube.player

import androidx.media3.datasource.DefaultHttpDataSource
import com.albunyaan.tube.data.extractor.ExtractionClient
import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec §9.2(a), RV I10a: every live HLS source DashSourceBuilder builds starts from its own master (the map is cleared). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DashSourceBuilderLiveHlsTest {

    private val e1 = 1_792_221_531L
    private val e2 = 1_792_243_000L

    private fun playlist(itag: Int, expire: Long) =
        "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/$expire/ei/x/ip/203.0.113.7/id/Rs7St51oDDc.1/itag/$itag/file/index.m3u8"

    private fun live(expire: Long) = ResolvedStreams(
        streamId = "Rs7St51oDDc", videoTracks = emptyList(), audioTracks = emptyList(), durationSeconds = 0,
        hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/$expire/file/index.m3u8",
        isLive = true, extractionClient = ExtractionClient.NEWPIPE_IOS,
    )

    @Test fun `building a live hls source clears the stream's redirect map`() = runTest {
        val redirector = LivePlaylistRedirector { _, _ -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n${playlist(230, e2)}\n" }
        val builder = DashSourceBuilder(
            mpdGenerator = MultiRepresentationMpdGenerator(),
            dataSourceFactoryProvider = mock { on { forStreams(any()) } doReturn DefaultHttpDataSource.Factory() },
            livePlaylistRedirector = redirector,
        )
        assertNotNull(builder.build(live(e1)))                              // first source
        redirector.refresh(live(e1), live(e2))                              // a refresh maps the fresh URLs
        assertNotNull(redirector.rewrite("Rs7St51oDDc", playlist(230, e1)))

        assertNotNull(builder.build(live(e1)))                              // a new source (recovery, network change)

        assertNull(redirector.rewrite("Rs7St51oDDc", playlist(230, e1)))
    }
}
