package com.albunyaan.tube.ui.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import java.net.IDN

/**
 * NewPipe returns YouTube descriptions as HTML — raw `<br>`, `<a href>`,
 * `&nbsp;`, `&amp;`, etc. Setting that string directly on a `TextView`
 * renders the markup as literal characters, which is the bug captured in
 * the player screen description card. This helper parses the HTML into a
 * `CharSequence` with newlines, decoded entities, and `URLSpan`s.
 */
internal object PlayerDescriptions {

    private val ALLOWED_LINK_SCHEMES = setOf("http", "https")

    /**
     * Convert raw description text (HTML for YouTube, occasionally plain) to a
     * `CharSequence` suitable for `TextView.setText`. Returns `null` when the
     * input has no renderable content — callers should substitute the
     * "no description" fallback string in that case.
     *
     * `URLSpan`s in the parsed output are filtered to `http`/`https` only.
     * `HtmlCompat.fromHtml` preserves whatever scheme appeared in the source
     * `<a href>`; a curated channel owner could otherwise smuggle
     * `albunyaantube://`, `intent://`, `tel:`, `sms:`, `mailto:`, `geo:`, etc.
     * into a description and weaponise the tappable link to navigate inside
     * our own app's deep-link routes or hand-off to arbitrary other apps with
     * attacker-chosen payloads. Every span that fails [isOpenableLink] is
     * stripped: the link text stays visible (so users still see the URL) but
     * is inert. A kept span opens its URL with the scheme lower-cased: intent
     * matching is case-sensitive, so a tap on `Https://` would open nothing.
     */
    fun render(rawHtml: String?): CharSequence? {
        if (rawHtml.isNullOrBlank()) return null
        val parsed = HtmlCompat.fromHtml(rawHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
        val trimmed = parsed.trim()
        if (trimmed.isEmpty()) return null
        return stripUnsafeLinkSpans(trimmed)
    }

    private fun stripUnsafeLinkSpans(text: CharSequence): CharSequence {
        if (text !is Spanned) return text
        val builder = SpannableStringBuilder(text)
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val kept = span.url?.takeIf(::isOpenableLink)?.toUri()?.normalizeScheme()?.toString()?.takeIf(::isOpenableLink)
            if (kept != null && kept == span.url) continue
            builder.removeSpan(span)
            if (kept != null) builder.setSpan(URLSpan(kept), text.getSpanStart(span), text.getSpanEnd(span), text.getSpanFlags(span))
        }
        return builder
    }

    /**
     * The one rule for links the app lets the user open (descriptions and channel About links), and iOS must
     * follow the same rule. Anything that fails it is shown as text and never opened:
     * 1. no `\`, whitespace (leading and trailing too: the tap parses the raw URL), ISO control character or soft
     *    hyphen (a browser drops or rewrites them);
     * 2. scheme `http` or `https`;
     * 3. a host that converts to ASCII (IDNA, as a browser does), so `ｙｏｕｔｕｂｅ.com` cannot pass as another site;
     *    unassigned code points are refused, since a browser's newer Unicode maps them (`yᵒutube.com`); the cost:
     *    a real site whose name uses a letter added after Unicode 3.2 (`ভবিষ্যৎ.com`) shows as text;
     * 4. not YouTube ([isYouTube]).
     */
    fun isOpenableLink(url: String?): Boolean {
        val link = url ?: return false
        if (link.any { it == '\\' || it.isWhitespace() || it.isISOControl() || it == '\u00AD' }) return false
        if (!isSafeLinkScheme(link)) return false
        val host = asciiHost(link) ?: return false
        return !isYouTube(host)
    }

    /**
     * Owner rule: the app exists so users don't go to YouTube. A label that starts with `youtube` or is `youtu` or
     * `withyoutube` is YouTube (youtube.com, youtu.be, youtube-nocookie, youtubekids, youtubego, every country domain
     * and subdomain, youtube.googleapis.com), and so is YouTube's short domain `yt.be`, which redirects there. An
     * unrelated site with such a label (`youtuber.com`) is caught too; that cost is accepted.
     */
    private fun isYouTube(host: String) = host == "yt.be" || host.endsWith(".yt.be") ||
        host.split('.').any { it.startsWith("youtube") || it == "youtu" || it == "withyoutube" }

    /** The host as a browser compares it: IDNA to ASCII, lowercase, no trailing dot. Null when there is none. */
    private fun asciiHost(url: String): String? {
        val host = url.toUri().host ?: return null
        val ascii = try {
            IDN.toASCII(host, 0)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // toASCII returns ASCII or throws; the check is the shared rule's (iOS gets the host back as is).
        return ascii.lowercase().trimEnd('.').takeIf { it.isNotBlank() && it.all { c -> c.code < 0x80 } }
    }

    private fun isSafeLinkScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        val scheme = url.substring(0, colon).lowercase()
        return scheme in ALLOWED_LINK_SCHEMES
    }
}
