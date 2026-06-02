package com.albunyaan.tube.ui.me

import android.os.Looper
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.AwaitingImports
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B14: Tests the section-row visibility logic for [AwaitingImportsAdapter].
 *
 * Mirrors [MeFavoritesAdapterTest] — Robolectric so DiffUtil's Handler.post
 * can be drained synchronously via shadowOf(Looper.getMainLooper()).idle().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AwaitingImportsAdapterTest {

    @Test
    fun empty_awaiting_yields_zero_items() {
        val adapter = AwaitingImportsAdapter()
        adapter.submit(AwaitingImports(emptyList(), emptyList(), emptyList()))
        idleMain()
        assertEquals(0, adapter.sectionAdapter.itemCount)
    }

    @Test
    fun single_channel_shows_header_plus_one_item() {
        val adapter = AwaitingImportsAdapter()
        adapter.submit(AwaitingImports(listOf(channel("ch1")), emptyList(), emptyList()))
        idleMain()
        // 1 header + 1 channel row
        assertEquals(2, adapter.sectionAdapter.itemCount)
    }

    @Test
    fun mixed_items_show_header_plus_all_rows() {
        val adapter = AwaitingImportsAdapter()
        adapter.submit(
            AwaitingImports(
                channels = listOf(channel("ch1"), channel("ch2")),
                playlists = listOf(playlist("pl1")),
                videos = listOf(video("v1"), video("v2"), video("v3")),
            )
        )
        idleMain()
        // 1 header + 2 channels + 1 playlist + 3 videos = 7
        assertEquals(7, adapter.sectionAdapter.itemCount)
    }

    @Test
    fun going_from_non_empty_to_empty_collapses_section() {
        val adapter = AwaitingImportsAdapter()
        adapter.submit(AwaitingImports(listOf(channel("ch1")), emptyList(), emptyList()))
        idleMain()
        assertEquals(2, adapter.sectionAdapter.itemCount)

        adapter.submit(AwaitingImports(emptyList(), emptyList(), emptyList()))
        idleMain()
        assertEquals(0, adapter.sectionAdapter.itemCount)
    }

    @Test
    fun header_view_type_is_AWAITING_HEADER_VIEW_TYPE() {
        val adapter = AwaitingImportsAdapter()
        adapter.submit(AwaitingImports(listOf(channel("ch1")), emptyList(), emptyList()))
        idleMain()
        assertEquals(
            AwaitingImportsAdapter.AWAITING_HEADER_VIEW_TYPE,
            adapter.sectionAdapter.getItemViewType(0)
        )
    }

    @Test
    fun item_view_type_is_AWAITING_ITEM_VIEW_TYPE() {
        val adapter = AwaitingImportsAdapter()
        adapter.submit(AwaitingImports(listOf(channel("ch1")), emptyList(), emptyList()))
        idleMain()
        assertEquals(
            AwaitingImportsAdapter.AWAITING_ITEM_VIEW_TYPE,
            adapter.sectionAdapter.getItemViewType(1)
        )
    }

    @Test
    fun view_type_constants_do_not_collide_with_other_adapters() {
        // Guard: ensure the 601-602 range is still free from the other adapters.
        val forbidden = setOf(
            MeChipsAdapter.ROW_VIEW_TYPE,           // 101
            MeFavoritesAdapter.FAVORITES_ROW_VIEW_TYPE,   // 401
            MeFavoritesAdapter.FAVORITES_SEE_ALL_VIEW_TYPE, // 402
            MeFavoritesAdapter.FAVORITES_TILE_VIEW_TYPE,  // 403
            MeWeekSectionAdapter.WEEK_HEADER_VIEW_TYPE,   // 501
            MeWeekSectionAdapter.WEEK_SHORTS_VIEW_TYPE,   // 502
            MeWeekSectionAdapter.WEEK_VIDEO_VIEW_TYPE,    // 503
        )
        assert(AwaitingImportsAdapter.AWAITING_HEADER_VIEW_TYPE !in forbidden)
        assert(AwaitingImportsAdapter.AWAITING_ITEM_VIEW_TYPE !in forbidden)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun channel(id: String) = SubscribedChannel(
        channelId = id,
        channelUrl = "https://youtube.com/channel/$id",
        name = "Channel $id",
        avatarUrl = null,
        subscribedAt = 0L,
    )

    private fun playlist(id: String) = SavedPlaylist(
        playlistId = id,
        playlistUrl = "https://youtube.com/playlist?list=$id",
        name = "Playlist $id",
        thumbnailUrl = null,
        uploaderName = null,
        savedAt = 0L,
    )

    private fun video(id: String) = FavoriteVideo(
        videoId = id,
        title = "Video $id",
        channelName = "ch-$id",
        thumbnailUrl = null,
        durationSeconds = 0,
    )
}
