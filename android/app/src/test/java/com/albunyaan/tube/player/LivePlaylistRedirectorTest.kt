package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.ExtractionClient
import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §9.2(a) and RV I10: the rewrite rule, the map's lifetime, and the refresh's fallbacks. Pure JVM. */
class LivePlaylistRedirectorTest {

    private val e1 = 1_792_221_531L
    private val e2 = 1_792_243_000L

    private fun url(itag: Int, expire: Long, ip: String = "203.0.113.7") =
        "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/$expire/ei/x/ip/$ip/id/Rs7St51oDDc.1" +
            "/itag/$itag/source/yt_live_broadcast/live/1/sgovp/gir%3Dyes%3Bitag%3D134/playlist_type/DVR/file/index.m3u8"

    /** The shape of a real live master (LR/probes/raw/np-A…hls): two audio renditions, video variants, one shared expire. */
    private fun master(expire: Long, ip: String = "203.0.113.7") = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:URI="${url(233, expire, ip)}",TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES
        #EXT-X-MEDIA:URI="${url(234, expire, ip)}",TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=962461,CODECS="avc1.4D401E,mp4a.40.2",RESOLUTION=640x360,AUDIO="234"
        ${url(230, expire, ip)}
        #EXT-X-STREAM-INF:BANDWIDTH=1282517,CODECS="avc1.4D401F,mp4a.40.2",RESOLUTION=854x480,AUDIO="234"
        ${url(231, expire, ip)}
    """.trimIndent()

    private fun streams(expire: Long, client: ExtractionClient = ExtractionClient.NEWPIPE_IOS) = ResolvedStreams(
        streamId = "Rs7St51oDDc", videoTracks = emptyList(), audioTracks = emptyList(), durationSeconds = 0,
        hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/$expire/file/index.m3u8",
        isLive = true, extractionClient = client,
    )

    private fun redirector(text: String?) = LivePlaylistRedirector { _, _ -> text }

    @Test fun `the fresh master maps every variant and audio rendition by itag`() {
        assertEquals(setOf(230, 231, 233, 234), LivePlaylistRedirector.variantMap(master(e2)).keys)
        assertEquals(url(233, e2), LivePlaylistRedirector.variantMap(master(e2))[233])
    }

    @Test fun `a media playlist request is rewritten to the fresher url of its itag`() = runTest {
        val r = redirector(master(e2))
        assertTrue(r.refresh(streams(e1), streams(e2)))

        assertEquals(url(230, e2), r.rewrite("Rs7St51oDDc", url(230, e1)))
        assertEquals(url(233, e2), r.rewrite("Rs7St51oDDc", url(233, e1)))
    }

    @Test fun `only a later expire is ever used`() = runTest {
        val r = redirector(master(e1))
        r.refresh(streams(e1), streams(e1))

        assertNull(r.rewrite("Rs7St51oDDc", url(230, e2)))
        assertNull(r.rewrite("Rs7St51oDDc", url(230, e1)))
    }

    /** RV I10a: after a Wi-Fi ↔ cellular change the player's new master is on a new IP; an old-IP map must not apply. */
    @Test fun `a mapping minted for another ip is never used`() = runTest {
        val r = redirector(master(e2, ip = "198.51.100.9"))
        r.refresh(streams(e1), streams(e2))

        assertNull(r.rewrite("Rs7St51oDDc", url(230, e1, ip = "203.0.113.7")))
    }

    @Test fun `segments, unknown itags and other streams pass through`() = runTest {
        val r = redirector(master(e2))
        r.refresh(streams(e1), streams(e2))

        // A real live segment carries /expire/, /ip/ and /itag/ too: only the hls_playlist guard keeps it out.
        assertNull(r.rewrite("Rs7St51oDDc", "https://rr3---sn-x.googlevideo.com/videoplayback/expire/$e1/ei/x/ip/203.0.113.7/id/Rs7St51oDDc.1/itag/230/sq/69099/goap/x"))
        assertNull(r.rewrite("Rs7St51oDDc", url(999, e1)))
        assertNull(r.rewrite("eaOtcUNHDB8", url(230, e1)))
    }

    /** RV I10a: every new source (recovery, network change, switch) starts from its own master. */
    @Test fun `clear forgets the map`() = runTest {
        val r = redirector(master(e2))
        r.refresh(streams(e1), streams(e2))
        r.clear("Rs7St51oDDc", ExtractionClient.NEWPIPE_IOS)

        assertNull(r.rewrite("Rs7St51oDDc", url(230, e1)))
    }

    /** RV I10b: segment requests carry the original client's User-Agent; another client's URLs would 403. */
    @Test fun `a refresh from another client falls back to the rebuild`() = runTest {
        val r = redirector(master(e2))

        assertFalse(r.refresh(streams(e1, ExtractionClient.NEWPIPE_IOS), streams(e2, ExtractionClient.NEWPIPE_ANDROID)))
        assertNull(r.rewrite("Rs7St51oDDc", url(230, e1)))
    }

    @Test fun `a fresh master missing an itag the player uses falls back to the rebuild`() = runTest {
        val r = redirector(master(e2).lines().filterNot { it.contains("/itag/231/") }.joinToString("\n"))
        r.rewrite("Rs7St51oDDc", url(231, e1))                       // the player has been reading itag 231

        assertFalse(r.refresh(streams(e1), streams(e2)))
    }

    @Test fun `a failed master fetch falls back to the rebuild`() = runTest {
        assertFalse(redirector(null).refresh(streams(e1), streams(e2)))
    }

    /** RV I10a: a source rebuilt while the fresh master is in flight starts from its own master; the late map is dropped. */
    @Test fun `a clear during the master fetch drops that refresh`() = runTest {
        lateinit var r: LivePlaylistRedirector
        r = LivePlaylistRedirector { _, _ -> r.clear("Rs7St51oDDc", ExtractionClient.NEWPIPE_IOS); master(e2) }

        assertFalse(r.refresh(streams(e1), streams(e2)))
        assertNull(r.rewrite("Rs7St51oDDc", url(230, e1)))
    }

    /**
     * RV I10b: a recovery may rebuild the stream on the other client while the caller re-extracts (seconds, before
     * refresh() runs). The caller's `current` is then stale; the map must match the client of the source now playing.
     */
    @Test fun `a rebuild on another client before the refresh drops that refresh`() = runTest {
        val r = redirector(master(e2))
        r.clear("Rs7St51oDDc", ExtractionClient.NEWPIPE_IOS)          // the source the caller is refreshing
        r.clear("Rs7St51oDDc", ExtractionClient.NEWPIPE_ANDROID)      // a recovery rebuilt it during the re-extraction

        assertFalse(r.refresh(streams(e1, ExtractionClient.NEWPIPE_IOS), streams(e2, ExtractionClient.NEWPIPE_IOS)))
        assertNull(r.rewrite("Rs7St51oDDc", url(230, e1)))
    }

    @Test fun `a failed or garbage refresh keeps the earlier map`() = runTest {
        var text: String? = master(e2)
        val r = LivePlaylistRedirector { _, _ -> text }
        assertTrue(r.refresh(streams(e1), streams(e2)))

        text = null
        assertFalse(r.refresh(streams(e1), streams(e2)))
        text = "<html>not a playlist</html>"
        assertFalse(r.refresh(streams(e1), streams(e2)))

        assertEquals(url(230, e2), r.rewrite("Rs7St51oDDc", url(230, e1)))
    }
}
