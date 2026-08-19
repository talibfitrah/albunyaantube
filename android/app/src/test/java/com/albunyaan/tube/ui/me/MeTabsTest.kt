package com.albunyaan.tube.ui.me

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.R
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.AwaitingImports
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long list of items awaiting review sat between the user's channel chips and their actual
 * feed, so anyone who imported a few hundred videos had to scroll past all of them to reach
 * their own content. The two are now separate tabs.
 *
 * The tab wiring itself is fragment behaviour and is only really provable on a device; what the
 * JVM can pin is that every layout variant offers the same tab host under the same id, and that
 * the count driving the tab label sees all three kinds of awaiting item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MeTabsTest {

    private fun inflateMe(): View {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Albunyaan
        )
        return LayoutInflater.from(context).inflate(R.layout.fragment_me, null, false)
    }

    private fun assertTabHostPresent(root: View) {
        // Same ids across variants, or the fragment's findViewById wiring silently no-ops on
        // whichever form factor was not updated.
        assertNotNull("meTabs missing", root.findViewById<TabLayout>(R.id.meTabs))
        assertNotNull("meRecycler missing", root.findViewById<RecyclerView>(R.id.meRecycler))
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phoneLayoutHostsTheTabs() {
        assertTabHostPresent(inflateMe())
    }

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h1280dp-xhdpi")
    fun tabletLayoutHostsTheTabs() {
        assertTabHostPresent(inflateMe())
    }

    @Test
    @Config(qualifiers = "sw720dp-w1280dp-h800dp-xhdpi")
    fun largeTabletLayoutHostsTheTabs() {
        assertTabHostPresent(inflateMe())
    }

    @Test
    @Config(qualifiers = "ar-rXA-ldrtl-w411dp-h891dp-xhdpi")
    fun rtlLayoutHostsTheTabs() {
        assertTabHostPresent(inflateMe())
    }

    @Test
    fun theTabCountCoversEveryKindOfAwaitingItem() {
        val awaiting = AwaitingImports(
            channels = listOf(channel("c1"), channel("c2")),
            playlists = listOf(playlist("p1")),
            videos = listOf(video("v1"), video("v2"), video("v3")),
        )

        // Counting only videos would under-report a mixed import and mislabel the tab.
        assertEquals(6, awaiting.total)
    }

    @Test
    fun nothingAwaitingCountsAsZero() {
        assertEquals(0, AwaitingImports(emptyList(), emptyList(), emptyList()).total)
    }

    @Test
    fun theFeedEmptyStateStaysOutOfTheWayOnThePendingTab() {
        // Someone whose only content is awaiting review has an empty feed by definition. Showing
        // the feed's "nothing here" over the Pending tab would hide the very list they opened.
        assertEquals(false, shouldShowFeedEmptyState(feedIsEmpty = true, selectedTab = TAB_PENDING))
    }

    @Test
    fun theFeedEmptyStateStillShowsOnTheContentTab() {
        assertEquals(true, shouldShowFeedEmptyState(feedIsEmpty = true, selectedTab = TAB_CONTENT))
    }

    @Test
    fun aNonEmptyFeedNeverShowsTheEmptyState() {
        assertEquals(false, shouldShowFeedEmptyState(feedIsEmpty = false, selectedTab = TAB_CONTENT))
        assertEquals(false, shouldShowFeedEmptyState(feedIsEmpty = false, selectedTab = TAB_PENDING))
    }

    @Test
    fun theSpanLookupReadsTheAdapterThatIsAttached() {
        // The grid's span lookup used to ask concatAdapter directly. Once the Pending tab swaps a
        // different adapter into the same RecyclerView, concatAdapter holds far fewer items than
        // the list being laid out, and ConcatAdapter.getItemViewType throws for any position past
        // its end — a crash on tablet/TV as soon as more than a couple of items are pending.
        val spanCount = 3
        val awaitingHeader = 601
        val awaitingItem = 602

        // Awaiting rows are full width; only feed video tiles take a single cell.
        assertEquals(spanCount, spanFor(awaitingHeader, spanCount))
        assertEquals(spanCount, spanFor(awaitingItem, spanCount))
        assertEquals(1, spanFor(MeWeekSectionAdapter.WEEK_VIDEO_VIEW_TYPE, spanCount))
    }

    private fun channel(id: String) = SubscribedChannel(
        channelId = id,
        channelUrl = "https://youtube.com/channel/$id",
        name = id,
        avatarUrl = null,
        subscribedAt = 0L,
        user_id = "uid",
    )

    private fun playlist(id: String) = SavedPlaylist(
        playlistId = id,
        playlistUrl = "https://youtube.com/playlist?list=$id",
        name = id,
        thumbnailUrl = null,
        uploaderName = null,
        savedAt = 0L,
        user_id = "uid",
    )

    private fun video(id: String) = FavoriteVideo(
        videoId = id,
        title = id,
        channelName = "channel",
        thumbnailUrl = null,
        durationSeconds = 0,
        addedAt = 0L,
        user_id = "uid",
    )
}
