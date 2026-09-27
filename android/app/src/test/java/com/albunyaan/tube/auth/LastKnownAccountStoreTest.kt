package com.albunyaan.tube.auth

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A plain Application, not the Hilt AlBunyaanApplication: that one builds the real
// AccountRepository, whose authState collector sees the initial SignedOut on
// Dispatchers.Default and clears this same prefs file at an unpredictable moment
// — observed once clearing between write() and read() (sideloadRelease run).
@Config(application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LastKnownAccountStoreTest {

    private val store = LastKnownAccountStore(ApplicationProvider.getApplicationContext())

    private fun loaded(uid: String, status: AccountStatus = AccountStatus.ACTIVE, role: String = "user") =
        AccountState.Loaded(
            uid = uid, email = "$uid@b.com", displayName = "Name $uid", dateOfBirth = "2000-01-02",
            phoneNumber = "+31612345678", status = status, role = role,
        )

    @Test fun `round-trips every field for the same uid`() {
        val a = loaded("uid-a", AccountStatus.PENDING_PROFILE, role = "admin")
        store.write(a)

        assertEquals(a, store.read("uid-a"))
    }

    @Test fun `uid B never reads A's record`() {
        store.write(loaded("uid-a"))

        assertNull(store.read("uid-b"))
    }

    @Test fun `a later write overwrites the record`() {
        store.write(loaded("uid-a", AccountStatus.PENDING_PROFILE))
        store.write(loaded("uid-a", AccountStatus.ACTIVE))

        assertEquals(AccountStatus.ACTIVE, store.read("uid-a")?.status)
    }

    @Test fun `clear deletes the record`() {
        store.write(loaded("uid-a"))
        store.clear()

        assertNull(store.read("uid-a"))
    }
}
