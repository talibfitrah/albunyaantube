package com.albunyaan.tube.navigation

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.ui.MainShellFragment
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for navigation functionality.
 * Tests that MainShellFragment and its bottom navigation work correctly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationGraphTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        scenario = launchFragmentInHiltContainer<MainShellFragment>(
            themeResId = R.style.Theme_Albunyaan
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun mainShellFragment_launches() {
        onView(withId(R.id.mainBottomNav))
            .check(matches(isDisplayed()))
    }

    @Test
    fun bottomNavigation_navigatesToDownloads() {
        // Click downloads tab
        onView(withId(R.id.downloadsFragment)).perform(click())

        // Verify downloads fragment is displayed via empty state (shown when no downloads)
        onView(withId(R.id.emptyDownloads))
            .check(matches(isDisplayed()))
    }

    @Test
    fun bottomNavigation_allTabsClickable() {
        onView(withId(R.id.homeFragment)).perform(click())
        onView(withId(R.id.channelsFragment)).perform(click())
        onView(withId(R.id.playlistsFragment)).perform(click())
        onView(withId(R.id.videosFragment)).perform(click())
        onView(withId(R.id.downloadsFragment)).perform(click())
    }
}
