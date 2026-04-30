package com.albunyaan.tube.share

import android.net.Uri
import com.albunyaan.tube.BuildConfig

object ShareLinks {

    fun video(
        videoId: String,
        @Suppress("UNUSED_PARAMETER")
        title: String? = null,
        @Suppress("UNUSED_PARAMETER")
        imageUrl: String? = null,
        @Suppress("UNUSED_PARAMETER")
        description: String? = null
    ): String {
        return publicShareUrl(
            type = "watch",
            id = videoId,
            fallbackDeepLink = "albunyaantube://video/${Uri.encode(videoId)}"
        )
    }

    fun channel(
        channelId: String,
        @Suppress("UNUSED_PARAMETER")
        title: String?,
        @Suppress("UNUSED_PARAMETER")
        imageUrl: String?,
        @Suppress("UNUSED_PARAMETER")
        description: String?
    ): String {
        return publicShareUrl(
            type = "channel",
            id = channelId,
            fallbackDeepLink = "albunyaantube://channel/${Uri.encode(channelId)}"
        )
    }

    fun playlist(
        playlistId: String,
        @Suppress("UNUSED_PARAMETER")
        title: String?,
        @Suppress("UNUSED_PARAMETER")
        imageUrl: String?,
        @Suppress("UNUSED_PARAMETER")
        description: String?
    ): String {
        return publicShareUrl(
            type = "playlist",
            id = playlistId,
            fallbackDeepLink = "albunyaantube://playlist/${Uri.encode(playlistId)}"
        )
    }

    private fun publicShareUrl(
        type: String,
        id: String,
        fallbackDeepLink: String
    ): String {
        val shareBaseUrl = BuildConfig.SHARE_BASE_URL.trimEnd('/')
        if (shareBaseUrl.isBlank()) {
            return fallbackDeepLink
        }

        return Uri.parse(shareBaseUrl)
            .buildUpon()
            .appendPath("api")
            .appendPath(type)
            .appendPath(id)
            .build()
            .toString()
    }
}
