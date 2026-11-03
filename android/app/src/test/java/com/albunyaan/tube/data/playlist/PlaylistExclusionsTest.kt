package com.albunyaan.tube.data.playlist

import com.albunyaan.tube.data.FakeInnerTube
import com.albunyaan.tube.data.FakeInnerTube.Companion.continuation
import com.albunyaan.tube.data.FakeInnerTube.Companion.page
import com.albunyaan.tube.data.source.Exclusions
import com.albunyaan.tube.data.source.OriginalTitles
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.RegistryEntry
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.schabi.newpipe.extractor.NewPipe
import retrofit2.Response

/**
 * Owner bug 2026-11-03: a playlist's items, as the screen, the player queue and a playlist download read them, drop
 * what the dashboard excluded from the playlist and from each item's channel. Real NewPipe parsing over a canned
 * YouTube continuation answer, so the test covers the repository's own wiring, not a copy of it.
 */
class PlaylistExclusionsTest {

    private val api: ContentApi = mock()
    private val youtube = FakeInnerTube()

    @Before
    fun setUp() {
        NewPipe.init(youtube)
    }

    private fun repository(exclusions: Exclusions) = NewPipePlaylistDetailRepository(
        extractorClient = mock(),
        indexRepository = mock(),
        originalTitles = OriginalTitles(api) { 1_000L },
        exclusions = exclusions,
    )

    private fun entry(vararg videos: String) = Response.success(RegistryEntry(excludedVideoIds = videos.toList()))

    @Test
    fun `a page drops the playlist's and the item channel's exclusions and keeps YouTube's positions`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(Response.success(emptyMap()))
        whenever(api.getPlaylist(PLAYLIST)).thenReturn(entry("lesson00002"))
        whenever(api.getChannel(CHANNEL)).thenReturn(entry("lesson00003"))
        youtube.pages["P2"] = continuation(
            listOf("lesson00001" to CHANNEL, "lesson00002" to CHANNEL, "lesson00003" to CHANNEL, "lesson00004" to CHANNEL),
            next = "P3",
        )

        val result = repository(Exclusions(api, { 1_000L }, backgroundScope)).getItems(PLAYLIST, page("P2"), itemOffset = 101)

        assertEquals(listOf("lesson00001", "lesson00004"), result.items.map { it.videoId })
        assertEquals(listOf(101, 104), result.items.map { it.position })
        assertEquals(105, result.nextItemOffset) // the next page numbers on from YouTube's count, not the shown one
    }

    @Test
    fun `a page of only excluded items is followed to the next one`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(Response.success(emptyMap()))
        whenever(api.getPlaylist(PLAYLIST)).thenReturn(entry("lesson00001", "lesson00002"))
        whenever(api.getChannel(CHANNEL)).thenReturn(Response.error(404, "".toResponseBody(null)))
        youtube.pages["P2"] = continuation(listOf("lesson00001" to CHANNEL, "lesson00002" to CHANNEL), next = "P3")
        youtube.pages["P3"] = continuation(listOf("lesson00003" to CHANNEL))

        val result = repository(Exclusions(api, { 1_000L }, backgroundScope)).getItems(PLAYLIST, page("P2"), itemOffset = 101)

        assertEquals(listOf("lesson00003"), result.items.map { it.videoId })
        assertEquals(listOf(103), result.items.map { it.position })
        assertNull(result.nextPage)
    }

    @Test
    fun `a backend that never answers costs the list nothing but the wait`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(Response.success(emptyMap()))
        whenever(api.getPlaylist(PLAYLIST)).thenAnswer { throw java.io.IOException("down") }
        whenever(api.getChannel(CHANNEL)).thenAnswer { throw java.io.IOException("down") }
        youtube.pages["P2"] = continuation(listOf("lesson00001" to CHANNEL))

        val result = repository(Exclusions(api, { 1_000L }, backgroundScope)).getItems(PLAYLIST, page("P2"), itemOffset = 1)

        assertEquals(listOf("lesson00001"), result.items.map { it.videoId }) // fail open
    }

    private companion object {
        const val CHANNEL = "UCaaaaaaaaaaaaaaaaaaaaaa"
        const val PLAYLIST = "PL0IYpdlafdv278Cn5HfCs5fmedQrQbhmT"
    }
}
