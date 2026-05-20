package com.albunyaan.tube.dto;

/**
 * A single search result item returned by the YouTube moderator search.
 *
 * @param youtubeId   YouTube content ID (channel ID, playlist ID, or video ID)
 * @param name        Title / channel name
 * @param url         Canonical YouTube URL
 * @param thumbnailUrl URL of the best available thumbnail (nullable)
 * @param secondary   Channel name (for VIDEO/PLAYLIST) or subscriber count string (for CHANNEL)
 * @param alreadyKnown true if this ID is already in the registry (any status)
 * @param knownStatus  APPROVED | PENDING | REJECTED | REQUEST_CHANGES, or null if not in registry
 */
public record SearchHit(
        String youtubeId,
        String name,
        String url,
        String thumbnailUrl,
        String secondary,
        boolean alreadyKnown,
        String knownStatus,
        String contentType
) {}
