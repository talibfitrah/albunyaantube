package com.albunyaan.tube.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.launchFragmentInHiltContainer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for SearchFragment functionality.
 * Tests search input, history management, and accessibility.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class SearchFragmentTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        scenario = launchFragmentInHiltContainer<SearchFragment>(
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
    fun searchView_isDisplayed() {
        onView(withId(R.id.searchView))
            .check(matches(isDisplayed()))
    }

    @Test
    fun searchView_isFocusable() {
        onView(withId(R.id.searchView))
            .check(matches(isFocusable()))
    }

    @Test
    fun searchHistorySection_isInitiallyHidden() {
        // Search history section is hidden when there's no history
        onView(withId(R.id.searchHistorySection))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun emptyState_isInitiallyHidden() {
        // Empty state is hidden initially (no search has been performed)
        onView(withId(R.id.emptyState))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun loadingState_isInitiallyHidden() {
        // Loading state is hidden initially
        onView(withId(R.id.loadingState))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun toolbar_isDisplayed() {
        // Toolbar with search view should be visible
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
    }
}
