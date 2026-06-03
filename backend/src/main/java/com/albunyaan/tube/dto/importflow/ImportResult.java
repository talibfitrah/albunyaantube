package com.albunyaan.tube.dto.importflow;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.YouTubeContentType;

public record ImportResult(
        String youtubeId,
        YouTubeContentType type,
        ImportDisposition disposition,
        ContentItemDto content) {}
