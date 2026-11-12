package com.albunyaan.tube.player

internal enum class Forbidden403Step { REFRESH, PLAY_MUXED, EXHAUSTED }

/**
 * Next recovery step for a mid-play HTTP 403.
 *
 * The first 403 of an episode refreshes (fresh signed URLs fix a genuine expiry). A repeat 403
 * on an adaptive source then plays the muxed track already in hand instead of refreshing again:
 * the 2026-11-11 probe showed a fresh extraction returns the identical stream set, so a second
 * refresh cannot help. While muxed, or with no muxed track, the old refresh ladder holds.
 *
 * @param refreshesThisEpisode the player's refresh count, reset when playback resumes.
 * @param playingMuxed the prepared source is progressive.
 * @param hasMuxed the current stream set has a muxed (video+audio) track.
 */
internal fun next403Step(
    refreshesThisEpisode: Int,
    maxRefreshes: Int,
    playingMuxed: Boolean,
    hasMuxed: Boolean
): Forbidden403Step = when {
    refreshesThisEpisode == 0 && maxRefreshes > 0 -> Forbidden403Step.REFRESH
    !playingMuxed && hasMuxed -> Forbidden403Step.PLAY_MUXED
    refreshesThisEpisode < maxRefreshes -> Forbidden403Step.REFRESH
    else -> Forbidden403Step.EXHAUSTED
}
