package com.albunyaan.tube.ui.player

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import androidx.core.text.HtmlCompat

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
     * attacker-chosen payloads. Stripping non-http(s) spans keeps the link
     * text visible (so users still see the URL) but inert.
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
        val spans = text.getSpans(0, text.length, URLSpan::class.java)
        if (spans.isEmpty()) return text
        val unsafe = spans.filterNot { isSafeLinkScheme(it.url) }
        if (unsafe.isEmpty()) return text
        // Copy into a mutable builder once so we can remove the offending
        // spans without affecting the safe http(s) ones that remain clickable.
        val builder = SpannableStringBuilder(text)
        for (span in unsafe) builder.removeSpan(span)
        return builder as Spannable
    }

    private fun isSafeLinkScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        val scheme = url.substring(0, colon).lowercase()
        return scheme in ALLOWED_LINK_SCHEMES
    }
}
