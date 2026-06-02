package com.albunyaan.tube.dto.importflow;

import com.albunyaan.tube.dto.YouTubeContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One item in an import-resolve request.
 *
 * <p>F5 — field constraints reject blank/oversized/malformed client input before it
 * can reach the admin approval queue. The youtubeId/channelId charset matches
 * YouTube's id alphabet, which also rejects bidi/zero-width controls in ids; titles
 * are stripped of those controls at persist time (see
 * {@code RegistrySubmissionWriter.sanitizeText}).
 */
public record ImportItem(
        @NotNull YouTubeContentType type,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String youtubeId,
        @Size(max = 300) String title,
        @Size(max = 2048) String thumbnailUrl,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]*") String channelId) {}
