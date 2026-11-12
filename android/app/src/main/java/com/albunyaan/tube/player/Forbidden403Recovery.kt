package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.ResolvedStreams

internal enum class Forbidden403Step { REFRESH, PLAY_MUXED, EXHAUSTED }

/**
 * Next recovery step for a mid-play HTTP 403.
 *
 * The first 403 on a stream refreshes (fresh signed URLs fix a genuine expiry). A repeat 403
 * on an adaptive source then plays the muxed track already in hand instead of refreshing again:
 * the 2026-11-11 probe showed a fresh extraction returns the identical stream set, so a second
 * refresh cannot help. "Repeat" counts per stream, across resumes: the field failure plays
 * ~40 s, 403s, refreshes, plays, and 403s again. While muxed, or with no muxed track, the
 * refresh ladder holds.
 *
 * @param forbidden403sThisStream 403s on the current stream including this one; survives
 *   resume, resets on a new stream or a manual retry.
 * @param refreshesThisEpisode the player's refresh count, reset when playback resumes.
 * @param playingMuxed the prepared source is progressive.
 * @param hasMuxed the current stream set has a muxed (video+audio) track.
 */
internal fun next403Step(
    forbidden403sThisStream: Int,
    refreshesThisEpisode: Int,
    maxRefreshes: Int,
    playingMuxed: Boolean,
    hasMuxed: Boolean
): Forbidden403Step = when {
    forbidden403sThisStream <= 1 && refreshesThisEpisode < maxRefreshes -> Forbidden403Step.REFRESH
    !playingMuxed && hasMuxed -> Forbidden403Step.PLAY_MUXED
    refreshesThisEpisode < maxRefreshes -> Forbidden403Step.REFRESH
    else -> Forbidden403Step.EXHAUSTED
}

/**
 * Whether [next403Step]'s PLAY_MUXED can help: a VOD stream set with a muxed track. Live is
 * excluded because [DashSourceBuilder.decide] ignores forceProgressive for live, so the
 * "muxed" rebuild would be the same live source and never advance the refresh count.
 */
internal fun hasMuxedFallback(resolved: ResolvedStreams?): Boolean =
    resolved != null && !resolved.isLive && resolved.videoTracks.any { !it.isVideoOnly }
