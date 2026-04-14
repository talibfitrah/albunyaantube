package com.albunyaan.tube.data.shorts

data class ShortsItem(
    val id: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int
) {
    /**
     * Canonical YouTube Shorts URL for sharing.
     *
     * The [id] originates from the backend and flows directly into share text
     * via [androidx.core.app.ShareCompat]. A malformed id containing
     * whitespace or control characters would corrupt the share payload or
     * redirect to unintended URLs. We defensively validate the id against
     * YouTube's video-id format (alnum, underscore, hyphen — YouTube's own
     * ids are 11 chars; we allow 6-32 to tolerate platform variation) and
     * fall back to the YouTube root if the id doesn't conform.
     */
    val canonicalShareUrl: String
        get() = if (VIDEO_ID_RX.matches(id)) "https://www.youtube.com/shorts/$id"
        else "https://www.youtube.com"

    private companion object {
        private val VIDEO_ID_RX = Regex("^[A-Za-z0-9_-]{6,32}$")
    }
}

data class ShortsPage(val items: List<ShortsItem>, val nextCursor: String?)
