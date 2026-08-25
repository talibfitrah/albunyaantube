package com.albunyaan.tube.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [LastInstallAttempt].
 *
 * Robolectric is only needed because [PreferenceDataStoreFactory.create]
 * spins a protobuf-backed file store; the [LastInstallAttempt] logic itself
 * is JVM-agnostic. The clock is passed explicitly as a parameter so we can
 * exercise the STALE_PENDING_THRESHOLD_MS ABANDONED path in microseconds
 * instead of waiting 24 real hours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LastInstallAttemptTest {

    @get:Rule val tmp = TemporaryFolder().also { it.create() }
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var subject: LastInstallAttempt

    @Before
    fun setup() {
        // Own the DataStore scope so we can cancel before TemporaryFolder
        // deletes the backing file (mirrors CooldownStateTest pattern).
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tmp.root, "update.preferences_pb") },
        )
        subject = LastInstallAttempt(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `empty store returns null snapshot`() = runTest {
        assertNull(subject.snapshot(currentVersion = "1.0.0-beta.16", nowMs = 1_000L))
    }

    @Test
    fun `recordPending round-trips as PENDING within stale window`() = runTest {
        subject.recordPending(targetVersion = "1.0.0-beta.17", nowMs = 1_000L)
        val snap = subject.snapshot(
            currentVersion = "1.0.0-beta.16",
            nowMs = 1_000L + 60_000L, // 1 minute later — well within 24h
        )
        assertNotNull(snap)
        assertEquals("1.0.0-beta.17", snap!!.targetVersion)
        assertEquals(LastInstallAttempt.Status.PENDING, snap.status)
        assertEquals(1_000L, snap.timestampMs)
        assertNull(snap.failureMessage)
    }

    @Test
    fun `recordSuccess round-trips and surfaces as SUCCESS`() = runTest {
        subject.recordSuccess(targetVersion = "1.0.0-beta.17", nowMs = 5_000L)
        val snap = subject.snapshot(currentVersion = "1.0.0-beta.16", nowMs = 5_100L)
        assertEquals(LastInstallAttempt.Status.SUCCESS, snap?.status)
    }

    @Test
    fun `recordFailure persists the OS-provided message`() = runTest {
        subject.recordFailure(
            targetVersion = "1.0.0-beta.17",
            message = "blocked by system",
            nowMs = 10_000L,
        )
        val snap = subject.snapshot(currentVersion = "1.0.0-beta.16", nowMs = 10_100L)
        assertEquals(LastInstallAttempt.Status.FAILURE, snap?.status)
        assertEquals("blocked by system", snap?.failureMessage)
    }

    @Test
    fun `recordFailure with null message clears prior message`() = runTest {
        // Stage one: previous failure left a message.
        subject.recordFailure("1.0.0-beta.17", "blocked", 1_000L)
        // Stage two: a later retry failed without a useful message — must not
        // re-render the stale "blocked" text.
        subject.recordFailure("1.0.0-beta.17", null, 2_000L)
        val snap = subject.snapshot("1.0.0-beta.16", 2_100L)
        assertNull(snap?.failureMessage)
    }

    @Test
    fun `clear empties the store`() = runTest {
        subject.recordFailure("1.0.0-beta.17", "boom", 1_000L)
        subject.clear()
        assertNull(subject.snapshot("1.0.0-beta.16", 1_100L))
    }

    @Test
    fun `snapshot auto-clears when currentVersion matches target`() = runTest {
        // Recorded a successful install of beta.17; the running app's
        // BuildConfig now reports beta.17 — banner is no longer relevant.
        subject.recordSuccess("1.0.0-beta.17", 1_000L)
        val first = subject.snapshot(currentVersion = "1.0.0-beta.17", nowMs = 1_100L)
        assertNull(first) // auto-cleared
        // Re-check: the store was actually cleared (not just shadowed at read).
        val second = subject.snapshot(currentVersion = "1.0.0-beta.16", nowMs = 1_200L)
        assertNull(second)
    }

    @Test
    fun `PENDING older than threshold surfaces as ABANDONED`() = runTest {
        subject.recordPending("1.0.0-beta.17", nowMs = 1_000L)
        val staleNow = 1_000L + LastInstallAttempt.STALE_PENDING_THRESHOLD_MS + 1L
        val snap = subject.snapshot("1.0.0-beta.16", staleNow)
        assertEquals(LastInstallAttempt.Status.ABANDONED, snap?.status)
        // Underlying record is still PENDING — ABANDONED is a derived state at
        // read time only. The timestamp is preserved so future debug logs can
        // tell us when the original commit happened.
        assertEquals(1_000L, snap?.timestampMs)
    }

    @Test
    fun `PENDING just inside threshold is still PENDING`() = runTest {
        subject.recordPending("1.0.0-beta.17", nowMs = 1_000L)
        val borderNow = 1_000L + LastInstallAttempt.STALE_PENDING_THRESHOLD_MS - 1L
        val snap = subject.snapshot("1.0.0-beta.16", borderNow)
        assertEquals(LastInstallAttempt.Status.PENDING, snap?.status)
    }

    @Test
    fun `SUCCESS older than threshold is NOT auto-promoted to ABANDONED`() = runTest {
        // ABANDONED only applies to PENDING records; an old SUCCESS should
        // remain SUCCESS (it's still informative, even if stale).
        subject.recordSuccess("1.0.0-beta.17", 1_000L)
        val staleNow = 1_000L + LastInstallAttempt.STALE_PENDING_THRESHOLD_MS * 2
        val snap = subject.snapshot("1.0.0-beta.16", staleNow)
        assertEquals(LastInstallAttempt.Status.SUCCESS, snap?.status)
    }

    @Test
    fun `FAILURE older than threshold is NOT auto-promoted to ABANDONED`() = runTest {
        subject.recordFailure("1.0.0-beta.17", "boom", 1_000L)
        val staleNow = 1_000L + LastInstallAttempt.STALE_PENDING_THRESHOLD_MS * 2
        val snap = subject.snapshot("1.0.0-beta.16", staleNow)
        assertEquals(LastInstallAttempt.Status.FAILURE, snap?.status)
    }

    @Test
    fun `latest record wins after multiple writes`() = runTest {
        subject.recordPending("1.0.0-beta.17", 1_000L)
        subject.recordFailure("1.0.0-beta.17", "blocked", 2_000L)
        subject.recordPending("1.0.0-beta.18", 3_000L)
        val snap = subject.snapshot("1.0.0-beta.16", 3_100L)
        assertEquals("1.0.0-beta.18", snap?.targetVersion)
        assertEquals(LastInstallAttempt.Status.PENDING, snap?.status)
        assertEquals(3_000L, snap?.timestampMs)
        // Critically: recordPending clears the prior failureMessage so a
        // retry banner doesn't render the OLD failure reason against the NEW
        // attempt.
        assertNull(snap?.failureMessage)
    }
}
