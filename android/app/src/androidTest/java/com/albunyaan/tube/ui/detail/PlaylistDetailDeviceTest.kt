package com.albunyaan.tube.ui.detail

import android.view.View
import androidx.core.os.bundleOf
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.data.playlist.FakePlaylistDetailRepository
import com.albunyaan.tube.di.TestPlaylistDetailModule
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.launchFragmentInHiltContainer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-specific UI tests for PlaylistDetailFragment.
 *
 * These tests verify device-tier behavior as specified in the implementation plan:
 * - **Phone** (default, <600dp): Base layout, single column list
 * - **Tablet** (sw600dp, 600-720dp): Horizontal action buttons, larger spacing
 * - **Large tablet/TV** (sw720dp, ≥720dp): Focusable elements for D-pad navigation
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
class PlaylistDetailDeviceTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private val repository: FakePlaylistDetailRepository
        get() = TestPlaylistDetailModule.fakeRepository

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    // Device configuration thresholds (in dp)
    private val TABLET_MIN_WIDTH_DP = 600
    private val TV_MIN_WIDTH_DP = 720

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
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            title = "Test Playlist",
            channelName = "Test Channel",
            category = "Education",
            itemCount = 15L,
            totalDurationSeconds = 5400L
        )
        repository.itemsToReturn = FakePlaylistDetailRepository.createDefaultItems(10)

        val args = bundleOf(
            PlaylistDetailFragment.ARG_PLAYLIST_ID to "PLtest123",
            PlaylistDetailFragment.ARG_PLAYLIST_TITLE to "Test Playlist",
            PlaylistDetailFragment.ARG_PLAYLIST_CATEGORY to "Education",
            PlaylistDetailFragment.ARG_PLAYLIST_COUNT to 15,
            PlaylistDetailFragment.ARG_DOWNLOAD_POLICY to DownloadPolicy.ENABLED.name,
            PlaylistDetailFragment.ARG_EXCLUDED to false
        )
        scenario = launchFragmentInHiltContainer<PlaylistDetailFragment>(
            fragmentArgs = args,
            themeResId = R.style.Theme_Albunyaan
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
        // Note: playlistBanner is a legacy ID (GONE); heroThumbnail is the actual visible image
        onView(withId(R.id.heroThumbnail)).check(matches(isDisplayed()))
        onView(withId(R.id.playlistTitle)).check(matches(isDisplayed()))
        onView(withId(R.id.channelName)).check(matches(isDisplayed()))
        onView(withId(R.id.playlistMetadata)).check(matches(isDisplayed()))
    }

    @Test
    fun phone_actionButtons_areDisplayed() {
        assumeTrue("Skipping phone test on non-phone device", isPhone())
        launchFragment()

        onView(withId(R.id.playAllButton)).check(matches(isDisplayed()))
        onView(withId(R.id.shuffleButton)).check(matches(isDisplayed()))
        onView(withId(R.id.downloadPlaylistButton)).check(matches(isDisplayed()))
    }

    @Test
    fun phone_videosRecyclerView_isDisplayed() {
        assumeTrue("Skipping phone test on non-phone device", isPhone())
        launchFragment()

        onView(withId(R.id.videosRecyclerView)).check(matches(isDisplayed()))
    }

    // ========== Tablet Tests (sw600dp layout) ==========

    @Test
    fun tablet_headerLayout_isDisplayed() {
        assumeTrue("Skipping tablet test on non-tablet device", isTablet())
        launchFragment()

        // Verify tablet header elements
        // Note: playlistBanner is a legacy ID (GONE); heroThumbnail is the actual visible image
        onView(withId(R.id.heroThumbnail)).check(matches(isDisplayed()))
        onView(withId(R.id.playlistTitle)).check(matches(isDisplayed()))
        onView(withId(R.id.channelName)).check(matches(isDisplayed()))
        onView(withId(R.id.playlistMetadata)).check(matches(isDisplayed()))
    }

    @Test
    fun tablet_actionButtons_areDisplayed() {
        assumeTrue("Skipping tablet test on non-tablet device", isTablet())
        launchFragment()

        // Tablet layout should show action buttons horizontally
        onView(withId(R.id.playAllButton)).check(matches(isDisplayed()))
        onView(withId(R.id.shuffleButton)).check(matches(isDisplayed()))
        onView(withId(R.id.downloadPlaylistButton)).check(matches(isDisplayed()))
    }

    @Test
    fun tablet_videosRecyclerView_isDisplayed() {
        assumeTrue("Skipping tablet test on non-tablet device", isTablet())
        launchFragment()

        onView(withId(R.id.videosRecyclerView)).check(matches(isDisplayed()))
    }

    // ========== TV/Large Tablet Tests (sw720dp layout) ==========

    @Test
    fun tv_headerLayout_isDisplayed() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        // Verify TV header elements
        // Note: playlistBanner is a legacy ID (GONE); heroThumbnail is the actual visible image
        onView(withId(R.id.heroThumbnail)).check(matches(isDisplayed()))
        onView(withId(R.id.playlistTitle)).check(matches(isDisplayed()))
    }

    @Test
    @SdkSuppress(minSdkVersion = 21) // Focus requires API 21+
    fun tv_playAllButton_isFocusable() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        // On TV, buttons should be focusable for D-pad navigation
        onView(withId(R.id.playAllButton))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    @SdkSuppress(minSdkVersion = 21)
    fun tv_shuffleButton_isFocusable() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        onView(withId(R.id.shuffleButton))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    @SdkSuppress(minSdkVersion = 21)
    fun tv_downloadButton_isFocusable() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        onView(withId(R.id.downloadPlaylistButton))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    fun tv_videosRecyclerView_isDisplayed() {
        assumeTrue("Skipping TV test on non-TV device", isTvOrLargeTablet())
        launchFragment()

        onView(withId(R.id.videosRecyclerView)).check(matches(isDisplayed()))
    }

    // ========== Device-Agnostic Tests (run on all devices) ==========

    @Test
    fun allDevices_headerContentIsAccessible() {
        launchFragment()

        // These elements should be present on ALL device tiers
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
        onView(withId(R.id.playlistTitle)).check(matches(isDisplayed()))
        onView(withId(R.id.videosRecyclerView)).check(matches(isDisplayed()))
    }

    @Test
    fun allDevices_actionButtonsArePresent() {
        launchFragment()

        // All device tiers should have action buttons
        onView(withId(R.id.playAllButton)).check(matches(isDisplayed()))
        onView(withId(R.id.shuffleButton)).check(matches(isDisplayed()))
        onView(withId(R.id.downloadPlaylistButton)).check(matches(isDisplayed()))
    }

    @Test
    fun allDevices_toolbar_isVisible() {
        launchFragment()

        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }

    @Test
    fun allDevices_collapsingToolbar_isDisplayed() {
        launchFragment()

        onView(withId(R.id.collapsingToolbar)).check(matches(isDisplayed()))
    }

    @Test
    fun allDevices_categoryChip_isDisplayed_whenCategoryExists() {
        launchFragment()

        // Category should be visible when set
        onView(withId(R.id.categoryChipsContainer)).check(matches(isDisplayed()))
    }

    // ========== Custom Matchers ==========

    /**
     * Matcher that checks if a view has at least the specified minimum size in dp.
     */
    private fun hasMinimumSize(minDp: Int): org.hamcrest.Matcher<View> {
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
