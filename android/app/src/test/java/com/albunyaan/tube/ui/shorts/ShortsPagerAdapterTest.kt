package com.albunyaan.tube.ui.shorts

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.R
import com.albunyaan.tube.data.shorts.ShortsItem
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShortsPagerAdapterTest {

    // A like change (tapping Like, or Room re-emitting after a metadata rebind) used to re-run the full bind with
    // the language and subtitle buttons forced off; their count flows don't emit again, so the buttons stayed hidden.
    @Test
    fun `a like change keeps the language and subtitle buttons`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val liked = MutableStateFlow(false)
        val adapter = ShortsPagerAdapter(
            owner,
            ShortsPagerAdapter.Callbacks(
                onLike = {}, onShare = {}, onDownload = {}, onChannelTap = {}, onTapVideo = {},
                onAudioTrackTap = {}, onSubtitleTap = {},
                onLikedFlow = { liked },
                onAudioLanguageCountFlow = { MutableStateFlow(2) },
                onSubtitleCountFlow = { MutableStateFlow(1) },
            ),
        )
        adapter.submitList(listOf(ShortsItem("s1", "Title", "c1", "Channel", null, null, 60)))
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Albunyaan)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = ViewGroup.LayoutParams(1080, 1920)
            this.adapter = adapter
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1080, 1920)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val page = list.findViewHolderForAdapterPosition(0)!!.itemView
        assertEquals("before like", View.VISIBLE, page.findViewById<View>(R.id.shortAudioTrackBtn).visibility)

        liked.value = true
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.shortAudioTrackBtn).visibility)
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.shortSubtitleBtn).visibility)
    }
}
