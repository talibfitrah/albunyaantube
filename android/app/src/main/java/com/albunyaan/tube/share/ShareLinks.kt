package com.albunyaan.tube.share

import android.net.Uri
import com.albunyaan.tube.BuildConfig

object ShareLinks {

    fun video(
        videoId: String,
        title: String? = null,
        imageUrl: String? = null,
        description: String? = null
    ): String {
        return publicShareUrl(
            type = "watch",
            id = videoId,
            fallbackDeepLink = "albunyaantube://video/${Uri.encode(videoId)}",
            title = title,
            imageUrl = imageUrl,
            description = description
        )
    }

    fun channel(
        channelId: String,
        title: String?,
        imageUrl: String?,
        description: String?
    ): String {
        return publicShareUrl(
            type = "channel",
            id = channelId,
            fallbackDeepLink = "albunyaantube://channel/${Uri.encode(channelId)}",
            title = title,
            imageUrl = imageUrl,
            description = description
        )
    }

    fun playlist(
        playlistId: String,
        title: String?,
        imageUrl: String?,
        description: String?
    ): String {
        return publicShareUrl(
            type = "playlist",
            id = playlistId,
            fallbackDeepLink = "albunyaantube://playlist/${Uri.encode(playlistId)}",
            title = title,
            imageUrl = imageUrl,
            description = description
        )
    }

    private fun publicShareUrl(
        type: String,
        id: String,
        fallbackDeepLink: String,
        title: String? = null,
        imageUrl: String? = null,
        description: String? = null
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
            .apply {
                title?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    appendQueryParameter("title", it.take(MAX_TITLE_CHARS))
                }
                imageUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let {
                    appendQueryParameter("image", it)
                }
                description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    appendQueryParameter("description", it.take(MAX_DESCRIPTION_CHARS))
                }
            }
            .build()
            .toString()
    }

    private const val MAX_TITLE_CHARS = 160
    private const val MAX_DESCRIPTION_CHARS = 220
}
