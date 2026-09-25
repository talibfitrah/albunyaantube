package com.albunyaan.tube.util

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UploadAgeTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    @Test fun `unknown upload date renders no age`() {
        assertNull(UploadAge.format(res, null))
    }

    @Test fun `known ages keep today, days, weeks, months, years`() {
        assertEquals("Today", UploadAge.format(res, 0))
        assertEquals("3 days ago", UploadAge.format(res, 3))
        assertEquals("2 weeks ago", UploadAge.format(res, 14))
        assertEquals("2 months ago", UploadAge.format(res, 60))
        assertEquals("1 year ago", UploadAge.format(res, 400))
    }

    @Test fun `meta line has no stray separator when the age is unknown`() {
        assertEquals("1K views", UploadAge.joinMeta("1K views", null))
        assertEquals("Today", UploadAge.joinMeta("", "Today"))
        assertEquals("1K views • Today", UploadAge.joinMeta("1K views", "Today"))
        assertEquals("", UploadAge.joinMeta("", null))
    }

    // --- YouTube's English age text (NewPipe runs Locale.US), re-rendered in the app's locale ---

    @Test fun `parses the English age text into minutes like iOS EnglishCounts`() {
        assertEquals(120, UploadAge.minutesAgo("2 hours ago"))
        assertEquals(3 * 1440, UploadAge.minutesAgo("Streamed 3 days ago"))
        assertEquals(1, UploadAge.minutesAgo("1 minute ago"))
        assertEquals(0, UploadAge.minutesAgo("30 seconds ago"))
        assertEquals(2 * 7 * 1440, UploadAge.minutesAgo("2 weeks ago"))
        assertNull(UploadAge.minutesAgo("Premiered Jan 5, 2024"))
        assertNull(UploadAge.minutesAgo(null))
    }

    @Test fun `minutes then hours then the day ladder`() {
        assertEquals("1 minute ago", UploadAge.formatMinutes(res, 0))  // never "0 minutes ago"
        assertEquals("45 minutes ago", UploadAge.formatMinutes(res, 45))
        assertEquals("2 hours ago", UploadAge.formatMinutes(res, 120))
        assertEquals("3 days ago", UploadAge.formatMinutes(res, 3 * 1440))
        assertEquals("2 weeks ago", UploadAge.formatMinutes(res, 14 * 1440))
    }

    @Test fun `English text is localized, unparseable text is kept, nothing invented`() {
        assertEquals("2 hours ago", UploadAge.fromEnglish(res, "2 hours ago"))
        assertEquals("Premiered Jan 5, 2024", UploadAge.fromEnglish(res, "Premiered Jan 5, 2024"))
        assertNull(UploadAge.fromEnglish(res, null))
        assertNull(UploadAge.fromEnglish(res, " "))
    }

    @Test
    @Config(qualifiers = "ar")
    fun `Arabic UI gets Arabic hours, not YouTube's English`() {
        val arRes = ApplicationProvider.getApplicationContext<android.app.Application>().resources
        assertEquals(arRes.getQuantityString(com.albunyaan.tube.R.plurals.time_ago_hours, 2, 2), UploadAge.fromEnglish(arRes, "2 hours ago"))
        assertEquals("منذ ساعتين", UploadAge.fromEnglish(arRes, "2 hours ago"))
    }
}
