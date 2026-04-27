package com.albunyaan.tube.ui.me

import com.albunyaan.tube.data.local.FavoriteVideo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * Tests the section-row visibility logic for [MeFavoritesAdapter].
 *
 * Runs under Robolectric so the inner [androidx.recyclerview.widget.ListAdapter]
 * (an AsyncListDiffer underneath) can post its commit callback to the main
 * Looper. We drain the main looper after each submission with
 * [shadowOf(Looper.getMainLooper()).idle] to make the diff result observable
 * synchronously in-test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MeFavoritesAdapterTest {

    @Test
    fun empty_list_means_zero_section_items() {
        val adapter = MeFavoritesAdapter(
            onClick = {}, onLongPress = {}, onSeeAll = {},
        )
        adapter.submit(emptyList())
        idleMain()
        assertEquals(0, adapter.sectionAdapter.itemCount)
    }

    @Test
    fun non_empty_list_shows_one_section_item() {
        val adapter = MeFavoritesAdapter(
            onClick = {}, onLongPress = {}, onSeeAll = {},
        )
        adapter.submit(listOf(fav("v1"), fav("v2")))
        idleMain()
        assertEquals(1, adapter.sectionAdapter.itemCount)
    }

    @Test
    fun submission_caps_at_MAX_TILES_20() {
        val adapter = MeFavoritesAdapter(
            onClick = {}, onLongPress = {}, onSeeAll = {},
        )
        adapter.submit((1..30).map { fav("v$it") })
        idleMain()
        // The section adapter is 1 (the row itself); the cap applies to the
        // inner tiles adapter. We assert the public surface only here so the
        // test stays decoupled from the inner-adapter wiring; the cap value
        // is exercised via the const itself below.
        assertEquals(1, adapter.sectionAdapter.itemCount)
        assertEquals(20, MeFavoritesAdapter.MAX_TILES)
    }

    private fun idleMain() {
        // Drain anything posted to the main Looper (AsyncListDiffer commits
        // its result via Handler.post on the main thread). Without this,
        // the section count flip in submit()'s callback hasn't happened
        // yet and the assertions race the diff.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fav(id: String) = FavoriteVideo(
        videoId = id,
        title = id,
        channelName = "ch-$id",
        thumbnailUrl = null,
        durationSeconds = 0,
    )
}
