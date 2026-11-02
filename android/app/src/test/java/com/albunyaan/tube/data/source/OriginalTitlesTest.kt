package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.source.api.ContentApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.io.IOException

/** Owner issue 1(b): on-device rows take the original titles the server knows; every failure is silent and quiet. */
class OriginalTitlesTest {

    private data class Row(val id: String, val title: String)

    private val api: ContentApi = mock()
    private var clock = 1_000L
    private val titles = OriginalTitles(api) { clock }

    private fun id(n: Int) = "lesson%05d".format(n)
    private fun rows(vararg n: Int) = n.map { Row(id(it), "Lesson $it") }
    private suspend fun retitle(items: List<Row>) = titles.retitle(items, { it.id }) { r, t -> r.copy(title = t) }

    @Test
    fun `retitle swaps in the titles the server knows and keeps the rest`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(Response.success(mapOf(id(1) to "زبدة النور: الدرس الأول")))

        val out = retitle(rows(1, 2))

        assertEquals(listOf(Row(id(1), "زبدة النور: الدرس الأول"), Row(id(2), "Lesson 2")), out)
    }

    @Test
    fun `asks once per 50 distinct valid ids and never sends a bad id`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(Response.success(emptyMap()))
        val distinct = (1..120).map(::id)
        val items = (distinct + distinct.take(10) + listOf("PLabc", "short")).map { Row(it, "t") }

        retitle(items)

        val asked = argumentCaptor<String>()
        verify(api, times(3)).fetchTitles(asked.capture())
        val chunks = asked.allValues.map { it.split(",") }
        assertEquals(listOf(20, 50, 50), chunks.map { it.size }.sorted())
        assertEquals(distinct.toSet(), chunks.flatten().toSet())
        assertEquals(120, chunks.flatten().size)
    }

    @Test
    fun `a failed ask keeps the list titles and stays quiet for a minute`() = runTest {
        whenever(api.fetchTitles(any())).thenAnswer { throw IOException("down") }
        val items = rows(1)

        assertEquals(items, retitle(items))
        clock += 59_000
        assertEquals(items, retitle(items))
        verify(api, times(1)).fetchTitles(any())
        clock += 2_000
        retitle(items)
        verify(api, times(2)).fetchTitles(any())
    }

    @Test
    fun `a 400 or 503 is a failure too`() = runTest {
        for (code in listOf(400, 503)) {
            val api: ContentApi = mock()
            var clock = 1_000L
            val titles = OriginalTitles(api) { clock }
            whenever(api.fetchTitles(any())).thenReturn(Response.error(code, "{}".toResponseBody(null)))
            val items = rows(1)

            assertEquals(items, titles.retitle(items, { it.id }) { r, t -> r.copy(title = t) })
            clock += 59_000
            titles.retitle(items, { it.id }) { r, t -> r.copy(title = t) }
            verify(api, times(1)).fetchTitles(any())
            clock += 2_000
            titles.retitle(items, { it.id }) { r, t -> r.copy(title = t) }
            verify(api, times(2)).fetchTitles(any())
        }
    }

    @Test
    fun `a server slower than the timeout is abandoned`() = runTest {
        whenever(api.fetchTitles(any())).doSuspendableAnswer {
            delay(5_000)
            Response.success(mapOf(id(1) to "late"))
        }
        val items = rows(1)

        assertEquals(items, retitle(items))
        assertEquals(1_500, currentTime)
        retitle(items)
        verify(api, times(1)).fetchTitles(any())
    }

    @Test
    fun `known titles are answered from memory`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(Response.success(mapOf(id(1) to "الأول", id(2) to "الثاني")))

        retitle(rows(1, 2))
        clock += 24 * 3_600_000L
        val again = retitle(rows(1, 2))

        verify(api, times(1)).fetchTitles(any())
        assertEquals(listOf("الأول", "الثاني"), again.map { it.title })
    }

    @Test
    fun `an unknown id is asked again after the answer's max-age`() = runTest {
        whenever(api.fetchTitles(any())).thenReturn(
            Response.success(emptyMap(), Headers.headersOf("Cache-Control", "public, max-age=3600"))
        )

        retitle(rows(1))
        clock += 61_000 // past the 60 s floor: max-age, not the floor, decides
        retitle(rows(1))
        verify(api, times(1)).fetchTitles(any())
        clock += 3_540_000
        retitle(rows(1))
        verify(api, times(2)).fetchTitles(any())
    }

    // Fix round 1 (B): iOS TitlesClient parity. Each 50-id chunk stands alone; quiet only when no chunk answered.
    private val twoChunks = rows(*(1..51).toList().toIntArray()) // lesson00051 is alone in the second chunk

    @Test
    fun `one chunk that throws or hangs costs only its own ids and does not quiet the client`() = runTest {
        for (lonely in listOf<suspend () -> Response<Map<String, String>>>({ throw IOException("down") }, { awaitCancellation() })) {
            val api: ContentApi = mock()
            val titles = OriginalTitles(api) { 1_000L }
            whenever(api.fetchTitles(any())).doSuspendableAnswer { inv ->
                if (inv.getArgument<String>(0) == id(51)) lonely() else Response.success(mapOf(id(1) to "الأول"))
            }

            val out = titles.retitle(twoChunks, { it.id }) { r, t -> r.copy(title = t) }

            assertEquals("الأول", out.first().title)
            assertEquals("Lesson 51", out.last().title)
            titles.retitle(rows(51), { it.id }) { r, t -> r.copy(title = t) } // same instant: not quiet, so it asks
            verify(api, times(3)).fetchTitles(any())
        }
    }

    @Test
    fun `one chunk's 503 does not quiet the client`() = runTest {
        whenever(api.fetchTitles(any())).doSuspendableAnswer { inv ->
            if (inv.getArgument<String>(0) == id(51)) Response.error(503, "{}".toResponseBody(null))
            else Response.success(mapOf(id(1) to "الأول"))
        }

        assertEquals("الأول", retitle(twoChunks).first().title)
        retitle(rows(51))
        verify(api, times(3)).fetchTitles(any())
    }

    @Test
    fun `a caller cancelled mid-ask leaves the client willing to ask`() = runTest {
        var calls = 0
        whenever(api.fetchTitles(any())).doSuspendableAnswer {
            if (++calls == 1) awaitCancellation() else Response.success(mapOf(id(1) to "الأول"))
        }
        val backedOut = launch { retitle(rows(1)) }
        runCurrent()
        assertEquals("positive control: the ask is out and hung", 1, calls)

        backedOut.cancelAndJoin()

        assertEquals("الأول", retitle(rows(1)).single().title)
        assertEquals(2, calls)
    }

    @Test
    fun `a page with no valid video id asks nothing`() = runTest {
        retitle(listOf(Row("PLabcdefghijklmnop", "playlist")))

        verify(api, never()).fetchTitles(any())
    }
}
