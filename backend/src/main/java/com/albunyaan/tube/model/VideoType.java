package com.albunyaan.tube.model;

/**
 * Distinguishes live YouTube videos from standard videos
 * within the existing VIDEO content type. Null on existing docs is treated as STANDARD.
 */
public enum VideoType {
    STANDARD,
    LIVE
}
