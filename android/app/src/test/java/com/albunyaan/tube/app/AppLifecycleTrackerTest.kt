package com.albunyaan.tube.app

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/** Owner bug 2026-11-03: a list reloads after the app sat in the background, so a new exclusion shows. */
class AppLifecycleTrackerTest {

    private var clock = 0L
    private val tracker = AppLifecycleTracker { clock }
    private val owner: LifecycleOwner = mock()

    @Test
    fun `a return after five minutes in the background is signalled, a shorter one and the first start are not`() = runTest {
        val seen = mutableListOf<Long>()
        backgroundScope.launch { tracker.returnsAfterIdle.collect { seen += clock } }
        runCurrent()

        tracker.onStart(owner) // cold start: the lists load anyway
        tracker.onStop(owner); clock += 5 * 60_000L - 1; tracker.onStart(owner)
        runCurrent()
        assertEquals(emptyList<Long>(), seen)

        tracker.onStop(owner); clock += 5 * 60_000L; tracker.onStart(owner)
        runCurrent()
        assertEquals(listOf(clock), seen)
    }
}
