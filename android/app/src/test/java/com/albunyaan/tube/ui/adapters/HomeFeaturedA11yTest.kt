package com.albunyaan.tube.ui.adapters

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HomeFeaturedA11yTest {

    private fun res(): Resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    @Test fun `known age is the last field`() {
        assertEquals(
            "Video: Tafsir, Duration: 4:05, 1K views, 3 days ago",
            HomeFeaturedAdapter.videoDescription(res(), "Tafsir", "4:05", "1K views", "3 days ago"),
        )
    }

    @Test fun `unknown age leaves no empty trailing field`() {
        assertEquals(
            "Video: Tafsir, Duration: 4:05, 1K views",
            HomeFeaturedAdapter.videoDescription(res(), "Tafsir", "4:05", "1K views", null),
        )
    }

    @Test
    @Config(qualifiers = "ar")
    fun `Arabic separator is dropped too`() {
        assertEquals(
            "فيديو: تفسير، المدة: 4:05، 1K",
            HomeFeaturedAdapter.videoDescription(res(), "تفسير", "4:05", "1K", null),
        )
    }

    @Test fun `unknown age uses its own template, not a cut-down one`() {
        assertEquals(
            res().getString(com.albunyaan.tube.R.string.a11y_video_item_no_age, "Tafsir", "4:05", "1K views"),
            HomeFeaturedAdapter.videoDescription(res(), "Tafsir", "4:05", "1K views", null),
        )
    }

    @Test fun `Home uses the shared age ladder, so 400 days is 1 year ago`() {
        assertEquals(
            "1K views • 1 year ago • Tafsir",
            HomeFeaturedAdapter.metaLine(res(), "1K views", 400, "Tafsir"),
        )
    }

    @Test fun `Home meta with unknown age and blank category has no stray separator`() {
        assertEquals("1K views", HomeFeaturedAdapter.metaLine(res(), "1K views", null, " "))
    }
}
