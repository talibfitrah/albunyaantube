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
}
