package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.source.api.ContentApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
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
            Response.success(emptyMap(), Headers.headersOf("Cache-Control", "public, max-age=60"))
        )

        retitle(rows(1))
        clock += 30_000
        retitle(rows(1))
        verify(api, times(1)).fetchTitles(any())
        clock += 31_000
        retitle(rows(1))
        verify(api, times(2)).fetchTitles(any())
    }

    @Test
    fun `a page with no valid video id asks nothing`() = runTest {
        retitle(listOf(Row("PLabcdefghijklmnop", "playlist")))

        verify(api, never()).fetchTitles(any())
    }
}
