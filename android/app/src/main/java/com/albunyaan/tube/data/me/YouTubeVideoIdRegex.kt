package com.albunyaan.tube.data.me

internal object YouTubeVideoIdRegex {
    /**
     * Matches the 11-char YouTube video id in these common URL shapes:
     * watch query URLs, youtu.be short URLs, shorts URLs, embed URLs, the
     * older watch path form, and music.youtube.com watch URLs.
     */
    internal val VIDEO_ID_REGEX =
        Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/watch/)([A-Za-z0-9_-]{11})""")
}
