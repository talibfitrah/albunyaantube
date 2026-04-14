package com.albunyaan.tube.data.me

/**
 * Fetches the latest items for a single subscribed channel.
 *
 * Abstracted so tests can supply a fake without talking to YouTube.
 */
interface ChannelFeedFetcher {

    /**
     * Return the latest page of items for [channelUrl]. Ordering is not guaranteed
     * — the caller sorts by [ChannelFeedItem.uploadedAt].
     *
     * Items with a null [ChannelFeedItem.uploadedAt] may be returned; the caller
     * decides whether to keep them.
     *
     * @throws Exception on network / extraction failure. Caller is expected to
     *   catch and record the failure for the channel without aborting other
     *   channels' fetches.
     */
    suspend fun fetchLatest(channelUrl: String): List<ChannelFeedItem>

    data class ChannelFeedItem(
        val videoId: String,
        val title: String,
        val thumbnailUrl: String?,
        val durationSeconds: Long?,
        val viewCount: Long?,
        val uploadedAt: Long?,
        val isShort: Boolean,
    )
}
