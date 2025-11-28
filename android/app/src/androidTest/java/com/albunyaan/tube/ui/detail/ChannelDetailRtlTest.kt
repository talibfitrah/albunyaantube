package com.albunyaan.tube.ui.detail

import android.view.View
import androidx.core.os.bundleOf
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.FakeChannelDetailRepository
import com.albunyaan.tube.di.TestChannelDetailModule
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.util.LocaleTestRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * RTL (Right-to-Left) layout tests for ChannelDetailFragment.
 *
 * Tests verify that the Channel Detail screen renders correctly in Arabic locale,
 * with proper text alignment and layout direction for RTL languages.
 *
 * These tests ensure:
 * - Text views use RTL-compatible alignment (textAlignment="viewStart")
 * - Layout direction is properly set
 * - Channel name, subscriber count, and summary align correctly
 *
 * ## Locale Setup
 *
 * These tests use [LocaleTestRule] to properly apply the Arabic locale to both
 * the application and instrumentation contexts. The rule:
 * - Sets the locale BEFORE the activity is created
 * - Applies locale to app resources (not just a derived context)
 * - Automatically restores the original locale after each test
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChannelDetailRtlTest {

    // Hilt rule must be applied first
    private val hiltRule = HiltAndroidRule(this)

    // Locale rule applies Arabic locale before activity launch
    private val localeRule = LocaleTestRule(LocaleTestRule.ARABIC)

    // Chain the rules: hiltRule first, then localeRule
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(hiltRule)
        .around(localeRule)

    private val repository: FakeChannelDetailRepository
        get() = TestChannelDetailModule.fakeRepository

    private var scenario: ActivityScenario<HiltTestActivity>? = null

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
            title = "مشاري راشد العفاسي",
            summaryLine = "قارئ قرآن من الكويت",
            subscriberCount = 11700000L,
            isVerified = true
        )

        val args = bundleOf(
            ChannelDetailFragment.ARG_CHANNEL_ID to "UC_test_channel",
            ChannelDetailFragment.ARG_CHANNEL_NAME to "مشاري راشد العفاسي",
            ChannelDetailFragment.ARG_EXCLUDED to false
        )
        scenario = launchFragmentInHiltContainer<ChannelDetailFragment>(
            fragmentArgs = args,
            themeResId = R.style.Theme_Albunyaan
        )
        // Wait for fragment and data to load
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // ========== RTL Layout Direction Tests ==========

    @Test
    fun channelNameText_hasRtlCompatibleAlignment() {
        launchFragment()

        onView(withId(R.id.channelNameText))
            .check(matches(isDisplayed()))
            .check(matches(hasTextAlignmentViewStart()))
    }

    @Test
    fun subscriberCountText_hasRtlCompatibleAlignment() {
        launchFragment()

        onView(withId(R.id.subscriberCountText))
            .check(matches(isDisplayed()))
            .check(matches(hasTextAlignmentViewStart()))
    }

    @Test
    fun channelSummaryText_hasRtlCompatibleAlignment() {
        launchFragment()

        onView(withId(R.id.channelSummaryText))
            .check(matches(isDisplayed()))
            .check(matches(hasTextAlignmentViewStart()))
    }

    @Test
    fun tabLayout_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.tabLayout))
            .check(matches(isDisplayed()))
    }

    @Test
    fun viewPager_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.viewPager))
            .check(matches(isDisplayed()))
    }

    @Test
    fun verifiedBadge_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.verifiedBadge))
            .check(matches(isDisplayed()))
    }

    @Test
    fun toolbar_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun channelAvatar_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.channelAvatar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun contentContainer_hasRtlLayoutDirection() {
        launchFragment()

        // Verify the content container uses RTL layout direction
        onView(withId(R.id.contentContainer))
            .check(matches(isDisplayed()))
            .check(matches(hasRtlLayoutDirection()))
    }

    // ========== Custom Matchers ==========

    /**
     * Matcher that checks if a TextView has textAlignment set to VIEW_START.
     * VIEW_START aligns text to the start of the view, which respects RTL direction.
     */
    private fun hasTextAlignmentViewStart(): Matcher<View> {
        return object : BoundedMatcher<View, android.widget.TextView>(android.widget.TextView::class.java) {
            override fun describeTo(description: Description) {
                description.appendText("has textAlignment VIEW_START")
            }

            override fun matchesSafely(textView: android.widget.TextView): Boolean {
                // TEXT_ALIGNMENT_VIEW_START = 5
                return textView.textAlignment == View.TEXT_ALIGNMENT_VIEW_START
            }
        }
    }

    /**
     * Matcher that checks if a view has RTL layout direction.
     */
    private fun hasRtlLayoutDirection(): Matcher<View> {
        return object : BoundedMatcher<View, View>(View::class.java) {
            override fun describeTo(description: Description) {
                description.appendText("has RTL layout direction")
            }

            override fun matchesSafely(view: View): Boolean {
                return view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            }
        }
    }
}
