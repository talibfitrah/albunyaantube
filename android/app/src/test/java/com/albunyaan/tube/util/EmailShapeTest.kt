package com.albunyaan.tube.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailShapeTest {
    @Test fun `accepts simple address`()              = assertTrue(isEmailShape("a@b.co"))
    @Test fun `accepts multi-dot domain`()            = assertTrue(isEmailShape("u.s@a.b.co"))
    @Test fun `rejects missing at`()                  = assertFalse(isEmailShape("ab.co"))
    @Test fun `rejects double at`()                   = assertFalse(isEmailShape("a@@b.co"))
    @Test fun `rejects empty local`()                 = assertFalse(isEmailShape("@b.co"))
    @Test fun `rejects empty domain`()                = assertFalse(isEmailShape("a@"))
    @Test fun `rejects domain without dot`()          = assertFalse(isEmailShape("a@b"))
    @Test fun `rejects leading-dot domain`()          = assertFalse(isEmailShape("a@.co"))
    @Test fun `rejects trailing-dot domain`()         = assertFalse(isEmailShape("a@b."))
}
