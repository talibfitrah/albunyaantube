package com.albunyaan.tube.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PhoneFormatTest {

    private lateinit var ctx: Context

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext() }

    @Test fun `formatE164 returns canonical Dutch mobile`() {
        val out = PhoneFormat.formatE164(ctx, region = "NL", national = "612345678")
        assertEquals("+31612345678", out)
    }

    @Test fun `formatE164 rejects too-short Dutch number`() {
        val out = PhoneFormat.formatE164(ctx, region = "NL", national = "12345")
        assertNull(out)
    }

    @Test fun `formatE164 strips separators`() {
        val out = PhoneFormat.formatE164(ctx, region = "NL", national = "06 12 34 56 78")
        assertEquals("+31612345678", out)
    }

    @Test fun `parseDisplay splits Dutch E164 into region and national`() {
        val pair = PhoneFormat.parseDisplay(ctx, e164 = "+31612345678")
        assertNotNull(pair)
        assertEquals("NL", pair!!.first)
        assertEquals("612345678", pair.second)
    }

    @Test fun `parseDisplay returns null for malformed input`() {
        assertNull(PhoneFormat.parseDisplay(ctx, "not-a-number"))
    }
}
