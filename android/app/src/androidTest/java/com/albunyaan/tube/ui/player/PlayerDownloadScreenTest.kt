package com.albunyaan.tube.ui.player

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.datasource.cache.SimpleCache
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
import com.albunyaan.tube.util.eventually
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                putString("videoId", "")
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
}
