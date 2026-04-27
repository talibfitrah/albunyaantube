package com.albunyaan.tube.data.me

/**
 * Fetches the latest items for a single subscribed channel.
 *
 * Abstracted so tests can supply a fake without talking to YouTube.
 *
 * v3 (ANDROID-PERSONAL-02 / ATOM refresh): result type extended to carry
 * conditional-GET metadata (ETag, Last-Modified) and a NotModified branch
 * for HTTP 304 responses. The default ATOM implementation is
 * [AtomChannelFeedFetcher]; the legacy NewPipe-scraping path remains as
 * [NewPipeChannelFeedFetcher] (rollback path; not bound by default).
 */
interface ChannelFeedFetcher {

    /**
     * Return the latest items for [channelUrl] together with conditional-GET
     * metadata from the response.
     *
     * The fetcher is allowed to (and should) send `If-None-Match` /
     * `If-Modified-Since` based on the [priorEtag] / [priorLastModified] the
     * caller supplies. When the server replies 304 Not Modified the fetcher
     * returns [FetchResult.NotModified]; otherwise it parses and returns
     * [FetchResult.Items].
     *
     * Items with a null [ChannelFeedItem.uploadedAt] may be returned; the
     * caller decides whether to keep them.
     *
     * @throws Exception on network / extraction failure (including 429 / 5xx).
     *   Caller is expected to catch and record the failure for the channel
     *   without aborting other channels' fetches.
     */
    suspend fun fetchLatest(
        channelUrl: String,
        priorEtag: String? = null,
        priorLastModified: String? = null,
    ): FetchResult

    sealed class FetchResult {
        /**
         * Server returned 304 Not Modified. The caller should treat this as
         * a successful refresh with no new items — bump lastSuccessfulFetchAt
         * but leave the cache rows alone.
         */
        data class NotModified(
            val etag: String?,
            val lastModified: String?,
        ) : FetchResult()

        /**
         * Server returned 200 with a feed body. [items] is whatever the
         * fetcher parsed (possibly empty). [etag] / [lastModified] are the
         * cache validators to persist for the next refresh tick.
         */
        data class Items(
            val items: List<ChannelFeedItem>,
            val etag: String?,
            val lastModified: String?,
        ) : FetchResult()
    }

    data class ChannelFeedItem(
        val videoId: String,
        val title: String,
        val thumbnailUrl: String?,
        /** Always null when the source is ATOM (no duration in the feed). */
        val durationSeconds: Long?,
        /** Always null when the source is ATOM (no view count in the feed). */
        val viewCount: Long?,
        val uploadedAt: Long?,
        val isShort: Boolean,
    )
}
