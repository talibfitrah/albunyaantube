package com.albunyaan.tube.ui.player

import android.text.Spanned
import android.text.style.URLSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PlayerDescriptions] — guards the regression where raw HTML
 * from NewPipe (`<br>`, `<a href>`, `&nbsp;`, `&amp;`) was rendered as
 * literal characters in the player description card.
 *
 * Pinned to SDK 31 to match sibling Robolectric tests in this module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerDescriptionsTest {

    @Test
    fun null_input_returns_null() {
        assertNull(PlayerDescriptions.render(null))
    }

    @Test
    fun blank_input_returns_null() {
        assertNull(PlayerDescriptions.render("   "))
    }

    @Test
    fun html_with_only_whitespace_markers_returns_null() {
        assertNull(PlayerDescriptions.render("<br><br>"))
    }

    @Test
    fun br_tag_becomes_newline() {
        val rendered = PlayerDescriptions.render("Hello<br>world")
        assertEquals("Hello\nworld", rendered.toString())
    }

    @Test
    fun nbsp_entity_becomes_non_breaking_space() {
        val rendered = PlayerDescriptions.render("a&nbsp;b")
        assertEquals("a b", rendered.toString())
    }

    @Test
    fun amp_entity_becomes_ampersand() {
        val rendered = PlayerDescriptions.render("x &amp; y")
        assertEquals("x & y", rendered.toString())
    }

    @Test
    fun anchor_tag_preserves_text_and_adds_URLSpan() {
        val rendered = PlayerDescriptions.render(
            "Visit <a href=\"https://example.com\">our site</a> for more"
        )
        assertNotNull(rendered)
        assertEquals("Visit our site for more", rendered.toString())
        val spanned = rendered as Spanned
        val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("https://example.com", spans[0].url)
    }

    @Test
    fun trailing_whitespace_is_trimmed() {
        val rendered = PlayerDescriptions.render("text<br><br><br>")
        assertEquals("text", rendered.toString())
    }

    @Test
    fun leading_whitespace_is_trimmed() {
        val rendered = PlayerDescriptions.render("<br><br>text")
        assertEquals("text", rendered.toString())
    }

    @Test
    fun multi_paragraph_html_preserves_paragraph_breaks() {
        // Matches the shape of the YouTube description in the bug report:
        // multiple <br><br> separators between sentences.
        val rendered = PlayerDescriptions.render(
            "السلام عليكم<br><br>متابعينا<br><br>تحياتي"
        )
        assertEquals(
            "السلام عليكم\n\nمتابعينا\n\nتحياتي",
            rendered.toString()
        )
    }

    @Test
    fun mixed_entities_and_links_render_cleanly() {
        val rendered = PlayerDescriptions.render(
            "Foo&nbsp;&amp;&nbsp;Bar<br><a href=\"https://x.example\">x.example</a>"
        )
        assertNotNull(rendered)
        assertEquals("Foo & Bar\nx.example", rendered.toString())
        val spanned = rendered as Spanned
        val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("https://x.example", spans[0].url)
        assertTrue(
            "URLSpan should cover the visible link text",
            spanned.getSpanStart(spans[0]) >= 0
        )
    }

    @Test
    fun http_anchor_remains_clickable() {
        val rendered = PlayerDescriptions.render(
            "tap <a href=\"http://x.example\">here</a>"
        )!!
        val spans = (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("http://x.example", spans[0].url)
        assertEquals("tap here", rendered.toString())
    }

    @Test
    fun custom_scheme_anchor_text_kept_url_inert() {
        // Channel-owner-controlled `<a href="albunyaantube://playlist/EVIL">` would
        // otherwise become a tappable URLSpan that fires ACTION_VIEW and routes
        // through MainActivity.handleDeepLink to an attacker-chosen destination.
        val rendered = PlayerDescriptions.render(
            "watch <a href=\"albunyaantube://playlist/evil\">our playlist</a>"
        )!!
        val spans = (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals("custom-scheme URLSpan must be stripped", 0, spans.size)
        assertEquals("watch our playlist", rendered.toString())
    }

    @Test
    fun intent_scheme_anchor_text_kept_url_inert() {
        val rendered = PlayerDescriptions.render(
            "<a href=\"intent://x#Intent;scheme=https;end\">tap</a>"
        )!!
        val spans = (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals("intent:// URLSpan must be stripped", 0, spans.size)
        assertEquals("tap", rendered.toString())
    }

    @Test
    fun tel_scheme_anchor_text_kept_url_inert() {
        val rendered = PlayerDescriptions.render(
            "call <a href=\"tel:+1234567890\">us</a>"
        )!!
        val spans = (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals("tel: URLSpan must be stripped", 0, spans.size)
    }

    @Test
    fun mixed_safe_and_unsafe_schemes_only_safe_remains() {
        val rendered = PlayerDescriptions.render(
            "<a href=\"https://safe.example\">safe</a> and " +
                "<a href=\"intent://evil#Intent;end\">unsafe</a>"
        )!!
        val spans = (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("https://safe.example", spans[0].url)
        assertEquals("safe and unsafe", rendered.toString())
    }

    @Test
    fun scheme_match_is_case_insensitive() {
        val rendered = PlayerDescriptions.render(
            "<a href=\"HTTPS://x.example\">x</a>"
        )!!
        val spans = (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals("uppercase https must be accepted", 1, spans.size)
    }

    @Test
    fun blank_or_malformed_href_yields_no_clickable_span() {
        val rendered = PlayerDescriptions.render(
            "<a href=\"\">empty</a> and <a href=\"no-colon\">malformed</a>"
        )!!
        val spans = (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals("blank and schemeless URLs must be stripped", 0, spans.size)
    }
}
