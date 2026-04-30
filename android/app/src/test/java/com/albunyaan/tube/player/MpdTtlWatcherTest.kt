package com.albunyaan.tube.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MpdTtlWatcherTest {

    private lateinit var mockRegistry: SyntheticDashMpdRegistry
    private var refreshCallCount = 0

    @Before
    fun setUp() {
        mockRegistry = mock()
        refreshCallCount = 0
    }

    @Test
    fun `calls onRefreshNeeded at 90% of TTL`() = runTest {
        // fakeTime stays 0 so the coroutine (lazy with StandardTestDispatcher) reads
        // clock()=0 when it first runs inside advanceTimeBy, giving delayMs = 108_000.
        val fakeTime = 0L
        val registeredAt = 0L

        val entry = SyntheticDashMpdRegistry.MpdEntry(
            videoId = "vid",
            mpdXml = "<MPD/>",
            registeredAtMs = registeredAt
        )
        whenever(mockRegistry.getEntry("vid")).thenReturn(entry)

        val watcher = MpdTtlWatcher(
            videoId = "vid",
            registry = mockRegistry,
            onRefreshNeeded = { refreshCallCount++ },
            clock = { fakeTime }
        )

        watcher.start(this)
        advanceTimeBy(107_999L)
        assertEquals(0, refreshCallCount)

        advanceTimeBy(2L)
        assertEquals(1, refreshCallCount)
    }

    @Test
    fun `fires immediately when clock is already past 90% TTL at start time`() = runTest {
        // delayMs = (0 + 108_000 - 200_000).coerceAtLeast(0) = 0 → no delay, fires at once.
        val fakeTime = 200_000L
        val registeredAt = 0L

        val entry = SyntheticDashMpdRegistry.MpdEntry(
            videoId = "vid",
            mpdXml = "<MPD/>",
            registeredAtMs = registeredAt
        )
        whenever(mockRegistry.getEntry("vid")).thenReturn(entry)

        val watcher = MpdTtlWatcher(
            videoId = "vid",
            registry = mockRegistry,
            onRefreshNeeded = { refreshCallCount++ },
            clock = { fakeTime }
        )

        watcher.start(this)
        advanceTimeBy(1L)
        assertEquals(1, refreshCallCount)
    }

    @Test
    fun `does not call onRefreshNeeded when cancelled before 90%`() = runTest {
        var fakeTime = 0L
        val registeredAt = 1000L

        val entry = SyntheticDashMpdRegistry.MpdEntry(
            videoId = "vid",
            mpdXml = "<MPD/>",
            registeredAtMs = registeredAt
        )
        whenever(mockRegistry.getEntry("vid")).thenReturn(entry)

        val watcher = MpdTtlWatcher(
            videoId = "vid",
            registry = mockRegistry,
            onRefreshNeeded = { refreshCallCount++ },
            clock = { fakeTime }
        )

        watcher.start(this)
        watcher.cancel()
        fakeTime = registeredAt + 200_000L
        advanceTimeBy(200_000L)
        assertEquals(0, refreshCallCount)
    }

    @Test
    fun `does not call onRefreshNeeded when entry not found`() = runTest {
        whenever(mockRegistry.getEntry("vid")).thenReturn(null)

        val watcher = MpdTtlWatcher(
            videoId = "vid",
            registry = mockRegistry,
            onRefreshNeeded = { refreshCallCount++ }
        )

        watcher.start(this)
        advanceTimeBy(200_000L)
        assertEquals(0, refreshCallCount)
    }
}
