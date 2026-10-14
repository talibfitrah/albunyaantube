package com.albunyaan.tube.ui.player

import android.text.Spanned
import android.text.style.URLSpan
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
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

    @get:Rule
    val vectors = ErrorCollector()

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
    // Owner rule: the app exists so users don't go to YouTube. A YouTube link stays as text, not a tap target.

    private fun linksIn(rendered: CharSequence?) =
        (rendered as Spanned).getSpans(0, rendered.length, URLSpan::class.java).map { it.url }

    @Test
    fun youtube_links_keep_their_text_but_are_not_tappable() {
        val hrefs = listOf(
            "https://youtu.be/V2Brp_esIVI",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://m.youtube.com/watch?v=V2Brp_esIVI",
            "https://MUSIC.YOUTUBE.COM/channel/UCabcdefghijklmnopqrstuv",
            "https://www.youtube-nocookie.com/embed/V2Brp_esIVI",
            "http://youtube.com/@channel",
        )
        for (href in hrefs) {
            val rendered = PlayerDescriptions.render("see <a href=\"$href\">$href</a> now")
            assertEquals("$href must not be tappable", emptyList<String>(), linksIn(rendered))
            assertEquals("see $href now", rendered.toString())
        }
    }

    @Test
    fun other_hosts_stay_tappable_next_to_an_inert_youtube_link() {
        val rendered = PlayerDescriptions.render(
            "<a href=\"https://youtu.be/V2Brp_esIVI\">video</a> and <a href=\"https://example.org\">site</a>"
        )
        assertEquals(listOf("https://example.org"), linksIn(rendered))
        assertEquals("video and site", rendered.toString())
    }

    @Test
    fun look_alike_hosts_are_not_youtube() {
        for (href in listOf("https://notyoutube.com/x", "https://myyoutube.org/x", "https://example.org/youtube.com")) {
            assertEquals(listOf(href), linksIn(PlayerDescriptions.render("<a href=\"$href\">x</a>")))
        }
    }

    // Fail closed: one rule decides whether a link opens, for descriptions and channel links alike. iOS must pass
    // the same vectors.

    @Test
    fun every_shared_link_rule_vector() {
        val notOpenable = listOf(
            "https:youtube.com/watch?v=x",
            "HTTPS:YOUTU.BE/x",
            "intent://x#Intent;end",
            "albunyaantube://video/x",
            "javascript:alert(1)",
            "mailto:a@b.c",
            "https://youtu.be/abc",
            "https://www.youtube.com/channel/x",
            "https://m.youtube.com/watch?v=x",
            "https://MUSIC.YOUTUBE.COM/x",
            "https://www.youtube.de/x",
            "https://www.youtube.co.uk/x",
            "https://youtubekids.com/x",
            "https://youtube.googleapis.com/x",
            "https://youtube-nocookie.com/embed/x",
            "https://youtube.com.example.org/", // accepted cost: any host with a youtube label
            "https://yt.be/x",
            "https://www.yt.be/x",
            "https://youtubego.com/",
            "https://withyoutube.com/",
            "https://youtubegaming.com/",
            "http://youtubemusic.com/",
            "http://youtubetv.com/",
            "https://youtubeshorts.com/",
            "https://x@youtube.com/a",
            "https://%79outube.com/a",
            "https://YOUTUBE.COM./a",
            "//youtube.com/a",
            "https://youtube.com\\@example.org/a",
            "https://\\youtube.com/a",
            "https://ｙｏｕｔｕｂｅ.com/a", // fullwidth
            "https://youtube\u3002com/a", // ideographic full stop
            "https://you\u00ADtube.com/a", // soft hyphen
            "https://you\ttube.com/",
            "https://you\ntube.com/",
            "https://y\u1D52utube.com/a", // modifier letter small o (Unicode newer than IDNA2003's 3.2)
            "https://\uD83C\uDD48\uD83C\uDD3E\uD83C\uDD44\uD83C\uDD43\uD83C\uDD44\uD83C\uDD31\uD83C\uDD34.com/a", // 🅈🄾🅄🅃🅄🄱🄴
            "https://you\uD82F\uDCA0tube.com/a", // U+1BCA0, a format character a browser drops
            " https://example.org/a", // the tap path parses the untrimmed URL: it would be a dead link
            "\u00A0https://example.org/a",
            "https://example.org/a ",
            "https:///a", // a blank host
            "https://ভবিষ্যৎ.com/", // accepted cost: a real IDN using a letter added after Unicode 3.2 (U+09CE)
            "",
            null,
        )
        val openable = listOf(
            "https://example.org/a",
            "http://Example.org",
            "https://youtube.com@example.org/a", // the host is example.org
            "https://مثال.مصر/", // a real IDN: xn--mgbh0fb.xn--wgbh1c
            "https://bücher.de/",
            "https://notyoutube.com/x",
            "https://myyoutube.org/x",
            "https://notyt.be/x",
        )
        // Every vector is checked and every failure reported, one message per vector.
        for (url in notOpenable) vectors.checkThat("${url?.let(::visible)} opens", PlayerDescriptions.isOpenableLink(url), equalTo(false))
        for (url in openable) vectors.checkThat("${visible(url)} does not open", PlayerDescriptions.isOpenableLink(url), equalTo(true))
    }

    @Test
    fun a_kept_link_with_an_upper_case_scheme_opens_lower_cased() {
        // Intent matching is case-sensitive: a tap on `Https://` would resolve no activity. Same range and flags.
        val shape = { href: String ->
            val text = PlayerDescriptions.render("see <a href=\"$href\">notes</a> now") as Spanned
            text.getSpans(0, text.length, URLSpan::class.java).map { listOf(it.url, text.getSpanStart(it), text.getSpanEnd(it), text.getSpanFlags(it)) }
        }
        assertEquals(shape("https://example.org/a"), shape("Https://example.org/a"))
    }

    /** Spells out the invisible characters of a vector for the failure message. */
    private fun visible(url: String) = url.replace("\t", "<TAB>").replace("\n", "<LF>").replace("\u00AD", "<U+00AD>")

    @Test
    fun crafted_youtube_hrefs_keep_their_text_and_are_not_links() {
        val rendered = PlayerDescriptions.render(
            "<a href=\"https://ｙｏｕｔｕｂｅ.com/@x\">a</a> <a href=\"https://you&#9;tube.com/\">b</a> " +
                "<a href=\"https://you&#10;tube.com/\">c</a> <a href=\"https://example.org/a\">d</a>"
        )
        assertEquals(listOf("https://example.org/a"), linksIn(rendered))
        assertEquals("a b c d", rendered.toString())
    }

    @Test
    fun a_hostless_youtube_anchor_keeps_its_text_and_is_not_a_link() {
        val rendered = PlayerDescriptions.render("see <a href=\"https:youtube.com/watch?v=x\">youtube.com/watch</a> now")
        assertEquals(emptyList<String>(), linksIn(rendered))
        assertEquals("see youtube.com/watch now", rendered.toString())
    }
}
