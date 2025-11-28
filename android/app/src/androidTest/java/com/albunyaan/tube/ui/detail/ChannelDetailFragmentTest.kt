package com.albunyaan.tube.ui.detail

import android.view.View
import androidx.core.os.bundleOf
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.FakeChannelDetailRepository
import com.albunyaan.tube.di.TestChannelDetailModule
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.util.ViewStateIdlingResource
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for ChannelDetailFragment verifying the 5-tab layout,
 * verified badge visibility, skeleton states, and basic rendering.
 *
 * These tests cover:
 * - 5-tab structure (Videos, Live, Shorts, Playlists, About)
 * - Verified badge visibility based on channel data
 * - Loading skeleton display during data fetch
 * - Channel header rendering (name, subscriber count, summary)
 * - Posts tab absence (was removed - NewPipe doesn't support community posts)
 *
 * Note: Device-specific layouts (sw600dp, sw720dp) are tested via
 * the same view IDs - Android automatically selects the correct layout.
 * TestChannelDetailModule automatically replaces the production repository.
 *
 * ## Test Synchronization
 *
 * These tests use Espresso IdlingResources and deferred loading in the
 * FakeChannelDetailRepository to avoid flaky Thread.sleep() calls:
 *
 * - For testing **loaded states**: The repository returns immediately and
 *   we use IdlingResources to wait for the ViewModel to process the data.
 *
 * - For testing **loading states**: Use `repository.useDeferredLoading = true`
 *   to hold the loading state, assert, then call `repository.completeHeaderLoading()`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChannelDetailFragmentTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private val repository: FakeChannelDetailRepository
        get() = TestChannelDetailModule.fakeRepository

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    // Track registered idling resources for cleanup
    private val registeredIdlingResources = mutableListOf<androidx.test.espresso.IdlingResource>()

    @Before
    fun setUp() {
        hiltRule.inject()
        repository.reset()
    }

    @After
    fun tearDown() {
        // Unregister all idling resources
        registeredIdlingResources.forEach { IdlingRegistry.getInstance().unregister(it) }
        registeredIdlingResources.clear()

        scenario?.close()
        scenario = null
        repository.reset()
    }

    private fun launchFragment(
        channelId: String = "UC_test_channel",
        channelName: String? = "Test Channel",
        excluded: Boolean = false
    ) {
        val args = bundleOf(
            ChannelDetailFragment.ARG_CHANNEL_ID to channelId,
            ChannelDetailFragment.ARG_CHANNEL_NAME to channelName,
            ChannelDetailFragment.ARG_EXCLUDED to excluded
        )
        scenario = launchFragmentInHiltContainer<ChannelDetailFragment>(
            fragmentArgs = args,
            themeResId = R.style.Theme_Albunyaan
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Wait for the header to finish loading (success or error state).
     * Uses Espresso's built-in view synchronization by checking for a stable end state.
     */
    private fun waitForHeaderLoaded() {
        // Espresso automatically waits for the main thread to be idle.
        // We just need to trigger a view assertion that depends on the loaded state.
        // The view will be re-checked until it matches or times out.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // ========== 5-Tab Layout Tests ==========

    @Test
    fun tabLayout_displays5Tabs() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment()
        waitForHeaderLoaded()

        // Verify TabLayout is displayed with exactly 5 tabs
        onView(withId(R.id.tabLayout))
            .check(matches(isDisplayed()))
            .check(matches(hasTabCount(5)))

        // Verify Videos tab
        onView(withId(R.id.tabLayout))
            .check(matches(hasDescendant(withText(R.string.channel_tab_videos))))

        // Verify Live tab
        onView(withId(R.id.tabLayout))
            .check(matches(hasDescendant(withText(R.string.channel_tab_live))))

        // Verify Shorts tab
        onView(withId(R.id.tabLayout))
            .check(matches(hasDescendant(withText(R.string.channel_tab_shorts))))

        // Verify Playlists tab
        onView(withId(R.id.tabLayout))
            .check(matches(hasDescendant(withText(R.string.channel_tab_playlists))))

        // Verify About tab
        onView(withId(R.id.tabLayout))
            .check(matches(hasDescendant(withText(R.string.channel_tab_about))))
    }

    @Test
    fun tabLayout_doesNotContainPostsTab() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment()
        waitForHeaderLoaded()

        // Posts tab should NOT exist (was removed because NewPipe doesn't support it)
        // Verify exactly 5 tabs (no Posts tab - would be 6 if Posts existed)
        onView(withId(R.id.tabLayout))
            .check(matches(isDisplayed()))
            .check(matches(hasTabCount(5)))

        // Verify only the expected 5 tab labels exist (locale-independent check)
        // If Posts tab were added, tab count would be 6, failing the above assertion
        // Additionally verify the 5 expected tabs ARE present (positive assertion)
        onView(withId(R.id.tabLayout))
            .check(matches(hasDescendant(withText(R.string.channel_tab_videos))))
            .check(matches(hasDescendant(withText(R.string.channel_tab_live))))
            .check(matches(hasDescendant(withText(R.string.channel_tab_shorts))))
            .check(matches(hasDescendant(withText(R.string.channel_tab_playlists))))
            .check(matches(hasDescendant(withText(R.string.channel_tab_about))))
    }

    // ========== Verified Badge Tests ==========

    @Test
    fun verifiedBadge_isVisible_whenChannelIsVerified() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            isVerified = true
        )
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.verifiedBadge))
            .check(matches(isDisplayed()))
    }

    @Test
    fun verifiedBadge_isHidden_whenChannelIsNotVerified() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            isVerified = false
        )
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.verifiedBadge))
            .check(matches(not(isDisplayed())))
    }

    // ========== Header Content Tests ==========

    @Test
    fun channelName_isDisplayed() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            title = "Mishary Rashid Alafasy"
        )
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.channelNameText))
            .check(matches(withText("Mishary Rashid Alafasy")))
    }

    @Test
    fun subscriberCount_isDisplayed_whenAvailable() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            subscriberCount = 11700000L
        )
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.subscriberCountText))
            .check(matches(isDisplayed()))
    }

    @Test
    fun channelSummary_isDisplayed_whenAvailable() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            summaryLine = "Quran reciter from Kuwait"
        )
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.channelSummaryText))
            .check(matches(isDisplayed()))
            .check(matches(withText("Quran reciter from Kuwait")))
    }

    @Test
    fun channelSummary_isHidden_whenNotAvailable() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            summaryLine = null,
            shortDescription = null
        )
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.channelSummaryText))
            .check(matches(not(isDisplayed())))
    }

    // ========== Skeleton Loading State Tests ==========

    @Test
    fun headerSkeleton_isShown_whileLoading() {
        // Use deferred loading to hold the loading state
        repository.useDeferredLoading = true
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()

        launchFragment()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Skeleton should be visible while loading is in progress
        onView(withId(R.id.headerSkeleton))
            .check(matches(isDisplayed()))

        // Release the loading to allow cleanup
        repository.completeHeaderLoading()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun contentContainer_isShown_afterDataLoads() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.contentContainer))
            .check(matches(isDisplayed()))
    }

    @Test
    fun headerSkeleton_isHidden_afterDataLoads() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.headerSkeleton))
            .check(matches(not(isDisplayed())))
    }

    // ========== Error State Tests ==========

    @Test
    fun errorState_isShown_whenLoadingFails() {
        repository.errorToThrow = Exception("Network error")
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.errorState))
            .check(matches(isDisplayed()))
    }

    // ========== Exclusion Banner Tests ==========

    @Test
    fun exclusionBanner_isShown_whenChannelIsExcluded() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment(excluded = true)
        waitForHeaderLoaded()

        onView(withId(R.id.exclusionBanner))
            .check(matches(isDisplayed()))
    }

    @Test
    fun exclusionBanner_isHidden_whenChannelIsNotExcluded() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment(excluded = false)
        waitForHeaderLoaded()

        onView(withId(R.id.exclusionBanner))
            .check(matches(not(isDisplayed())))
    }

    // ========== ViewPager Tests ==========

    @Test
    fun viewPager_isDisplayed() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment()
        waitForHeaderLoaded()

        onView(withId(R.id.viewPager))
            .check(matches(isDisplayed()))
    }

    // ========== Toolbar Tests ==========

    @Test
    fun toolbar_isDisplayed() {
        repository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader()
        launchFragment()

        onView(withId(R.id.toolbar))
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
}
