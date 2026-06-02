package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Pure-static mapper from registry model objects to {@link ContentItemDto}.
 *
 * <p>Single source of truth for the model→DTO conversion used by both the public content
 * API ({@code PublicContentService}) and the import-resolve endpoint
 * ({@code ImportController}). Any field addition or null-guard change must happen here
 * and is automatically inherited by both call sites.
 *
 * <p>All methods are stateless and safe to call from any thread.
 */
public final class ContentItemMapper {

    private ContentItemMapper() {}

    /** Map an approved {@link Channel} to a {@link ContentItemDto}. */
    public static ContentItemDto fromChannel(Channel channel) {
        return ContentItemDto.channel(
                channel.getYoutubeId(),
                channel.getName(),
                channel.getCategory() != null ? channel.getCategory().getName() : null,
                channel.getSubscribers(),
                channel.getDescription(),
                channel.getThumbnailUrl(),
                channel.getVideoCount(),
                channel.getKeywords()
        );
    }

    /** Map an approved {@link Playlist} to a {@link ContentItemDto}. */
    public static ContentItemDto fromPlaylist(Playlist playlist) {
        return ContentItemDto.playlist(
                playlist.getYoutubeId(),
                playlist.getTitle(),
                playlist.getCategory() != null ? playlist.getCategory().getName() : null,
                playlist.getItemCount(),
                playlist.getDescription(),
                playlist.getThumbnailUrl(),
                playlist.getKeywords()
        );
    }

    /**
     * Map an approved {@link Video} to a {@link ContentItemDto}.
     *
     * <p>Category name is intentionally {@code null} — populated by client-side lookup to
     * avoid a per-video Firestore query in stream operations (same trade-off as the public
     * content API).
     */
    public static ContentItemDto fromVideo(Video video) {
        int durationSeconds = video.getDurationSeconds() != null ? video.getDurationSeconds() : 0;
        LocalDateTime uploadedAt = video.getUploadedAt() != null
                ? video.getUploadedAt().toDate().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();
        int uploadedDaysAgo = (int) ChronoUnit.DAYS.between(uploadedAt, LocalDateTime.now());

        // Category name will be null — populated by client-side lookup to avoid a
        // Firestore query in stream operations.
        return ContentItemDto.video(
                video.getYoutubeId(),
                video.getTitle(),
                null,
                durationSeconds,
                uploadedDaysAgo,
                video.getDescription(),
                video.getThumbnailUrl(),
                video.getViewCount(),
                video.getChannelTitle(),
                video.getKeywords()
        );
    }
}
