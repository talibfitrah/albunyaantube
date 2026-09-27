package com.albunyaan.tube.util

import android.icu.text.CompactDecimalFormat
import android.os.Build
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Locale-aware compact count (YouTube-style) for view/subscriber counts shown
 * OUTSIDE the player.
 *
 *   en: 1.2K / 3.4M / 1.2B
 *   ar: ١٫٢ ألف / ٣٫٤ مليون / ١٫٢ مليار   (the common Arabic forms)
 *   nl: 1,2K / 3,4 mln / 1,2 mld
 *
 * Uses the platform [CompactDecimalFormat] so each locale gets its most common
 * abbreviation and its own digits/separators — consistent with the app's existing
 * localized view-count strings and the player. Player surfaces keep their own
 * formatting (UpNextAdapter / PlayerFragment) and must not call this.
 *
 * Requires API 24 (`android.icu`); app minSdk is 26.
 */
object CountFormat {

    // CompactDecimalFormat.getInstance reparses CLDR compact data on each call — heavier than
    // NumberFormat on a fast-scrolling list. Cache one per locale. Instances are used only from
    // the main thread (RecyclerView binds / fragment UI), so sharing a formatter is safe.
    private val compactFormatters = ConcurrentHashMap<Locale, CompactDecimalFormat>()

    fun compact(count: Long, locale: Locale): String {
        // Android 8.0 (API 26) ships ICU 58.2, whose CompactDecimalFormat truncates the
        // compact unit words (Arabic "مليون" -> "مليو") and ignores maximumFractionDigits
        // ("13M" instead of "12.7M"). Rather than show corrupt text to the Arabic audience,
        // fall back to the full localized number on API 26 — correct, if not abbreviated.
        // API 27+ (ICU 60.2+) formats correctly. Verified against icu4j 58.2 / 60.2 / 75.1.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return NumberFormat.getIntegerInstance(locale).format(count)
        }
        val df = compactFormatters.getOrPut(locale) {
            CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
                // One decimal max so 12_700_000 reads "12.7M", not ICU's default "13M";
                // trailing .0 is still dropped (min fraction digits stays 0), so 1_000 -> "1K".
                .apply { maximumFractionDigits = 1 }
        }
        return df.format(count)
    }

    /**
     * The count value to feed getQuantityString when the number is shown via [compact].
     *
     * A compact magnitude (>= 1000, rendered "1.2K" / "١٫٢ مليون" …) agrees with the CLDR
     * "other" category: in Arabic the counted noun is singular after ألف/مليون/مليار, NOT the
     * raw count's one/two/few form — so 1,103 must read "١٫١ ألف مشاهدة", not "…مشاهدات". Below
     * 1000 the exact number is shown, so its own category is correct. 1_000_000 maps to "other"
     * in en/ar/nl. Callers still pass the result through their Int-clamp helper.
     */
    fun compactPluralCount(count: Long): Long = if (count >= 1_000L) 1_000_000L else count
}
