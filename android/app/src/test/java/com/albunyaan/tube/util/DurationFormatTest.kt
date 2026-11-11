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
}
