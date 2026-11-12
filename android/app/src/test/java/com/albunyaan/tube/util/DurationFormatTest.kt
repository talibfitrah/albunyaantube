package com.albunyaan.tube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationFormatTest {
    @Test fun `unknown or zero has no badge`() {
        assertNull(DurationFormat.badge(null))
        assertNull(DurationFormat.badge(0))
        assertNull(DurationFormat.badge(-1))
    }

    @Test fun `minutes and hours format as before`() {
        assertEquals("0:42", DurationFormat.badge(42))
        assertEquals("12:34", DurationFormat.badge(754))
        assertEquals("1:02:03", DurationFormat.badge(3723))
    }

    @Test fun `hour and long boundaries`() {
        assertEquals("59:59", DurationFormat.badge(3599))
        assertEquals("1:00:00", DurationFormat.badge(3600))
        assertEquals("10:00:00", DurationFormat.badge(36000))
        assertEquals("100:00:00", DurationFormat.badge(360000))
    }
}
