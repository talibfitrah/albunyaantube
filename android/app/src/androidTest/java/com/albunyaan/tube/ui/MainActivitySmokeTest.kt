package com.albunyaan.tube.ui

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.albunyaan.tube.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal activity-level smoke test to verify MainActivity launches correctly.
 *
 * This test ensures the full startup flow works (locale setup, nav graph wiring,
 * Hilt injection, back press handling, etc.) without crashing.
 *
 * Fragment-specific behavior is tested in isolation (NavigationGraphTest,
 * AccessibilityTest, etc.), so this test only verifies the activity hosts
 * the nav graph successfully.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivitySmokeTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    var activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun mainActivity_launchesAndShowsNavHost() {
        // Verify the activity launched successfully by checking the nav host
        // This proves MainActivity started successfully with:
        // - LocaleManager.applyStoredLocale()
        // - Navigation graph inflation
        // - Hilt dependency injection
        // - Back press callback registration
        activityRule.scenario.onActivity { activity ->
            val navHost = activity.findViewById<android.view.View>(R.id.nav_host_fragment)
            assertNotNull("NavHostFragment should exist in activity", navHost)
            assertEquals("NavHostFragment should be visible", android.view.View.VISIBLE, navHost.visibility)
        }
    }
}
