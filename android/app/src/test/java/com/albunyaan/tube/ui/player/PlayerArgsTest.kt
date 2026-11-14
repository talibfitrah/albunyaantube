package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.model.ContentItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0.4 round 3: Home, Search, Featured and the Videos tab all open the player through
 * [PlayerFragment.argsFor]. They passed the category ("General") as the channel, which the
 * player showed and a favourite saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerArgsTest {

    @Test fun `a list video opens the player with its channel, not its category`() {
        val video = ContentItem.Video(
            id = "lec1", title = "Lecture", category = "General", durationSeconds = 1800,
            uploadedDaysAgo = null, description = "About", viewCount = 12L, channelName = "Lecturer",
        )

        val args = PlayerFragment.argsFor(video)

        assertEquals("Lecturer", args.getString("channelName"))
        assertEquals("lec1", args.getString("videoId"))
        assertEquals(1800, args.getInt("durationSeconds"))
        assertEquals(12L, args.getLong("viewCount"))
    }

    /** Spec §9.2(b): a live-category item opens in live-channel mode, and the gate checks its channel. */
    @Test fun `a live category item carries its channel into the player`() {
        val video = ContentItem.Video(
            id = "eaOtcUNHDB8", title = "Zad", category = "Live", durationSeconds = 0,
            uploadedDaysAgo = null, description = "", channelName = "Zad TV", liveChannelId = "UCOll3M-P7oKs5cSrQ9ytt6g",
        )

        val args = PlayerFragment.argsFor(video)

        assertEquals("UCOll3M-P7oKs5cSrQ9ytt6g", args.getString("liveChannelId"))
        assertEquals("UCOll3M-P7oKs5cSrQ9ytt6g", args.getString("channelId"))
    }

    /** Owner ruling: an exact live link opens the player with its flag, so the player asks /live/links while it plays. */
    @Test fun `an exact live link carries its flag into the player`() {
        val video = ContentItem.Video(
            id = "Rs7St51oDDc", title = "Live", category = "Live", durationSeconds = 0,
            uploadedDaysAgo = null, description = "", liveLink = true,
        )

        assertEquals(true, PlayerFragment.argsFor(video).getBoolean("liveLink"))
        assertEquals(false, PlayerFragment.argsFor(video.copy(liveLink = false)).getBoolean("liveLink"))
    }

    /** Cubic 4: the wrap_content Up next list shows a growing window of the queue; the whole queue stays for advance. */
    @Test fun `up next grows a window at a time and pages the queue only once every row shows`() {
        val w = PlayerFragment.UP_NEXT_WINDOW
        assertEquals(2 * w, PlayerFragment.grownUpNextWindow(shown = w, bound = w, total = 100))
        assertEquals("still binding the last growth", 2 * w, PlayerFragment.grownUpNextWindow(2 * w, bound = w, total = 100))
        assertEquals(null, PlayerFragment.grownUpNextWindow(shown = 4 * w, bound = 100, total = 100))
        assertEquals(null, PlayerFragment.grownUpNextWindow(shown = w, bound = 5, total = 5))
    }

    /** Cold review: an advance, Previous or a row tap shifts the queue and keeps the grown window; a new queue resets it. */
    @Test fun `the up next window survives a shift of the queue and resets on a new one`() {
        fun q(vararg ids: String) = ids.map { UpNextItem(it, it, "", 0, it) }
        val grown = 3 * PlayerFragment.UP_NEXT_WINDOW
        val before = q("a", "b", "c", "d")
        assertEquals("advance", grown, PlayerFragment.upNextWindowFor(grown, before, q("b", "c", "d")))
        assertEquals("previous", grown, PlayerFragment.upNextWindowFor(grown, before, q("z", "a", "b", "c", "d")))
        assertEquals("row tap", grown, PlayerFragment.upNextWindowFor(grown, before, q("d")))
        assertEquals("a page appended", grown, PlayerFragment.upNextWindowFor(grown, before, q("a", "b", "c", "d", "e")))
        assertEquals("another video's queue", PlayerFragment.UP_NEXT_WINDOW, PlayerFragment.upNextWindowFor(grown, before, q("x", "y")))
        assertEquals("first queue", PlayerFragment.UP_NEXT_WINDOW, PlayerFragment.upNextWindowFor(grown, emptyList(), q("x")))
    }
}
