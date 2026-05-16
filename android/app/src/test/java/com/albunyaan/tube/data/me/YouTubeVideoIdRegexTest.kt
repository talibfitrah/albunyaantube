package com.albunyaan.tube.data.me

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regex-level regression tests for F5 — the YouTube URL → 11-char video id
 * extractor. An empty video id would be stored in Room as the primary key,
 * causing cross-channel collisions on REPLACE. The fetcher filters these
 * at source, but the regex itself must also cover every URL shape the
 * extractor realistically returns.
 */
class YouTubeVideoIdRegexTest {

    private val regex = YouTubeVideoIdRegex.VIDEO_ID_REGEX

    @Test
    fun `matches standard watch URL`() {
        assertEquals(
            "dQw4w9WgXcQ",
            regex.find("https://www.youtube.com/watch?v=dQw4w9WgXcQ")?.groupValues?.get(1),
        )
    }

    @Test
    fun `matches watch URL with other params before v`() {
        assertEquals(
            "dQw4w9WgXcQ",
            regex.find("https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ&t=10s")?.groupValues?.get(1),
        )
    }

    @Test
    fun `matches youtu_be short URL`() {
        assertEquals(
            "aBcDeFgHiJk",
            regex.find("https://youtu.be/aBcDeFgHiJk?si=abc")?.groupValues?.get(1),
        )
    }

    @Test
    fun `matches shorts URL`() {
        assertEquals(
            "AZAZ09_AZAZ",
            regex.find("https://www.youtube.com/shorts/AZAZ09_AZAZ")?.groupValues?.get(1),
        )
    }

    @Test
    fun `matches embed URL`() {
        assertEquals(
            "abcdefghij0",
            regex.find("https://www.youtube.com/embed/abcdefghij0")?.groupValues?.get(1),
        )
    }

    @Test
    fun `matches older watch_id path form`() {
        assertEquals(
            "abcdefghij0",
            regex.find("https://www.youtube.com/watch/abcdefghij0")?.groupValues?.get(1),
        )
    }

    @Test
    fun `matches music youtube URL`() {
        assertEquals(
            "dQw4w9WgXcQ",
            regex.find("https://music.youtube.com/watch?v=dQw4w9WgXcQ")?.groupValues?.get(1),
        )
    }

    @Test
    fun `rejects URL without an 11-char id`() {
        assertEquals(null, regex.find("https://www.youtube.com/watch?v=too_short"))
        assertEquals(null, regex.find("https://www.youtube.com/"))
        assertEquals(null, regex.find(""))
    }

    @Test
    fun `extractor produces no empty id for any match`() {
        val samples = listOf(
            "https://www.youtube.com/watch?v=AAAAAAAAAAA",
            "https://youtu.be/BBBBBBBBBBB",
            "https://www.youtube.com/shorts/CCCCCCCCCCC",
            "https://www.youtube.com/embed/DDDDDDDDDDD",
        )
        for (s in samples) {
            val match = regex.find(s)
            assertNotNull("should match: $s", match)
            assertEquals(11, match!!.groupValues[1].length)
        }
    }
}
