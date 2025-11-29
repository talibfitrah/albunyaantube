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
import com.albunyaan.tube.data.playlist.FakePlaylistDetailRepository
import com.albunyaan.tube.di.TestPlaylistDetailModule
import com.albunyaan.tube.download.DownloadPolicy
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
 * RTL (Right-to-Left) layout tests for PlaylistDetailFragment.
 *
 * Tests verify that the Playlist Detail screen renders correctly in Arabic locale,
 * with proper text alignment and layout direction for RTL languages.
 *
 * These tests ensure:
 * - Text views use RTL-compatible alignment (textAlignment="viewStart")
 * - Layout direction is properly set
 * - Playlist title, channel name, and metadata align correctly
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
class PlaylistDetailRtlTest {

    // Hilt rule must be applied first
    private val hiltRule = HiltAndroidRule(this)

    // Locale rule applies Arabic locale before activity launch
    private val localeRule = LocaleTestRule(LocaleTestRule.ARABIC)

    // Chain the rules: hiltRule first, then localeRule
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(hiltRule)
        .around(localeRule)

    private val repository: FakePlaylistDetailRepository
        get() = TestPlaylistDetailModule.fakeRepository

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
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            title = "مختارات قرآنية",
            channelName = "مشاري راشد العفاسي",
            category = "القرآن",
            itemCount = 25L,
            totalDurationSeconds = 7200L
        )
        repository.itemsToReturn = FakePlaylistDetailRepository.createDefaultItems(5)

        val args = bundleOf(
            PlaylistDetailFragment.ARG_PLAYLIST_ID to "PLtest123",
            PlaylistDetailFragment.ARG_PLAYLIST_TITLE to "مختارات قرآنية",
            PlaylistDetailFragment.ARG_PLAYLIST_CATEGORY to "القرآن",
            PlaylistDetailFragment.ARG_PLAYLIST_COUNT to 25,
            PlaylistDetailFragment.ARG_DOWNLOAD_POLICY to DownloadPolicy.ENABLED.name,
            PlaylistDetailFragment.ARG_EXCLUDED to false
        )
        scenario = launchFragmentInHiltContainer<PlaylistDetailFragment>(
            fragmentArgs = args,
            themeResId = R.style.Theme_Albunyaan
        )
        // Wait for fragment and data to load
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // ========== RTL Layout Direction Tests ==========

    @Test
    fun playlistTitle_hasRtlCompatibleAlignment() {
        launchFragment()

        onView(withId(R.id.playlistTitle))
            .check(matches(isDisplayed()))
            .check(matches(hasTextAlignmentViewStart()))
    }

    @Test
    fun channelName_hasRtlCompatibleAlignment() {
        launchFragment()

        onView(withId(R.id.channelName))
            .check(matches(isDisplayed()))
            .check(matches(hasTextAlignmentViewStart()))
    }

    @Test
    fun playlistMetadata_hasRtlCompatibleAlignment() {
        launchFragment()

        onView(withId(R.id.playlistMetadata))
            .check(matches(isDisplayed()))
            .check(matches(hasTextAlignmentViewStart()))
    }

    @Test
    fun toolbar_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun playlistBanner_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.playlistBanner))
            .check(matches(isDisplayed()))
    }

    @Test
    fun videosRecyclerView_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.videosRecyclerView))
            .check(matches(isDisplayed()))
    }

    @Test
    fun playAllButton_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.playAllButton))
            .check(matches(isDisplayed()))
    }

    @Test
    fun shuffleButton_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.shuffleButton))
            .check(matches(isDisplayed()))
    }

    @Test
    fun downloadPlaylistButton_isDisplayed_inRtl() {
        launchFragment()

        onView(withId(R.id.downloadPlaylistButton))
            .check(matches(isDisplayed()))
    }

    @Test
    fun headerContent_hasRtlLayoutDirection() {
        launchFragment()

        // Verify the header content uses RTL layout direction
        onView(withId(R.id.headerContent))
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
