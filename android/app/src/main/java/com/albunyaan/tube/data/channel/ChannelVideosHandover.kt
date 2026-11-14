package com.albunyaan.tube.data.channel

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A channel's Videos tab as loaded so far: its rows, the cursor of its next page and which source that cursor belongs
 * to (the channel tab, or the uploads-playlist fallback). The tab writes it as it loads; the player reads it so a
 * tapped video's Up next continues the tab's own list from that cursor instead of rescanning the channel (as iOS
 * does). Keyed by channel; a tab reload replaces its entry. Empty after process death, when the player scans instead.
 */
@Singleton
class ChannelVideosHandover @Inject constructor() {

    data class Snapshot(val items: List<ChannelVideo>, val nextPage: Page?, val viaTab: Boolean)

    private val snapshots = object : LinkedHashMap<String, Snapshot>(MAX_CHANNELS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) = size > MAX_CHANNELS
    }

    @Synchronized
    fun put(channelId: String, snapshot: Snapshot) {
        snapshots[channelId] = snapshot
    }

    /** Forgets the channel: its tab is reloading. */
    @Synchronized
    fun remove(channelId: String) {
        snapshots.remove(channelId)
    }

    @Synchronized
    fun get(channelId: String): Snapshot? = snapshots[channelId]

    private companion object {
        /** The channels a user browsed most recently; older ones fall back to the scan. */
        const val MAX_CHANNELS = 8
    }
}
