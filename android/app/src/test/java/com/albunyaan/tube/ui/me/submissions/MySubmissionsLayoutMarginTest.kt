package com.albunyaan.tube.ui.me.submissions

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Field bug: text on My Submissions ran edge to edge with no side margin. The empty state was the
 * worst case — a wrap_content TextView in a FrameLayout with no horizontal inset at all, so a
 * two-line message wrapped against both screen bounds.
 *
 * Measures the laid-out views rather than asserting on XML attributes, so the guard survives a
 * refactor of how the inset is expressed (padding, margin, a parent container) and still fails if
 * the text goes back to touching the bounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w411dp-h891dp-xhdpi")
// Real text measurement — the legacy graphics stub measures every string as zero-width, which
// makes a "is the text inset from the edge" assertion pass vacuously.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MySubmissionsLayoutMarginTest {

    private val screenWidthPx = 411 * 2

    /**
     * The project's standard gutter, spacing_md = 16dp, in px at xhdpi (2x).
     *
     * Must stay above the values this fixed: the row's old paddingEnd was spacing_sm = 8dp = 16px,
     * so a 16px threshold would have passed against the very layout it was written to pin.
     */
    private val minInsetPx = 32

    /** Inflate at [screenWidthPx], run [prepare] on the tree, then measure and lay it out. */
    private fun inflateAndLayout(layoutRes: Int, prepare: (View) -> Unit = {}): View {
        // Material components need the app theme; the bare application context has none.
        val context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            R.style.Theme_Albunyaan
        )
        val view = LayoutInflater.from(context).inflate(layoutRes, null, false)
        prepare(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    @Test
    fun emptyStateTextIsInsetFromBothScreenEdges() {
        val root = inflateAndLayout(R.layout.fragment_my_submissions) {
            it.findViewById<TextView>(R.id.emptyState).visibility = View.VISIBLE
        }
        val empty = root.findViewById<TextView>(R.id.emptyState)

        val left = empty.left
        val right = screenWidthPx - empty.right
        assertTrue("empty state hugs the left edge (left=$left px)", left >= minInsetPx)
        assertTrue("empty state hugs the right edge (right=$right px)", right >= minInsetPx)

        // The measured inset above is satisfied by centering alone whenever the copy is short
        // enough not to fill the row, so a shorter translation would silently disarm it. Pin the
        // gutter itself too: it must hold for every locale, not just the ones that wrap.
        val lp = empty.layoutParams as android.view.ViewGroup.MarginLayoutParams
        assertTrue("no start gutter (${lp.marginStart} px)", lp.marginStart >= minInsetPx)
        assertTrue("no end gutter (${lp.marginEnd} px)", lp.marginEnd >= minInsetPx)
    }

    @Test
    fun submissionRowTextIsInsetFromBothScreenEdges() {
        val row = inflateAndLayout(R.layout.item_my_submission)

        // The thumbnail is the leftmost element; every text view sits to its right.
        val leftMost = row.findViewById<View>(R.id.thumbnail)
        // The title is full-bleed to the row's end constraint — the tightest right edge of the text.
        val title = row.findViewById<TextView>(R.id.title)

        val left = leftMost.left
        val right = screenWidthPx - title.right
        assertTrue("row content hugs the left edge (left=$left px)", left >= minInsetPx)
        assertTrue("row text hugs the right edge (right=$right px)", right >= minInsetPx)
    }
}
