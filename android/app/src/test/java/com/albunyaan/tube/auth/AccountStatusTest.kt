package com.albunyaan.tube.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountStatusTest {
    @Test fun `parses known wire values`() {
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromWire("active"))
        assertEquals(AccountStatus.PENDING_PROFILE, AccountStatus.fromWire("pending_profile"))
        assertEquals(AccountStatus.BLOCKED, AccountStatus.fromWire("blocked"))
        assertEquals(AccountStatus.DELETED, AccountStatus.fromWire("deleted"))
    }

    @Test fun `unknown wire value falls back to PENDING_PROFILE`() {
        // Conservative default: treat unknown as "needs bootstrap" so the user
        // gets routed through the explicit flow rather than silently allowed in.
        assertEquals(AccountStatus.PENDING_PROFILE, AccountStatus.fromWire("future_status"))
        assertEquals(AccountStatus.PENDING_PROFILE, AccountStatus.fromWire(null))
    }
}
