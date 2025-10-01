package com.albunyaan.tube.data.extractor

data class VideoTrack(
    val url: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val bitrate: Int?,
    val qualityLabel: String?,
    val fps: Int?
)

data class AudioTrack(
    val url: String,
    val mimeType: String?,
    val bitrate: Int?,
    val codec: String?
)

data class ResolvedStreams(
    val streamId: String,
    val videoTracks: List<VideoTrack>,
    val audioTracks: List<AudioTrack>,
    val durationSeconds: Int?
)

data class PlaybackSelection(
    val streamId: String,
    val video: VideoTrack?,
    val audio: AudioTrack,
    val resolved: ResolvedStreams
)
