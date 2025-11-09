package com.albunyaan.tube.navigation

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.albunyaan.tube.R
import com.albunyaan.tube.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for navigation functionality.
 * Tests that the app launches and navigation works correctly.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationGraphTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun mainActivity_launches() {
        // Verify MainActivity launches successfully
        // This test ensures the app starts without crashing
        Thread.sleep(2000) // Wait for splash
        onView(withId(R.id.mainBottomNav))
            .check(matches(isDisplayed()))
    }

    @Test
    fun bottomNavigation_navigatesToDownloads() {
        activityRule.scenario.onActivity { activity ->
            // Give time for splash to navigate to main shell
            Thread.sleep(2000)

            // Verify we can navigate to downloads tab
            onView(withId(R.id.downloadsFragment)).perform(click())
            Thread.sleep(500)

            // Verify downloads fragment is displayed
            onView(withId(R.id.downloadsRecyclerView))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun bottomNavigation_allTabsClickable() {
        activityRule.scenario.onActivity { activity ->
            // Give time for splash to navigate to main shell
            Thread.sleep(2000)

            // Verify all bottom navigation items are clickable
            onView(withId(R.id.homeFragment)).perform(click())
            Thread.sleep(300)

            onView(withId(R.id.channelsFragment)).perform(click())
            Thread.sleep(300)

            onView(withId(R.id.playlistsFragment)).perform(click())
            Thread.sleep(300)

            onView(withId(R.id.videosFragment)).perform(click())
            Thread.sleep(300)

            onView(withId(R.id.downloadsFragment)).perform(click())
            Thread.sleep(300)
        }
    }
}

