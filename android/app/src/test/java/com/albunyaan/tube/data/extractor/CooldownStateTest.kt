package com.albunyaan.tube.data.extractor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.albunyaan.tube.data.me.MeRefreshTelemetry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [CooldownState] (spec §4.6).
 *
 * Uses Robolectric solely for an Android [Context]-free DataStore — the
 * DataStore Preferences API stores its protobuf in a real on-disk file via
 * [PreferenceDataStoreFactory.create], which works in JVM unit tests as long
 * as a writable directory exists ([TemporaryFolder]).
 *
 * The clock is advanced via an [AtomicLong] passed as the `now: () -> Long`
 * seam, so cooldown windows that would normally span 24 hours can be
 * exercised in microseconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CooldownStateTest {

    @get:Rule val tmp = TemporaryFolder().also { it.create() }
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var clock: AtomicLong

    @Before
    fun setup() {
        clock = AtomicLong(1_000_000_000L)
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "cd.preferences_pb") }
        )
    }

    @After fun tearDown() { /* DataStore closes on GC; tmp folder cleans up */ }

    @Test
    fun first_trip_is_one_hour() = runTest {
        val cd = CooldownState(dataStore, { clock.get() }, MeRefreshTelemetry())
        cd.trip(IOException("429"))
        assertTrue(cd.isTripped(clock.get()))
        clock.addAndGet(59 * 60_000L)
        assertTrue(cd.isTripped(clock.get()))
        clock.addAndGet(2 * 60_000L)
        assertFalse(cd.isTripped(clock.get()))
    }

    @Test
    fun second_trip_within_24h_escalates_to_4h() = runTest {
        val cd = CooldownState(dataStore, { clock.get() }, MeRefreshTelemetry())
        cd.trip(IOException("429"))
        clock.addAndGet(2 * 60 * 60_000L) // 2h later
        cd.trip(IOException("429"))
        clock.addAndGet(3 * 60 * 60_000L) // 3h after second trip
        assertTrue(cd.isTripped(clock.get()))  // 4h cooldown still active
    }

    @Test
    fun seven_clean_days_resets_trip_count() = runTest {
        // markCleanFetch resets TRIP_COUNT to 0 after the clean-streak window (7d)
        // has elapsed since the last trip. We isolate this from the 24h trip-window
        // reset by reading the persisted TRIP_COUNT directly — chaining a follow-up
        // trip() would conflate the two reset pathways.
        val cd = CooldownState(dataStore, { clock.get() }, MeRefreshTelemetry())
        cd.trip(IOException("429"))
        // After trip: TRIP_COUNT == 1, CLEAN_STREAK_START_MS == clock.
        val tripCountAfterTrip = dataStore.data.first()[intPreferencesKey("cooldown_trip_count_24h")]
        assertEquals(1, tripCountAfterTrip)

        clock.addAndGet(8L * 24L * 60L * 60_000L)  // 8 days later — past 7-day reset window
        cd.markCleanFetch(clock.get())

        val tripCountAfterReset = dataStore.data.first()[intPreferencesKey("cooldown_trip_count_24h")]
        assertEquals(0, tripCountAfterReset)
    }

    @Test
    fun state_persists_across_restart() = runTest {
        val cd1 = CooldownState(dataStore, { clock.get() }, MeRefreshTelemetry())
        cd1.trip(IOException("429"))
        // Simulate app restart by creating a new instance pointing at the same DataStore.
        // Fresh telemetry instance — events are in-memory only and the persisted
        // DataStore is what's under test.
        val cd2 = CooldownState(dataStore, { clock.get() }, MeRefreshTelemetry())
        assertTrue(cd2.isTripped(clock.get()))
    }
}
