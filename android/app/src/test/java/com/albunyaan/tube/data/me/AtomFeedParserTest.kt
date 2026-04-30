package com.albunyaan.tube.data.me

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parser-level regression tests for [AtomFeedParser].
 *
 * Pinned to SDK 31 to match sibling Robolectric tests in this package
 * (project convention).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AtomFeedParserTest {

    private val parser = AtomFeedParser()

    @Test
    fun parses_real_atom_feed_with_uploads() {
        val xml = readResource("/atom/channel-with-uploads.xml")
        val items = parser.parse(xml.byteInputStream())

        assertTrue(
            "expected 1..15 entries from real ATOM fixture, got ${items.size}",
            items.size in 1..15,
        )

        val first = items.first()
        assertEquals("videoId must be 11 chars", 11, first.videoId.length)
        assertTrue("title must be non-empty", first.title.isNotEmpty())
        assertNotNull("thumbnailUrl must be present", first.thumbnailUrl)
        assertTrue(
            "thumbnailUrl must start with https://",
            first.thumbnailUrl?.startsWith("https://") == true,
        )
        assertNotNull("uploadedAt must be parsed from <published>", first.uploadedAt)
        assertNull("durationSeconds is not in ATOM — must be null", first.durationSeconds)
        assertNull("viewCount is not in ATOM — must be null", first.viewCount)
    }

    @Test
    fun parses_empty_feed_returns_empty_list() {
        val xml = readResource("/atom/channel-empty-no-uploads.xml")
        val items = parser.parse(xml.byteInputStream())
        assertEquals(0, items.size)
    }

    @Test
    fun marks_shorts_url_pattern_as_isShort() {
        val xml = readResource("/atom/channel-with-shorts.xml")
        val items = parser.parse(xml.byteInputStream())

        // Fixture has 3 entries: shortVid0001 (/shorts/), longVideo002
        // (/watch?v=), shortVid0003 (/shorts/). Verify exact counts and
        // per-id flagging — not just `any { isShort }`.
        assertEquals("expected 3 entries from shorts fixture", 3, items.size)

        val shortIds = items.filter { it.isShort }.map { it.videoId }.sorted()
        val longIds = items.filterNot { it.isShort }.map { it.videoId }.sorted()

        assertEquals(listOf("shortVid0001", "shortVid0003"), shortIds)
        assertEquals(listOf("longVideo002"), longIds)
    }

    @Test
    fun malformed_xml_returns_partial_or_empty_without_crashing() {
        val xml = readResource("/atom/malformed.xml")
        // Must not throw — fetcher relies on the parser to be defensive.
        val items = parser.parse(xml.byteInputStream())
        // Parser's defensive Throwable catch returns whatever was parsed
        // before the malformation. The fixture has exactly one well-formed
        // entry (firstGood01) followed by a truncated entry. Pinning the
        // count and id locks behavior — a regression that loses the leading
        // entry would silently pass under a `>= 0` assertion.
        assertEquals(1, items.size)
        assertEquals("firstGood01", items.first().videoId)
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().readText()
}
