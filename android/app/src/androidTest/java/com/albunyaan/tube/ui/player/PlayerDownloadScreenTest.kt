package com.albunyaan.tube.ui.player

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.datasource.cache.SimpleCache
import androidx.navigation.Navigation
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.di.TestDownloadModule
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.ui.download.RecordingNavController
import com.albunyaan.tube.util.eventually
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Offline downloads (1.0.3): the player opened with [PlayerFragment.ARG_DOWNLOAD_ID] plays that
 * download (the arg wins over videoId) and shows nothing a local file cannot back: no Download
 * button that does nothing, no "No views yet", no quality or cast control. The file is absent so
 * the test needs no media; the screen state is the same.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlayerDownloadScreenTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    // Each test builds its own Hilt component, and SimpleCache allows one instance per folder per process.
    @Inject
    lateinit var simpleCache: SimpleCache

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        scenario?.close()
        TestDownloadModule.fakeRepository.clear()
        simpleCache.release()
    }

    @Test
    fun aDownloadOpensInLocalPlaybackMode() {
        val missing = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "absent.mp4")
        TestDownloadModule.fakeRepository.emit(
            listOf(
                DownloadEntry(
                    request = DownloadRequest("download-7", "Saved lecture", "lecture_7", audioOnly = false),
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    filePath = missing.absolutePath,
                )
            )
        )

        scenario = launchFragmentInHiltContainer<PlayerFragment>(
            fragmentArgs = Bundle().apply {
                putString(PlayerFragment.ARG_DOWNLOAD_ID, "download-7")
                // A real, different video id: the download arg must still win.
                putString("videoId", "another_lecture")
            },
            themeResId = R.style.Theme_Albunyaan,
        )

        eventually {
            scenario!!.onActivity { activity ->
                fun visible(id: Int) = activity.findViewById<View>(id).isVisible
                assertEquals("Saved lecture", activity.findViewById<TextView>(R.id.videoTitle).text.toString())
                assertFalse("the Download button does nothing here", visible(R.id.downloadButton))
                assertFalse("a saved item has no view count", visible(R.id.videoStats))
                assertFalse(visible(R.id.qualityButton))
                assertFalse(visible(R.id.castButton))
            }
        }
    }

    /** The file is gone: say so, and offer nothing that cannot bring it back. */
    @Test
    fun aMissingDownloadShowsUnavailableWithNoDeadActions() {
        openAbsentDownload()

        eventually {
            scenario!!.onActivity { activity ->
                fun visible(id: Int) = activity.findViewById<View>(id).isVisible
                assertTrue(visible(R.id.playerErrorOverlay))
                assertFalse("Retry cannot bring a deleted file back", visible(R.id.playerRetryButton))
                assertFalse("Refresh cannot bring a deleted file back", visible(R.id.playerRefreshStreamButton))
            }
        }
    }

    /** No channel and no view count for a saved item: the description sits right under the title. */
    @Test
    fun noBlankBandUnderTheTitle() {
        openAbsentDownload()

        eventually {
            scenario!!.onActivity { activity ->
                val title = activity.findViewById<View>(R.id.videoTitle)
                val card = activity.findViewById<View>(R.id.descriptionCard)
                val titleAt = IntArray(2).also(title::getLocationOnScreen)
                val cardAt = IntArray(2).also(card::getLocationOnScreen)
                val margin = (card.layoutParams as ViewGroup.MarginLayoutParams).topMargin
                assertEquals("blank band between title and description", margin, cardAt[1] - (titleAt[1] + title.height))
            }
        }
    }

    /** A streamed video that is also saved: its Download button replaces this player with the file. */
    @Test
    fun thePlayersDownloadButtonOpensTheSavedFileInstead() {
        TestDownloadModule.fakeRepository.emit(
            listOf(
                DownloadEntry(
                    request = DownloadRequest("download-6", "Saved lecture", "lecture_6", audioOnly = false),
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                )
            )
        )
        scenario = launchFragmentInHiltContainer<PlayerFragment>(
            fragmentArgs = Bundle().apply { putString("videoId", "lecture_6") },
            themeResId = R.style.Theme_Albunyaan,
        )
        lateinit var nav: RecordingNavController
        scenario!!.onActivity { activity ->
            nav = RecordingNavController(activity)
            val fragment = activity.supportFragmentManager.fragments.first { it is PlayerFragment }
            Navigation.setViewNavController(fragment.requireView(), nav)
        }

        eventually {
            scenario!!.onActivity { activity ->
                activity.findViewById<View>(R.id.downloadButton).performClick()
                assertEquals(R.id.action_global_playerFragment, nav.destination)
            }
        }
        assertEquals("download-6", nav.args?.getString(PlayerFragment.ARG_DOWNLOAD_ID))
        assertEquals("the streaming player is replaced, not stacked", R.id.playerFragment, nav.navOptions?.popUpToId)
        assertTrue(nav.navOptions?.isPopUpToInclusive() == true)
    }

    private fun openAbsentDownload() {
        val missing = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "absent.mp4")
        TestDownloadModule.fakeRepository.emit(
            listOf(
                DownloadEntry(
                    request = DownloadRequest("download-8", "Saved lecture", "lecture_8", audioOnly = false),
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    filePath = missing.absolutePath,
                )
            )
        )
        scenario = launchFragmentInHiltContainer<PlayerFragment>(
            fragmentArgs = Bundle().apply { putString(PlayerFragment.ARG_DOWNLOAD_ID, "download-8") },
            themeResId = R.style.Theme_Albunyaan,
        )
    }
}
