package com.albunyaan.tube.data.extractor.cache

import com.albunyaan.tube.data.extractor.ChannelMetadata
import com.albunyaan.tube.data.extractor.PlaylistMetadata
import com.albunyaan.tube.data.extractor.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataCacheTest {

    private val cache = MetadataCache(ttlMillis = 1_000L, maxEntriesPerBucket = 2)

    @Test
    fun `video entry expires after ttl`() {
        val now = 1_000L
        cache.putVideo("id", VideoMetadata(title = "one"), now)

        val stale = cache.getVideo("id", now + 2_000L)
        assertNull(stale)
    }

    @Test
    fun `prunes oldest entries when capacity exceeded`() {
        cache.putChannel("a", ChannelMetadata(name = "A"), 0)
        cache.putChannel("b", ChannelMetadata(name = "B"), 1)
        cache.putChannel("c", ChannelMetadata(name = "C"), 2)

        val current = cache.getChannel("a", 3)
        assertNull(current)
        assertEquals("B", cache.getChannel("b", 3)?.name)
        assertEquals("C", cache.getChannel("c", 3)?.name)
    }

    @Test
    fun `playlist cache returns stored entry`() {
        cache.putPlaylist("p", PlaylistMetadata(title = "Playlist"), 0)
        val cached = cache.getPlaylist("p", 500)
        assertEquals("Playlist", cached?.title)
    }
}

