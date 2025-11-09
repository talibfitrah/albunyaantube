package com.albunyaan.tube.macrobenchmarks

import android.os.Build
import androidx.benchmark.Shell
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdStartBaselineProfile {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupBaseline() {
        assumeTrue(
            "Baseline profile collection requires API 33+ or a rooted session.",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                Shell.isSessionRooted()
        )

        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 5,
            stableIterations = 3,
            includeInStartupProfile = true,
        ) {
            this.pressHome()
            this.startActivityAndWait()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.albunyaan.tube"
    }
}

