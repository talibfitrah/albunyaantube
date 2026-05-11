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

    @Test fun `unknown wire value falls back to BLOCKED`() {
        // Safer-exit posture: unknown status routes to signIn, not a trap in bootstrap.
        assertEquals(AccountStatus.BLOCKED, AccountStatus.fromWire("future_status"))
        assertEquals(AccountStatus.BLOCKED, AccountStatus.fromWire(null))
    }
}
