package com.albunyaan.tube.ui.settings

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Field bug: the About screen showed a plain green square where the app logo belongs.
 * Inflates the real layout at phone and tablet widths and checks the slot is the brand logo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AboutLogoLayoutTest {

    private fun inflateLogo(): ImageView {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Albunyaan
        )
        val root = LayoutInflater.from(context).inflate(R.layout.fragment_about, null, false)
        val logo = root.findViewById<View>(R.id.aboutLogo)
        assertNotNull("About screen has no logo view", logo)
        assertTrue("logo slot is a ${logo.javaClass.simpleName}, not an ImageView", logo is ImageView)
        return logo as ImageView
    }

    private fun assertBrandLogo(logo: ImageView) {
        assertEquals(
            R.drawable.albunyaantube_logo,
            shadowOf(logo.drawable).createdFromResId
        )
        assertEquals(
            logo.context.getString(R.string.about_app_logo_description),
            logo.contentDescription
        )
        val size = logo.resources.getDimensionPixelSize(R.dimen.channel_avatar_size)
        assertEquals(size, logo.layoutParams.width)
        assertEquals(size, logo.layoutParams.height)
    }

    @Test
    fun phoneShowsBrandLogo() = assertBrandLogo(inflateLogo())

    /** The 800 px master in density-less drawable/ decoded as mdpi: 3200 px (~41 MB) at xxxhdpi. */
    @Test
    @Config(qualifiers = "xxxhdpi")
    fun logoBitmapIsNotUpscaledOnDecode() {
        val bitmap = (inflateLogo().drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertTrue("logo decoded at ${bitmap.width} px", bitmap.width <= 800)
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun tabletShowsBrandLogo() = assertBrandLogo(inflateLogo())

    @Test
    @Config(qualifiers = "sw720dp")
    fun largeTabletShowsBrandLogo() = assertBrandLogo(inflateLogo())
}
