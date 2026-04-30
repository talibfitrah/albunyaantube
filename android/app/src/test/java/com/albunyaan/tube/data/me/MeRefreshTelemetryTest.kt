package com.albunyaan.tube.data.me

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MeRefreshTelemetry] (T12 / spec §10 P10).
 *
 * Three guarantees we lean on at call sites:
 *  - Ring buffer trims to [MeRefreshTelemetry.MAX_EVENTS] (worst-case
 *    memory bound for the dev-settings dialog).
 *  - [MeRefreshTelemetry.snapshot] returns a defensive copy — dev-settings
 *    code can iterate it without a `ConcurrentModificationException` if
 *    a worker emits during read.
 *  - [MeRefreshTelemetry.events] is hot and emits to live subscribers.
 *
 * The class is `@Inject constructor()` so unit tests can instantiate it
 * directly — that's the same shape Hilt provides.
 */
class MeRefreshTelemetryTest {

    @Test
    fun `ring buffer trims to MAX_EVENTS oldest-first`() {
        val telemetry = MeRefreshTelemetry()
        // Push MAX + 25 events; verify the buffer holds the last MAX.
        val total = MeRefreshTelemetry.MAX_EVENTS + 25
        for (i in 0 until total) {
            telemetry.emit(
                MeRefreshTelemetry.Event.MeChannelFetched(
                    timestampMs = i.toLong(),
                    channelId = "UC$i",
                    itemsCount = 0,
                    latencyMs = 0L,
                    outcome = MeRefreshTelemetry.ChannelOutcome.NOT_MODIFIED,
                )
            )
        }
        val snap = telemetry.snapshot()
        assertEquals(MeRefreshTelemetry.MAX_EVENTS, snap.size)
        // First retained event is `total - MAX_EVENTS`-th emit.
        val first = snap.first() as MeRefreshTelemetry.Event.MeChannelFetched
        val last = snap.last() as MeRefreshTelemetry.Event.MeChannelFetched
        assertEquals((total - MeRefreshTelemetry.MAX_EVENTS).toLong(), first.timestampMs)
        assertEquals((total - 1).toLong(), last.timestampMs)
    }

    @Test
    fun `snapshot returns a defensive copy`() {
        val telemetry = MeRefreshTelemetry()
        telemetry.emit(
            MeRefreshTelemetry.Event.CooldownCleared(timestampMs = 1L)
        )
        val snap1 = telemetry.snapshot()
        val snap2 = telemetry.snapshot()
        // Two snapshots are independent List instances — mutating either
        // (after a cast) must not be observable through the other or via
        // a fresh snapshot. List<Event> is immutable, so prove it via
        // identity inequality plus a follow-up emit not reflected in
        // snap1.
        assertNotSame("snapshot must return a fresh list each call", snap1, snap2)

        telemetry.emit(
            MeRefreshTelemetry.Event.CooldownCleared(timestampMs = 2L)
        )
        // Pre-existing snapshot is unaffected by later emits.
        assertEquals(1, snap1.size)
        assertEquals(1, snap2.size)
        // A freshly taken snapshot reflects the new emit.
        assertEquals(2, telemetry.snapshot().size)
    }

    @Test
    fun `events flow emits to live subscribers`() = runTest {
        val telemetry = MeRefreshTelemetry()
        // Subscribe BEFORE the emit — events is replay=0, so a subscriber
        // that arrives after tryEmit fires would observe nothing.
        val collector = async { telemetry.events.first() }
        // Yield once so the inner coroutine has a chance to install its
        // collector on the SharedFlow before we emit.
        yield()
        telemetry.emit(
            MeRefreshTelemetry.Event.MeRefreshStarted(
                timestampMs = 1L,
                mode = MeRefreshTelemetry.Mode.PERIODIC,
                candidatesCount = 3,
            )
        )
        val first = collector.await()
        assertTrue(
            "expected MeRefreshStarted, got ${first::class.simpleName}",
            first is MeRefreshTelemetry.Event.MeRefreshStarted,
        )
        val started = first as MeRefreshTelemetry.Event.MeRefreshStarted
        assertEquals(MeRefreshTelemetry.Mode.PERIODIC, started.mode)
        assertEquals(3, started.candidatesCount)
    }

    @Test
    fun `clear empties the ring without affecting the events flow`() = runTest {
        val telemetry = MeRefreshTelemetry()
        telemetry.emit(MeRefreshTelemetry.Event.CooldownCleared(timestampMs = 5L))
        assertEquals(1, telemetry.snapshot().size)
        telemetry.clear()
        assertEquals(0, telemetry.snapshot().size)
        // After clear, fresh emits still land in the ring.
        telemetry.emit(MeRefreshTelemetry.Event.CooldownCleared(timestampMs = 6L))
        assertEquals(1, telemetry.snapshot().size)
    }
}
