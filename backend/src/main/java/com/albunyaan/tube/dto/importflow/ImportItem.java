package com.albunyaan.tube.dto.importflow;

import com.albunyaan.tube.dto.YouTubeContentType;

public record ImportItem(
        YouTubeContentType type,
        String youtubeId,
        String title,
        String thumbnailUrl,
        String channelId) {}
