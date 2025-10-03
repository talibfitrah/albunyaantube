package com.albunyaan.tube.macrobenchmarks

import android.os.Build
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class HomeScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollFeed() {
        assumeTrue("Requires API 29+ for Perfetto frame timing capture", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM
        ) {
            startActivityAndWait()

            val recycler = device.wait(
                Until.findObject(By.res(TARGET_PACKAGE, RECYCLER_ID)),
                FIND_TIMEOUT_MS
            ) ?: return@measureRepeated

            // Scroll down then up to exercise list rendering.
            recycler.fling(Direction.DOWN)
            device.waitForIdle()
            recycler.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.albunyaan.tube"
        const val RECYCLER_ID = "recyclerView"
        const val FIND_TIMEOUT_MS = 5_000L
    }
}
