package com.albunyaan.tube.data.channel

import com.albunyaan.tube.data.FakeInnerTube
import com.albunyaan.tube.data.FakeInnerTube.Companion.continuation
import com.albunyaan.tube.data.FakeInnerTube.Companion.page
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.source.Exclusions
import com.albunyaan.tube.data.source.OriginalTitles
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.RegistryEntry
import com.albunyaan.tube.player.StreamRequestTelemetry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.schabi.newpipe.extractor.NewPipe
import retrofit2.Response

/**
 * Owner bug 2026-11-03: a channel's Videos list (its uploads playlist path) drops what the dashboard excluded, and so
 * does the disk cache the next open paints from. Real NewPipe parsing over a canned continuation answer.
 */
class ChannelExclusionsTest {

    private val api: ContentApi = mock()
    private val dao: ChannelVideoCacheDao = mock()
    private val youtube = FakeInnerTube()

    @Before
    fun setUp() {
        NewPipe.init(youtube)
    }

    @Test
    fun `the uploads page and its cache rows drop the channel's excluded videos`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(Response.success(emptyMap()))
        whenever(api.getChannel(CHANNEL)).thenReturn(Response.success(RegistryEntry(excludedVideoIds = listOf("lesson00002"))))
        youtube.pages["P2"] = continuation(listOf("lesson00001" to CHANNEL, "lesson00002" to CHANNEL, "lesson00003" to CHANNEL))
        val repository = NewPipeChannelDetailRepository(
            extractorClient = mock(),
            indexRepository = mock(),
            telemetry = StreamRequestTelemetry(),
            channelVideoCacheDao = dao,
            originalTitles = OriginalTitles(api) { 1_000L },
            exclusions = Exclusions(api, { 1_000L }, backgroundScope),
        )

        val result = repository.getVideos(CHANNEL, page("P2"))

        assertEquals(listOf("lesson00001", "lesson00003"), result.items.map { it.id })
        val rows = argumentCaptor<List<ChannelVideoCache>>()
        verify(dao).upsertAll(rows.capture())
        assertEquals(listOf("lesson00001", "lesson00003"), rows.firstValue.map { it.videoId })
    }

    private companion object {
        const val CHANNEL = "UCaaaaaaaaaaaaaaaaaaaaaa"
    }
}
