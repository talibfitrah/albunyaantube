package com.albunyaan.tube.ui.download

import android.content.Context
import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.Navigation
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.di.TestDownloadModule
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.ui.player.PlayerFragment
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.UiController
import android.view.View
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class DownloadsFragmentTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private val fakeRepository get() = TestDownloadModule.fakeRepository

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        scenario = launchFragmentInHiltContainer<DownloadsFragment>(
            themeResId = R.style.Theme_Albunyaan
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        fakeRepository.clear()
    }

    @Test
    fun pauseResumeCancelFlow_updatesRepository() {
        val entry = DownloadEntry(
            request = DownloadRequest("download-1", "Sample Video", "test_video_id", audioOnly = true),
            status = DownloadStatus.RUNNING,
            progress = 42
        )
        fakeRepository.emit(listOf(entry))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        onView(withId(R.id.downloadsRecyclerView))
            .perform(
                RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(
                    0,
                    ClickChildViewAction(R.id.downloadPauseResume)
                )
            )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assert(fakeRepository.actions.contains("pause:download-1"))

        fakeRepository.emit(listOf(entry.copy(status = DownloadStatus.PAUSED)))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        onView(withId(R.id.downloadsRecyclerView))
            .perform(
                RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(
                    0,
                    ClickChildViewAction(R.id.downloadPauseResume)
                )
            )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assert(fakeRepository.actions.contains("resume:download-1"))

        onView(withId(R.id.downloadsRecyclerView))
            .perform(
                RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(
                    0,
                    ClickChildViewAction(R.id.downloadCancel)
                )
            )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assert(fakeRepository.actions.contains("cancel:download-1"))
    }

    @Test
    fun emptyState_isDisplayedWhenNoDownloads() {
        // When no downloads, empty state should be visible
        onView(withId(R.id.emptyDownloads))
            .check(matches(isDisplayed()))
    }

    @Test
    fun storageText_isDisplayed() {
        // Storage info should be visible
        onView(withId(R.id.storageText))
            .check(matches(isDisplayed()))
    }

    @Test
    fun open_passesTheDownloadIdToThePlayer() {
        lateinit var nav: RecordingNavController
        scenario!!.onActivity { activity ->
            nav = RecordingNavController(activity)
            val fragment = activity.supportFragmentManager.fragments.first { it is DownloadsFragment }
            Navigation.setViewNavController(fragment.requireView(), nav)
        }
        fakeRepository.emit(
            listOf(
                DownloadEntry(
                    request = DownloadRequest("download-9", "Saved lecture", "lecture_9", audioOnly = false),
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                )
            )
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        onView(withId(R.id.downloadsRecyclerView))
            .perform(
                RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(
                    0,
                    ClickChildViewAction(R.id.downloadOpen)
                )
            )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(R.id.action_global_playerFragment, nav.destination)
        assertEquals("download-9", nav.args?.getString(PlayerFragment.ARG_DOWNLOAD_ID))
    }

    private class ClickChildViewAction(private val viewId: Int) : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "Click child view with id $viewId"
        override fun perform(uiController: UiController, view: View) {
            val target = view.findViewById<View>(viewId)
            target?.performClick()
        }
    }
}

/** Records the navigation a screen asks for, instead of performing it. */
internal class RecordingNavController(context: Context) : NavController(context) {
    var destination: Int? = null
    var args: Bundle? = null
    var navOptions: NavOptions? = null
    override fun navigate(resId: Int, args: Bundle?, navOptions: NavOptions?) {
        destination = resId
        this.args = args
        this.navOptions = navOptions
    }
}
