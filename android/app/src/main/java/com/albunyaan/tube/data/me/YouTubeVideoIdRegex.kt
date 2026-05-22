package com.albunyaan.tube.data.me

internal object YouTubeVideoIdRegex {
    /**
     * Matches the 11-char YouTube video id in any of these URL shapes:
     *   https://www.youtube.com/watch?v=<id>
     *   https://www.youtube.com/watch?list=PL...&v=<id>    (v= after other query params)
     *   https://youtu.be/<id>
     *   https://www.youtube.com/shorts/<id>
     *   https://www.youtube.com/embed/<id>
     *   https://www.youtube.com/watch/<id>                 (older path form)
     *   https://music.youtube.com/watch?v=<id>
     *   //www.youtube.com/watch?v=<id>                     (protocol-relative)
     *
     * Used by [ChannelDeepPaginator]; covered by YouTubeVideoIdRegexTest.
     * An empty/unmatched id is treated by callers as a drop signal — the
     * caller filters those rows to avoid Room primary-key collisions on
     * REPLACE.
     */
    internal val VIDEO_ID_REGEX =
        Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/watch/)([A-Za-z0-9_-]{11})""")
}
