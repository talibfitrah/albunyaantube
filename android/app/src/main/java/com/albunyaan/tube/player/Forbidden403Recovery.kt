package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.delay

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
 * @param forbidden403sThisStream counted 403s ([counts403]) on the current stream, at least 1;
 *   survives resume, resets on a new stream or a manual retry.
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

/**
 * Whether a 403 counts toward [next403Step]'s per-stream repeat: only an unclassified 403 that
 * fresh URLs should have fixed. A URL expiry is fixed by a refresh, a rate limit or geo-block
 * says nothing about the stream's quality, and a 403 inside the seek-transient window is a
 * range-rejected seek on a healthy stream; counting any of them would pin 360p on a long
 * lecture, after two seeks, or after a throttle. An uncounted 403 still passes at least 1 to
 * [next403Step].
 */
internal fun counts403(failureType: StreamRequestTelemetry.FailureType, inSeekWindow: Boolean): Boolean =
    failureType == StreamRequestTelemetry.FailureType.UNKNOWN_403 && !inSeekWindow

/**
 * Run a delayed 403 recovery only if [videoId] is still the current video ([currentVideoId]). The state that
 * chose the recovery belongs to the video that 403'd; after a switch it would refresh, or pin to 360p, the next one.
 */
internal suspend fun afterBackoff(delayMs: Long, videoId: String?, currentVideoId: () -> String?, recover: () -> Unit) {
    delay(delayMs)
    if (currentVideoId() == videoId) recover()
}

/**
 * How long after the last counted 403 another one is a repeat. The field repeat comes ~50 s after the resume.
 * ponytail: fixed window; widen it if the repeat is ever seen later than this.
 */
private const val REPEAT_403_WINDOW_MS = 5 * 60_000L

/**
 * The per-stream counted-403 total ([counts403]) after one more at [nowMs]. A 403 more than [REPEAT_403_WINDOW_MS]
 * after the last counted one starts over at 1: the refresh in between worked (an IP-bound URL dies on a network
 * switch), so it must not pin a long lecture to 360p.
 */
internal fun next403Count(count: Int, lastAtMs: Long, nowMs: Long): Int =
    if (count > 0 && nowMs - lastAtMs <= REPEAT_403_WINDOW_MS) count + 1 else 1
