package com.albunyaan.tube.dto.registry;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.VideoType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** One row in the bulk submit request, returned from a prior preview. */
public record SubmitRow(
        int rowIndex,
        @NotBlank String originalUrl,
        @NotNull YouTubeContentType detectedType,
        /** Null when {@code detectedType} != VIDEO; STANDARD or LIVE otherwise. */
        VideoType videoType,
        @NotNull PreviewMetadata metadata,
        @Size(min = 1) List<@NotBlank String> categoryIds
) {}
