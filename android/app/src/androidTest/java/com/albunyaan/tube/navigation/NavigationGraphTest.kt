package com.albunyaan.tube.navigation

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast
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
        clickNavItem(R.id.downloadsFragment)

        // Verify downloads fragment is displayed via empty state (shown when no downloads)
        onView(withId(R.id.emptyDownloads))
            .check(matches(isDisplayed()))
    }

    @Test
    fun bottomNavigation_allTabsClickable() {
        clickNavItem(R.id.homeFragment)
        clickNavItem(R.id.channelsFragment)
        clickNavItem(R.id.playlistsFragment)
        clickNavItem(R.id.videosFragment)
        clickNavItem(R.id.downloadsFragment)
    }

    private fun clickNavItem(itemId: Int) {
        onView(withId(itemId)).perform(clickWhenVisible())
    }

    private fun clickWhenVisible(): ViewAction {
        return object : ViewAction {
            override fun getConstraints() = isDisplayingAtLeast(1)
            override fun getDescription() = "click when at least 1% visible"
            override fun perform(uiController: UiController, view: View) {
                view.performClick()
                uiController.loopMainThreadUntilIdle()
            }
        }
    }
}
