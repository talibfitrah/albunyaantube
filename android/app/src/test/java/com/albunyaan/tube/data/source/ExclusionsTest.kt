package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.RegistryEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.io.IOException

/** Owner bug 2026-11-03: the dashboard's exclusions reach the app through the registry GET, shared and fail-open. */
class ExclusionsTest {

    private val api: ContentApi = mock()
    private var clock = 1_000L

    private fun entry(videos: List<String> = emptyList(), playlists: List<String> = emptyList()) =
        Response.success(RegistryEntry(excludedVideoIds = videos, excludedPlaylistIds = playlists))

    @Test
    fun `a channel answer carries its excluded videos and playlists`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry(listOf("lesson00001"), listOf("PLgone")))
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        val e = exclusions.channel("UC1")

        assertEquals(200, e.code)
        assertEquals(setOf("lesson00001"), e.videoIds)
        assertEquals(setOf("PLgone"), e.playlistIds)
    }

    @Test
    fun `404 is not registered and 410 is gone, both with no exclusions`() = runTest {
        whenever(api.getChannel("UCnew")).thenReturn(Response.error(404, "".toResponseBody(null)))
        whenever(api.getPlaylist("PLgone")).thenReturn(Response.error(410, "".toResponseBody(null)))
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        assertEquals(404, exclusions.channel("UCnew").code)
        assertEquals(410, exclusions.playlist("PLgone").code)
        assertTrue(exclusions.channel("UCnew").videoIds.isEmpty())
    }

    @Test
    fun `concurrent asks for one id share one GET`() = runTest {
        whenever(api.getChannel("UC1")).doSuspendableAnswer { delay(500); entry(listOf("lesson00001")) }
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        // The header gate, the tab gate and the first page all ask at once on a channel open.
        val answers = listOf(
            async { exclusions.channel("UC1", fresh = true) },
            async { exclusions.channel("UC1", fresh = true) },
            async { exclusions.videoIds(listOf("UC1")) },
        ).awaitAll()

        verify(api, times(1)).getChannel("UC1")
        assertEquals(setOf("lesson00001"), (answers[2] as Set<*>))
    }

    @Test
    fun `an answer serves for five minutes, and fresh always asks again`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry(listOf("lesson00001")))
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        exclusions.channel("UC1")
        clock += 5 * 60_000L - 1
        exclusions.channel("UC1")
        verify(api, times(1)).getChannel("UC1")

        clock += 2
        exclusions.channel("UC1")
        verify(api, times(2)).getChannel("UC1")

        exclusions.channel("UC1", fresh = true) // a screen open: the newest exclusion shows
        verify(api, times(3)).getChannel("UC1")
    }

    @Test
    fun `a failed ask fails open with the last answer's lists and is asked again after 30 s`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry(listOf("lesson00001")))
        val exclusions = Exclusions(api, { clock }, backgroundScope)
        exclusions.channel("UC1")

        whenever(api.getChannel("UC1")).thenAnswer { throw IOException("down") }
        val failed = exclusions.channel("UC1", fresh = true)
        assertNotNull(failed.error)
        assertEquals(setOf("lesson00001"), failed.videoIds) // a stale list beats showing what the admin hid

        clock += 29_000
        exclusions.channel("UC1")
        verify(api, times(2)).getChannel("UC1")
        clock += 2_000
        exclusions.channel("UC1")
        verify(api, times(3)).getChannel("UC1")
    }

    @Test
    fun `a 5xx is a failure, never no exclusions`() = runTest {
        whenever(api.getPlaylist("PL1")).thenReturn(Response.error(503, "".toResponseBody(null)))
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        val e = exclusions.playlist("PL1")

        assertNotNull(e.error)
        assertEquals(null, e.code)
    }

    @Test
    fun `a list waits at most two seconds, then shows its items, and the late answer serves the next page`() = runTest {
        whenever(api.getPlaylist("PL1")).doSuspendableAnswer { delay(10_000); entry(listOf("lesson00009")) }
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        assertEquals(emptySet<String>(), exclusions.videoIds(playlistIds = listOf("PL1")))
        assertEquals(2_000L, currentTime)

        delay(9_000)
        assertEquals(setOf("lesson00009"), exclusions.videoIds(playlistIds = listOf("PL1")))
        verify(api, times(1)).getPlaylist("PL1")
    }

    @Test
    fun `a slow refresh of an expired answer serves the last lists, never none`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry(listOf("lesson00001")))
        val exclusions = Exclusions(api, { clock }, backgroundScope)
        exclusions.channel("UC1")
        clock += 5 * 60_000L + 1
        whenever(api.getChannel("UC1")).doSuspendableAnswer { delay(10_000); entry(listOf("lesson00001", "lesson00002")) }

        assertEquals(setOf("lesson00001"), exclusions.videoIds(listOf("UC1")))
    }

    @Test
    fun `a failure after an answer keeps its status, so a registered channel stays registered`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry())
        val exclusions = Exclusions(api, { clock }, backgroundScope)
        exclusions.channel("UC1")

        whenever(api.getChannel("UC1")).thenAnswer { throw IOException("down") }
        val failed = exclusions.channel("UC1", fresh = true)

        assertNotNull(failed.error)
        assertTrue(failed.registered)
    }

    @Test
    fun `each landed answer bumps changes, so a list already shown can filter again`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry(listOf("lesson00001")))
        val exclusions = Exclusions(api, { clock }, backgroundScope)
        val before = exclusions.changes.value

        exclusions.channel("UC1")

        assertEquals(before + 1, exclusions.changes.value)
    }

    @Test
    fun `at most four asks reach the backend at once`() = runTest {
        var running = 0
        var most = 0
        val slow: suspend () -> Response<RegistryEntry> = {
            running++; most = maxOf(most, running); delay(100); running--; entry()
        }
        (1..10).forEach { whenever(api.getChannel("UC$it")).doSuspendableAnswer { slow() } }
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        exclusions.videoIds((1..10).map { "UC$it" })

        assertEquals(4, most)
    }

    @Test
    fun `videoIds unions every channel and playlist it is given`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry(listOf("lesson00001")))
        whenever(api.getChannel("UC2")).thenReturn(Response.error(404, "".toResponseBody(null)))
        whenever(api.getPlaylist("PL1")).thenReturn(entry(listOf("lesson00002")))
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        val ids = exclusions.videoIds(listOf("UC1", "UC2", "UC1", ""), listOf("PL1"))

        assertEquals(setOf("lesson00001", "lesson00002"), ids)
        verify(api, times(1)).getChannel("UC1")
        assertFalse(ids.contains(""))
    }

    @Test
    fun `playlistIds is a channel's excluded playlists`() = runTest {
        whenever(api.getChannel("UC1")).thenReturn(entry(playlists = listOf("PLgone")))
        val exclusions = Exclusions(api, { clock }, backgroundScope)

        assertEquals(setOf("PLgone"), exclusions.playlistIds("UC1"))
    }
}
