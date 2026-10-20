package com.albunyaan.tube.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.util.ImageLoading.loadThumbnail
import com.albunyaan.tube.util.ImageLoading.loadThumbnailUrl
import com.albunyaan.tube.util.ImageLoading.loadYouTubeThumbnail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner issue 3: a playlist with no usable picture shows the playlist glyph, not the plain grey "loading" box.
 * Both cases below return before Coil, so they are synchronous. Inflating through the app theme also proves the
 * glyph's `?attr/colorPrimary` tint resolves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ImageLoadingPlaceholderTest {

    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Albunyaan)

    private fun assertPlaylistGlyph(url: String?) {
        val view = ImageView(context)
        view.loadThumbnailUrl(url, error = R.drawable.playlist_thumbnail_placeholder)
        val drawable = view.drawable
        assertTrue("expected the playlist glyph, got $drawable", drawable is LayerDrawable)
        assertEquals(2, (drawable as LayerDrawable).numberOfLayers)
    }

    @Test
    fun `a playlist with no url shows the playlist glyph at once`() = assertPlaylistGlyph(null)

    @Test
    fun `YouTube's no_thumbnail url shows the playlist glyph without loading it`() =
        assertPlaylistGlyph("https://i.ytimg.com/img/no_thumbnail.jpg")

    @Test
    fun `the playlist glyph keeps a 16 by 9 intrinsic size`() {
        val drawable = ContextCompat.getDrawable(context, R.drawable.playlist_thumbnail_placeholder)!!
        assertTrue(drawable.intrinsicWidth > 0)
        assertEquals(drawable.intrinsicWidth * 9, drawable.intrinsicHeight * 16)
    }

    /**
     * Fix round 1 (A): Coil 2.7.0 restarts a view's last request when the view is re-attached. A RecyclerView row that
     * loaded playlist A, was recycled and rebound to a playlist with no picture must keep the glyph after it re-attaches,
     * not get A's picture back. The fake loader answers every request with a red square and counts them; no network.
     */
    private var loads = 0
    private val red = ColorDrawable(Color.RED)

    private fun recycledRow(
        firstBind: ImageView.() -> Unit = { loadThumbnailUrl("https://i.ytimg.com/vi/lesson00001/hqdefault.jpg") },
        rebindWithNoPicture: ImageView.() -> Unit,
    ): ImageView {
        Coil.setImageLoader(
            ImageLoader.Builder(context).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        loads++
                        return SuccessResult(red, chain.request, DataSource.MEMORY_CACHE)
                    }
                })
            }.build()
        )
        val parent = FrameLayout(context)
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(parent)
        val row = ImageView(context).apply { layoutParams = ViewGroup.LayoutParams(160, 90) }
        parent.addView(row)
        row.firstBind()
        assertEquals("positive control: the first item's picture loaded", 1, loads)
        parent.removeView(row) // scrolled off
        row.rebindWithNoPicture()
        parent.addView(row) // scrolled back on
        return row
    }

    @After
    fun resetCoil() = Coil.reset()

    @Test
    fun `a recycled row rebound with no url keeps the glyph when it re-attaches`() {
        val row = recycledRow { loadThumbnailUrl(null, error = R.drawable.playlist_thumbnail_placeholder) }

        assertEquals("the old request must not restart", 1, loads)
        assertTrue("expected the playlist glyph, got ${row.drawable}", row.drawable is LayerDrawable)
    }

    @Test
    fun `a recycled row rebound with an empty fallback chain keeps the glyph when it re-attaches`() {
        val row = recycledRow {
            loadYouTubeThumbnail(primaryUrl = null, videoId = null, error = R.drawable.playlist_thumbnail_placeholder)
        }

        assertEquals("the old request must not restart", 1, loads)
        assertTrue("expected the playlist glyph, got ${row.drawable}", row.drawable is LayerDrawable)
    }

    @Test
    fun `a recycled channel row rebound with no avatar does not show the previous avatar`() {
        val row = recycledRow(
            firstBind = { loadThumbnail(ContentItem.Channel("UCchannel0001", "A", "c", 0, thumbnailUrl = "https://yt3.googleusercontent.com/a")) },
        ) { loadThumbnail(ContentItem.Channel("UCchannel0002", "B", "c", 0)) }

        assertEquals("the old request must not restart", 1, loads)
        assertTrue("expected the avatar placeholder, got ${row.drawable}", row.drawable !== red)
    }
}
