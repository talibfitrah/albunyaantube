package com.albunyaan.tube.data.youtube

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B7: Fetches all importable YouTube items for a signed-in user.
 *
 * Paginates each list type to completion, maps items to [ImportCandidate],
 * and isolates failures per type so a single 403 (e.g. liked-videos scope not
 * granted) does not suppress the other two lists.
 *
 * Hilt wiring (B15) will supply the [YouTubeImportApi] instance backed by a
 * Retrofit configured for https://www.googleapis.com/youtube/v3/.
 */
@Singleton
class YouTubeImportRemoteSource @Inject constructor(
    private val api: YouTubeImportApi,
) {
    companion object {
        private const val TAG = "YouTubeImportRemote"

        /** Hard cap on pages per list type to guard against infinite-pagination bugs. */
        private const val MAX_PAGES = 40
    }

    /**
     * Fetches subscriptions, playlists, and liked videos for [accessToken].
     *
     * Each list type is fetched independently. A thrown exception for one type
     * adds it to [ImportFetchResult.failedTypes] without aborting the others.
     *
     * @param accessToken Raw OAuth 2.0 access token (without "Bearer " prefix).
     */
    suspend fun fetchAll(accessToken: String): ImportFetchResult {
        val bearer = "Bearer $accessToken"
        val candidates = mutableListOf<ImportCandidate>()
        val failedTypes = mutableSetOf<CandidateType>()

        // ── subscriptions (CHANNEL) ──────────────────────────────────────────
        try {
            candidates += fetchSubscriptions(bearer)
        } catch (e: Exception) {
            Log.w(TAG, "subscriptions fetch failed", e)
            failedTypes += CandidateType.CHANNEL
        }

        // ── playlists (PLAYLIST) ─────────────────────────────────────────────
        try {
            candidates += fetchPlaylists(bearer)
        } catch (e: Exception) {
            Log.w(TAG, "playlists fetch failed", e)
            failedTypes += CandidateType.PLAYLIST
        }

        // ── liked videos (VIDEO) ─────────────────────────────────────────────
        try {
            candidates += fetchLikedVideos(bearer)
        } catch (e: Exception) {
            Log.w(TAG, "likedVideos fetch failed", e)
            failedTypes += CandidateType.VIDEO
        }

        return ImportFetchResult(candidates = candidates, failedTypes = failedTypes)
    }

    // ── per-type paginators ──────────────────────────────────────────────────

    private suspend fun fetchSubscriptions(bearer: String): List<ImportCandidate> {
        val result = mutableListOf<ImportCandidate>()
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        var pages = 0

        do {
            val response = api.subscriptions(bearer = bearer, pageToken = pageToken)
            response.items.mapTo(result) { item ->
                ImportCandidate(
                    type         = CandidateType.CHANNEL,
                    youtubeId    = item.snippet.resourceId.channelId,
                    title        = item.snippet.title,
                    thumbnailUrl = item.snippet.thumbnails.bestUrl(),
                    channelId    = null,
                )
            }
            pageToken = response.nextPageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES && seenTokens.add(pageToken))

        // F12: don't silently truncate — a remaining pageToken at the cap means the
        // account has more subscriptions than we imported.
        if (pageToken != null && pages >= MAX_PAGES) {
            Log.w(TAG, "subscriptions truncated at $MAX_PAGES pages; some items not imported")
        }
        return result
    }

    private suspend fun fetchPlaylists(bearer: String): List<ImportCandidate> {
        val result = mutableListOf<ImportCandidate>()
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        var pages = 0

        do {
            val response = api.playlists(bearer = bearer, pageToken = pageToken)
            response.items.mapTo(result) { item ->
                ImportCandidate(
                    type         = CandidateType.PLAYLIST,
                    youtubeId    = item.id,
                    title        = item.snippet.title,
                    thumbnailUrl = item.snippet.thumbnails.bestUrl(),
                    channelId    = null,
                )
            }
            pageToken = response.nextPageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES && seenTokens.add(pageToken))

        // F12: don't silently truncate — see fetchSubscriptions.
        if (pageToken != null && pages >= MAX_PAGES) {
            Log.w(TAG, "playlists truncated at $MAX_PAGES pages; some items not imported")
        }
        return result
    }

    private suspend fun fetchLikedVideos(bearer: String): List<ImportCandidate> {
        val result = mutableListOf<ImportCandidate>()
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        var pages = 0

        do {
            val response = api.likedVideos(bearer = bearer, pageToken = pageToken)
            response.items.mapTo(result) { item ->
                ImportCandidate(
                    type         = CandidateType.VIDEO,
                    youtubeId    = item.id,
                    title        = item.snippet.title,
                    thumbnailUrl = item.snippet.thumbnails.bestUrl(),
                    channelId    = item.snippet.channelId,
                )
            }
            pageToken = response.nextPageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES && seenTokens.add(pageToken))

        // F12: don't silently truncate — see fetchSubscriptions.
        if (pageToken != null && pages >= MAX_PAGES) {
            Log.w(TAG, "liked videos truncated at $MAX_PAGES pages; some items not imported")
        }
        return result
    }
}
