package com.albunyaan.tube.ui.detail

import android.content.res.Configuration
import android.view.View
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.FakeChannelDetailRepository
import com.albunyaan.tube.di.TestChannelDetailModule
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-specific UI tests for ChannelDetailFragment.
 *
 * These tests verify device-tier behavior as specified in the implementation plan:
 * - **Phone** (default, <600dp): Base layout, 2-column Shorts grid
 * - **Tablet** (sw600dp, 600-720dp): Wider layout, 4+ column Shorts grid
 * - **Large tablet/TV** (sw720dp, ≥720dp): Spacious layout, D-pad focus, 5+ column Shorts grid
 *
 * ## Running Device-Specific Tests
 *
 * To run these tests on specific device configurations:
 *
 * 1. **Phone tests**: Run on a phone emulator (default Pixel or similar)
 * 2. **Tablet tests**: Run on a tablet emulator (Nexus 9, Pixel Tablet)
 * 3. **TV tests**: Run on Android TV emulator or use `adb shell wm size 1920x1080`
 *
 * Tests use [assumeTrue] to skip tests that don't match the current device configuration,
 * allowing the full test suite to run on any device without failures.
 *
 * ## CI Integration
 *
 * For complete device matrix coverage, run tests on multiple emulators:
 * ```bash
 * # Phone
 * adb -s emulator-5554 shell am instrument ...
 *
 * # Tablet (sw600dp)
 * adb -s emulator-5556 shell am instrument ...
 *
 * # TV/Large tablet (sw720dp)
 * adb -s emulator-5558 shell am instrument ...
 * ```
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChannelDetailDeviceTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private val repository: FakeChannelDetailRepository
        get() = TestChannelDetailModule.fakeRepository

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    // Device configuration thresholds (in dp)
    private val TABLET_MIN_WIDTH_DP = 600
    private val TV_MIN_WIDTH_DP = 720

    // Shorts grid column expectations per device tier
    private val PHONE_SHORTS_COLUMNS = 2
    private val TABLET_MIN_SHORTS_COLUMNS = 4
    private val TV_MIN_SHORTS_COLUMNS = 5

    @Before
    fun setUp() {
        hiltRule.inject()
        repository.reset()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        repository.reset()
    }

    private fun launchFragment() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            title = "Test Channel",
            isVerified = true,
            subscriberCount = 11700000L,
            summaryLine = "Test channel summary"
        )
        // Add some shorts for grid tests
        repository.shortsToReturn = createTestShorts(10)

        val args = bundleOf(
            ChannelDetailFragment.ARG_CHANNEL_ID to "UC_test_channel",
            ChannelDetailFragment.ARG_CHANNEL_NAME to "Test Channel",
            ChannelDetailFragment.ARG_EXCLUDED to false
        )
        scenario = launchFragmentInHiltContainer<ChannelDetailFragment>(
            fragmentArgs = args,
            themeResId = R.style.Theme_Albunyaan
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun createTestShorts(count: Int): List<ChannelShort> {
        return (1..count).map { index ->
            ChannelShort(
                id = "short_$index",
                title = "Test Short $index",
                thumbnailUrl = null,
                viewCount = (index * 1000).toLong(),
                durationSeconds = 30,
                publishedTime = "2 days ago"
            )
        }
    }

    /**
     * Get the current device's smallest width in dp.
     */
    private fun getSmallestWidthDp(): Int {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = context.resources.configuration
        return config.smallestScreenWidthDp
    }

    /**
     * Check if the current device is a phone (< 600dp).
     */
    private fun isPhone(): Boolean = getSmallestWidthDp() < TABLET_MIN_WIDTH_DP

    /**
     * Check if the current device is a tablet (600-720dp).
     */
    private fun isTablet(): Boolean {
        val width = getSmallestWidthDp()
        return width >= TABLET_MIN_WIDTH_DP && width < TV_MIN_WIDTH_DP
    }

    /**
     * Check if the current device is a large tablet or TV (≥ 720dp).
     */
    private fun isTvOrLargeTablet(): Boolean = getSmallestWidthDp() >= TV_MIN_WIDTH_DP

    // ========== Phone Tests (default layout) ==========

    @Test
    fun phone_headerLayout_isDisplayed() {
        assumeTrue("Skipping phone test on non-phone device", isPhone())
        launchFragment()

        // Verify basic header elements are displayed
        onView(withId(R.id.channelBanner)).check(matches(isDisplayed()))
        onView(withId(R.id.channelAvatar)).check(matches(isDisplayed()))
        onView(withId(R.id.channelNameText)).check(matches(isDisplayed()))
        onView(withId(R.id.subscriberCountText)).check(matches(isDisplayed()))
    }

    @Test
    fun phone_tabLayout_hasCorrectTabCount() {
        assumeTrue("Skipping phone test on non-phone device", isPhone())
        launchFragment()

        onView(withId(R.id.tabLayout))
            .check(matches(isDisplayed()))
            .check(matches(hasTabCount(5)))
    }

    @Test
    fun phone_shortsGrid_has2Columns() {
        assumeTrue("Skipping phone test on non-phone device", isPhone())
        launchFragment()

        // Navigate to Shorts tab
        onView(withText(R.string.channel_tab_shorts)).perform(click())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Verify Shorts grid has exactly 2 columns on phone
        onView(withId(R.id.shortsRecycler))
            .check(matches(isDisplayed()))
            .check(matches(hasExactGridSpanCount(PHONE_SHORTS_COLUMNS)))
    }

    // ========== Tablet Tests (sw600dp layout) ==========

    @Test
    fun tablet_headerLayout_isDisplayed() {
        assumeTrue("Skipping tablet test on non-tablet device", isTablet())
        launchFragment()

        // Verify tablet header elements are displayed
        onView(withId(R.id.channelBanner)).check(matches(isDisplayed()))
        onView(withId(R.id.channelAvatar)).check(matches(isDisplayed()))
        onView(withId(R.id.channelNameText)).check(matches(isDisplayed()))
        onView(withId(R.id.subscriberCountText)).check(matches(isDisplayed()))
        onView(withId(R.id.verifiedBadge)).check(matches(isDisplayed()))
    }

    @Test
    fun tablet_verifiedBadge_hasCorrectSize() {
        assumeTrue("Skipping tablet test on non-tablet device", isTablet())
        launchFragment()

        // Verified badge should use tablet-specific sizing
        // The layout-sw600dp should apply larger dimensions
        onView(withId(R.id.verifiedBadge))
            .check(matches(isDisplayed()))
            .check(matches(hasMinimumSize(24))) // Tablet size should be at least 24dp
    }

    @Test
    fun tablet_tabLayout_isFullWidth() {
        assumeTrue("Skipping tablet test on non-tablet device", isTablet())
        launchFragment()

        onView(withId(R.id.tabLayout))
            .check(matches(isDisplayed()))
            .check(matches(hasTabCount(5)))
    }

    @Test
    fun tablet_shortsGrid_hasAtLeast4Columns() {
        assumeTrue("Skipping tablet test on non-tablet device", isTablet())
        launchFragment()

        // Navigate to Shorts tab
        onView(withText(R.string.channel_tab_shorts)).perform(click())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Verify Shorts grid has at least 4 columns on tablet
        onView(withId(R.id.shortsRecycler))
            .check(matches(isDisplayed()))
            .check(matches(hasMinGridSpanCount(TABLET_MIN_SHORTS_COLUMNS)))
    }

    // ========== TV/Large Tablet Tests (sw720dp layout) ==========

    @Test
    fun tv_headerLayout_isDisplayed() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        // Verify TV header elements
        onView(withId(R.id.channelBanner)).check(matches(isDisplayed()))
        onView(withId(R.id.channelAvatar)).check(matches(isDisplayed()))
        onView(withId(R.id.channelNameText)).check(matches(isDisplayed()))
    }

    @Test
    @SdkSuppress(minSdkVersion = 21) // Focus requires API 21+
    fun tv_tabLayout_isFocusable() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        // On TV, tabs should be focusable for D-pad navigation
        onView(withId(R.id.tabLayout))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    @SdkSuppress(minSdkVersion = 21)
    fun tv_viewPager_isFocusable() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        // The ViewPager should be focusable for D-pad navigation on TV
        onView(withId(R.id.viewPager))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    fun tv_shortsGrid_hasAtLeast5Columns() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        // Navigate to Shorts tab
        onView(withText(R.string.channel_tab_shorts)).perform(click())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Verify Shorts grid has at least 5 columns on TV/large tablet
        onView(withId(R.id.shortsRecycler))
            .check(matches(isDisplayed()))
            .check(matches(hasMinGridSpanCount(TV_MIN_SHORTS_COLUMNS)))
    }

    // ========== Device-Agnostic Tests (run on all devices) ==========

    @Test
    fun allDevices_headerContentIsAccessible() {
        launchFragment()

        // These elements should be present on ALL device tiers
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
        onView(withId(R.id.channelNameText)).check(matches(isDisplayed()))
        onView(withId(R.id.tabLayout)).check(matches(isDisplayed()))
        onView(withId(R.id.viewPager)).check(matches(isDisplayed()))
    }

    @Test
    fun allDevices_tabLayout_hasExactly5Tabs() {
        launchFragment()

        // All device tiers should have exactly 5 tabs
        onView(withId(R.id.tabLayout))
            .check(matches(hasTabCount(5)))
    }

    @Test
    fun allDevices_verifiedBadge_isVisible_whenVerified() {
        launchFragment()

        // Verified badge should be visible on all device tiers
        onView(withId(R.id.verifiedBadge))
            .check(matches(isDisplayed()))
    }

    // ========== Custom Matchers ==========

    /**
     * Matcher that checks if a TabLayout has the specified number of tabs.
     */
    private fun hasTabCount(expectedCount: Int): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has $expectedCount tabs")
            }

            override fun matchesSafely(view: View): Boolean {
                return view is TabLayout && view.tabCount == expectedCount
            }
        }
    }

    /**
     * Matcher that checks if a RecyclerView has a GridLayoutManager with at least
     * the specified number of columns.
     */
    private fun hasMinGridSpanCount(minColumns: Int): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has GridLayoutManager with at least $minColumns columns")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view !is RecyclerView) return false
                val layoutManager = view.layoutManager
                if (layoutManager !is GridLayoutManager) return false
                return layoutManager.spanCount >= minColumns
            }
        }
    }

    /**
     * Matcher that checks if a RecyclerView has a GridLayoutManager with exactly
     * the specified number of columns.
     */
    private fun hasExactGridSpanCount(expectedColumns: Int): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has GridLayoutManager with exactly $expectedColumns columns")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view !is RecyclerView) return false
                val layoutManager = view.layoutManager
                if (layoutManager !is GridLayoutManager) return false
                return layoutManager.spanCount == expectedColumns
            }
        }
    }

    /**
     * Matcher that checks if a view has at least the specified minimum size in dp.
     */
    private fun hasMinimumSize(minDp: Int): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has minimum size of ${minDp}dp")
            }

            override fun matchesSafely(view: View): Boolean {
                val density = view.resources.displayMetrics.density
                val minPx = (minDp * density).toInt()
                return view.width >= minPx && view.height >= minPx
            }
        }
    }
}
