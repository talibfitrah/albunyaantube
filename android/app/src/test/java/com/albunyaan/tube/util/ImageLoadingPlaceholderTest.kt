package com.albunyaan.tube.util

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.R
import com.albunyaan.tube.util.ImageLoading.loadThumbnailUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
}
