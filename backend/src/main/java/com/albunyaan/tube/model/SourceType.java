package com.albunyaan.tube.model;

/**
 * Enum representing the source of a video.
 * Tracks where the video was added from.
 */
public enum SourceType {
    /**
     * Video was added directly as a standalone item
     */
    STANDALONE,

    /**
     * Video was imported from a YouTube channel
     */
    CHANNEL,

    /**
     * Video was imported from a YouTube playlist
     */
    PLAYLIST,

    /**
     * Source is unknown or not tracked
     */
    UNKNOWN
}

