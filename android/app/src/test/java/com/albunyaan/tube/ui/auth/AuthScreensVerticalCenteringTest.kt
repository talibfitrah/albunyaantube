package com.albunyaan.tube.ui.auth

import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Owner report: the sign-in and registration screens hugged the top of the screen with a large
 * empty band below. When the content fits it must sit vertically centred; when it does not (keyboard
 * up, landscape, large font) it must start at the top and scroll, never be pushed above the scroll
 * origin where it cannot be reached.
 *
 * Measures the laid-out views (union of every visible leaf), so it holds however the centring is
 * expressed — container gravity, layout_gravity, or a packed chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
// Real text measurement, so text-heavy content has its true height.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthScreensVerticalCenteringTest {

    private val screens = mapOf(
        "sign_in" to R.layout.fragment_sign_in,
        "email_verification" to R.layout.fragment_email_verification,
        "profile_bootstrap" to R.layout.fragment_profile_bootstrap,
        "age_ineligible" to R.layout.fragment_age_ineligible,
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phoneContentIsVerticallyCentred() = assertAllCentred()

    @Test
    @Config(qualifiers = "sw600dp-w600dp-h960dp-xhdpi")
    fun tabletContentIsVerticallyCentred() = assertAllCentred()

    @Test
    @Config(qualifiers = "sw800dp-w800dp-h1280dp-xhdpi")
    fun largeTabletContentIsVerticallyCentred() = assertAllCentred()

    /** Keyboard up / landscape: a viewport shorter than the content must still scroll from the top. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun overflowingContentStartsAtTopAndScrolls() {
        for ((name, layout) in screens - "age_ineligible") { // age_ineligible is not a scroller
            val root = layOut(layout, heightDp = 300) as ScrollView
            val content = contentBounds(root)
            val child = root.getChildAt(0)
            assertTrue("$name: content pushed above scroll origin (top=${content.top}px)", content.top >= 0)
            assertTrue("$name: content not top-anchored (top=${content.top}px)", content.top <= dp(48))
            assertTrue("$name: does not scroll (child ${child.height}px in ${root.height}px)", child.height > root.height)
        }
    }

    private fun assertAllCentred() {
        val offCentre = screens.mapNotNull { (name, layout) ->
            val root = layOut(layout)
            val content = contentBounds(root)
            val topGap = content.top
            val bottomGap = root.height - content.bottom
            // 16dp: absorbs sign-in's -26dp "or" overlap (LinearLayout counts it at full height),
            // far below the 200dp+ empty band of a top-anchored screen.
            "$name (top gap ${topGap}px, bottom gap ${bottomGap}px)".takeIf { abs(topGap - bottomGap) > dp(16) }
        }
        assertTrue(
            "not vertically centred @sw${context.resources.configuration.smallestScreenWidthDp}dp: $offCentre",
            offCentre.isEmpty(),
        )
    }

    private val context by lazy {
        // Material components need the app theme; the bare application context has none.
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Albunyaan)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun layOut(layout: Int, heightDp: Int = context.resources.configuration.screenHeightDp): ViewGroup {
        val root = LayoutInflater.from(context).inflate(layout, null, false) as ViewGroup
        val w = dp(context.resources.configuration.screenWidthDp)
        val h = dp(heightDp)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
        return root
    }

    /** Union of every visible, non-empty leaf view, in [root] coordinates. */
    private fun contentBounds(root: ViewGroup): Rect {
        val out = Rect()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is ViewGroup && v.childCount > 0) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            } else if (v.width > 0 && v.height > 0) {
                val r = Rect(0, 0, v.width, v.height)
                root.offsetDescendantRectToMyCoords(v, r)
                out.union(r)
            }
        }
        walk(root)
        return out
    }
}
