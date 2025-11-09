package com.albunyaan.tube.ui.download

import android.content.Context
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadFileMetadata
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadStatus
import java.util.concurrent.TimeUnit
import android.text.format.Formatter
import android.text.format.DateUtils
import com.albunyaan.tube.download.DownloadStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.UiController
import android.view.View
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class DownloadsFragmentTest {

    private lateinit var fakeRepository: FakeDownloadRepository
    private lateinit var storage: DownloadStorage
    private var scenario: FragmentScenario<DownloadsFragment>? = null

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fakeRepository = FakeDownloadRepository()
        storage = DownloadStorage(context, 5L * 1024 * 1024)
        ServiceLocator.setDownloadRepositoryForTesting(fakeRepository)
        ServiceLocator.setDownloadStorageForTesting(storage)
        ServiceLocator.setExtractorMetricsForTesting(NoopMetrics)
        scenario = launchFragmentInContainer(themeResId = R.style.Theme_Albunyaan)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        ServiceLocator.setDownloadRepositoryForTesting(null)
        ServiceLocator.setDownloadStorageForTesting(null)
        ServiceLocator.setExtractorMetricsForTesting(null)
        fakeRepository.actions.clear()
    }

    @Test
    fun pauseResumeCancelFlow_updatesRepository() {
        val entry = DownloadEntry(
            request = DownloadRequest("download-1", "Sample Video", "M7lc1UVf-VE", audioOnly = true),
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
    fun itemContentDescription_includesStatusAndProgress() {
        val entry = DownloadEntry(
            request = DownloadRequest("download-2", "Another Video", "M7lc1UVf-VE", audioOnly = false),
            status = DownloadStatus.RUNNING,
            progress = 65
        )
        fakeRepository.emit(listOf(entry))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val context: Context = ApplicationProvider.getApplicationContext()
        val expected = context.getString(
            R.string.download_item_content_description,
            entry.request.title,
            context.getString(R.string.download_status_running),
            entry.progress
        )

        onView(withId(R.id.downloadsRecyclerView))
            .check(matches(hasDescendant(withContentDescription(expected))))
    }

    @Test
    fun completedDownload_showsMetadataDetails() {
        val metadata = DownloadFileMetadata(
            sizeBytes = 5_000_000,
            completedAtMillis = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10),
            mimeType = "audio/mp4"
        )
        val entry = DownloadEntry(
            request = DownloadRequest("download-3", "Metadata Video", "ysz5S6PUM-U", audioOnly = true),
            status = DownloadStatus.COMPLETED,
            progress = 100,
            filePath = "/tmp/download-3.m4a",
            metadata = metadata
        )
        fakeRepository.emit(listOf(entry))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val context: Context = ApplicationProvider.getApplicationContext()
        val size = Formatter.formatShortFileSize(context, metadata.sizeBytes)
        val relative = DateUtils.getRelativeTimeSpanString(
            metadata.completedAtMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
        val expected = context.getString(R.string.download_details_format, size, relative)

        onView(withId(R.id.downloadsRecyclerView))
            .check(matches(hasDescendant(withText(expected))))
    }

    private class FakeDownloadRepository : DownloadRepository {
        private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
        val actions = mutableListOf<String>()

        override val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

        override fun enqueue(request: DownloadRequest) {
            actions += "enqueue:${request.id}"
        }

        override fun pause(requestId: String) {
            actions += "pause:$requestId"
        }

        override fun resume(requestId: String) {
            actions += "resume:$requestId"
        }

        override fun cancel(requestId: String) {
            actions += "cancel:$requestId"
        }

        fun emit(entries: List<DownloadEntry>) {
            _downloads.value = entries
        }
    }

    private object NoopMetrics : ExtractorMetricsReporter {
        override fun onCacheHit(type: ContentType, hitCount: Int) {}
        override fun onCacheMiss(type: ContentType, missCount: Int) {}
        override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) {}
        override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) {}
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

