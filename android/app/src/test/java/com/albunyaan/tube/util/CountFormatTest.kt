package com.albunyaan.tube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Verifies the compact view/subscriber count formatter against the real ICU
 * CompactDecimalFormat under Robolectric. Prints actuals so any ICU-version
 * drift is visible in the test log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CountFormatTest {

    private fun en(n: Long) = CountFormat.compact(n, Locale.US)

    @Test fun `english compact matches youtube style`() {
        listOf(0L, 999L, 1_000L, 1_234L, 12_000L, 12_900L, 999_999L,
            1_000_000L, 12_700_000L, 3_400_000_000L).forEach {
            println("CountFormat en $it -> ${en(it)}")
        }
        assertEquals("999", en(999))
        assertEquals("1K", en(1_000))
        assertEquals("1.2K", en(1_234))
        assertEquals("12K", en(12_000))
        assertEquals("12.9K", en(12_900))
        assertEquals("1M", en(1_000_000))
        assertEquals("12.7M", en(12_700_000))
        assertEquals("3.4B", en(3_400_000_000))
    }

    @Test fun `boundary rounds up to the next unit`() {
        // 999_999 must not render "1000K".
        assertEquals("1M", en(999_999))
    }

    @Test fun `arabic uses the common word abbreviations`() {
        val ar = Locale("ar")
        val k = CountFormat.compact(1_200, ar)
        val m = CountFormat.compact(3_400_000, ar)
        val b = CountFormat.compact(1_000_000_000, ar)
        println("CountFormat ar -> $k | $m | $b")
        assertTrue("expected ألف in $k", k.contains("ألف"))
        assertTrue("expected مليون in $m", m.contains("مليون"))
        assertTrue("expected مليار in $b", b.contains("مليار"))
    }

    @Test
    @Config(sdk = [26])
    fun `api 26 falls back to full localized number to avoid corrupt ICU output`() {
        // On API 26 the platform ICU (58.2) corrupts compact output, so we render the
        // full grouped number instead. (Robolectric uses host ICU, so this exercises the
        // SDK_INT<27 guard branch, not the 58.2 bug itself.)
        assertEquals("12,700,000", CountFormat.compact(12_700_000, Locale.US))
        assertEquals("999", CountFormat.compact(999, Locale.US))
    }

    @Test fun `compactPluralCount forces the other category once abbreviated`() {
        // Below 1000 the exact count is shown, so its own plural category is correct.
        assertEquals(0L, CountFormat.compactPluralCount(0))
        assertEquals(3L, CountFormat.compactPluralCount(3))
        assertEquals(999L, CountFormat.compactPluralCount(999))
        // >=1000 is shown compactly; the counted noun agrees as CLDR "other" (singular in
        // Arabic after ألف/مليون), NOT the raw count's one/two/few form. 1_103 would be "few".
        assertEquals(1_000_000L, CountFormat.compactPluralCount(1_000))
        assertEquals(1_000_000L, CountFormat.compactPluralCount(1_103))
        assertEquals(1_000_000L, CountFormat.compactPluralCount(2_002))
        assertEquals(1_000_000L, CountFormat.compactPluralCount(12_700_000))
    }

    @Test fun `dutch uses comma decimal and its own units`() {
        val nl = Locale("nl")
        val k = CountFormat.compact(1_200, nl)
        println("CountFormat nl -> $k | ${CountFormat.compact(3_400_000, nl)}")
        assertTrue("expected comma decimal in $k", k.contains(","))
    }
}
