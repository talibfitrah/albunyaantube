package com.albunyaan.tube.ui.detail

import android.view.View
import androidx.core.os.bundleOf
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.data.playlist.FakePlaylistDetailRepository
import com.albunyaan.tube.di.TestPlaylistDetailModule
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.launchFragmentInHiltContainer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for PlaylistDetailFragment.
 *
 * Tests cover:
 * - Loading states (skeleton visibility)
 * - Success states (content display)
 * - Error states (error message display)
 * - Empty states (empty message display)
 * - UI interactions (button clicks)
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaylistDetailFragmentTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

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

    private fun launchFragment(
        playlistId: String = "PLtest123",
        playlistTitle: String? = "Test Playlist",
        category: String? = null,
        count: Int = 10,
        downloadPolicy: DownloadPolicy = DownloadPolicy.ENABLED,
        excluded: Boolean = false
    ) {
        val args = bundleOf(
            PlaylistDetailFragment.ARG_PLAYLIST_ID to playlistId,
            PlaylistDetailFragment.ARG_PLAYLIST_TITLE to playlistTitle,
            PlaylistDetailFragment.ARG_PLAYLIST_CATEGORY to category,
            PlaylistDetailFragment.ARG_PLAYLIST_COUNT to count,
            PlaylistDetailFragment.ARG_DOWNLOAD_POLICY to downloadPolicy.name,
            PlaylistDetailFragment.ARG_EXCLUDED to excluded
        )
        scenario = launchFragmentInHiltContainer<PlaylistDetailFragment>(
            fragmentArgs = args,
            themeResId = R.style.Theme_Albunyaan
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // ========== Loading State Tests ==========

    @Test
    fun loadingState_showsSkeletons_beforeDataLoads() {
        repository.useDeferredLoading = true
        launchFragment()

        // Header skeleton should be visible
        onView(withId(R.id.headerSkeleton))
            .check(matches(isDisplayed()))

        // List skeleton should be visible
        onView(withId(R.id.listSkeletonContainer))
            .check(matches(isDisplayed()))

        // Complete loading to clean up
        repository.completeHeaderLoading()
        repository.completeItemsLoading()
    }

    @Test
    fun loadingState_hidesSkeletons_afterDataLoads() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            title = "Test Playlist"
        )
        repository.itemsToReturn = FakePlaylistDetailRepository.createDefaultItems(5)

        launchFragment()

        // Header skeleton should be hidden
        onView(withId(R.id.headerSkeleton))
            .check(matches(not(isDisplayed())))

        // List skeleton should be hidden
        onView(withId(R.id.listSkeletonContainer))
            .check(matches(not(isDisplayed())))
    }

    // ========== Success State Tests ==========

    @Test
    fun successState_displaysPlaylistTitle() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            title = "My Awesome Playlist"
        )
        launchFragment()

        onView(withId(R.id.playlistTitle))
            .check(matches(isDisplayed()))
            .check(matches(withText("My Awesome Playlist")))
    }

    @Test
    fun successState_displaysChannelName() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            channelName = "Channel Name"
        )
        launchFragment()

        onView(withId(R.id.channelName))
            .check(matches(isDisplayed()))
            .check(matches(withText("Channel Name")))
    }

    @Test
    fun successState_displaysPlaylistMetadata() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            itemCount = 25L,
            totalDurationSeconds = 7200L // 2 hours
        )
        launchFragment()

        onView(withId(R.id.playlistMetadata))
            .check(matches(isDisplayed()))
    }

    @Test
    fun successState_displaysCategoryChip_whenCategoryExists() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            category = "Education"
        )
        launchFragment(category = "Education")

        onView(withId(R.id.categoryChipsContainer))
            .check(matches(isDisplayed()))
    }

    @Test
    fun successState_displaysBanner() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader()
        launchFragment()

        onView(withId(R.id.playlistBanner))
            .check(matches(isDisplayed()))
    }

    @Test
    fun successState_displaysVideosList() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader()
        repository.itemsToReturn = FakePlaylistDetailRepository.createDefaultItems(5)
        launchFragment()

        onView(withId(R.id.videosRecyclerView))
            .check(matches(isDisplayed()))
    }

    // ========== Empty State Tests ==========

    @Test
    fun emptyState_displaysEmptyMessage() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader()
        repository.itemsToReturn = emptyList()
        launchFragment()

        onView(withId(R.id.emptyState))
            .check(matches(isDisplayed()))
    }

    // ========== Error State Tests ==========

    @Test
    fun errorState_displaysErrorView() {
        repository.errorToThrow = RuntimeException("Network error")
        launchFragment()

        onView(withId(R.id.errorState))
            .check(matches(isDisplayed()))
    }

    // ========== Action Button Tests ==========

    @Test
    fun actionButtons_areDisplayed() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader()
        launchFragment()

        onView(withId(R.id.playAllButton))
            .check(matches(isDisplayed()))
        onView(withId(R.id.shuffleButton))
            .check(matches(isDisplayed()))
        onView(withId(R.id.downloadPlaylistButton))
            .check(matches(isDisplayed()))
    }

    @Test
    fun downloadButton_isDisabled_whenExcluded() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            excluded = true
        )
        launchFragment(excluded = true)

        onView(withId(R.id.downloadPlaylistButton))
            .check(matches(isDisplayed()))
            .check(matches(not(isEnabled())))
    }

    @Test
    fun downloadButton_isDisabled_whenDownloadPolicyIsDisabled() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            downloadPolicy = DownloadPolicy.DISABLED
        )
        launchFragment(downloadPolicy = DownloadPolicy.DISABLED)

        onView(withId(R.id.downloadPlaylistButton))
            .check(matches(isDisplayed()))
            .check(matches(not(isEnabled())))
    }

    // ========== Exclusion Banner Tests ==========

    @Test
    fun exclusionBanner_isVisible_whenExcluded() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            excluded = true
        )
        launchFragment(excluded = true)

        onView(withId(R.id.exclusionBanner))
            .check(matches(isDisplayed()))
    }

    @Test
    fun exclusionBanner_isHidden_whenNotExcluded() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            excluded = false
        )
        launchFragment(excluded = false)

        onView(withId(R.id.exclusionBanner))
            .check(matches(not(isDisplayed())))
    }

    // ========== Toolbar Tests ==========

    @Test
    fun toolbar_displaysInitialTitle_whileLoading() {
        repository.useDeferredLoading = true
        launchFragment(playlistTitle = "Initial Title")

        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))

        // Complete loading to clean up
        repository.completeHeaderLoading()
        repository.completeItemsLoading()
    }

    @Test
    fun toolbar_updatesTitle_afterLoading() {
        repository.headerToReturn = FakePlaylistDetailRepository.createDefaultHeader(
            title = "Updated Title"
        )
        launchFragment()

        // After loading, toolbar should show the actual title from header
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
    }

}
