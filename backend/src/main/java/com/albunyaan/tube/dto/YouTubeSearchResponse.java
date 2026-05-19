package com.albunyaan.tube.dto;

import java.util.List;

/**
 * Response envelope for the moderator YouTube search.
 *
 * @param items         Ordered list of search hits
 * @param nextPageToken Opaque token for fetching the next page (null when exhausted)
 */
public record YouTubeSearchResponse(List<SearchHit> items, String nextPageToken) {}
