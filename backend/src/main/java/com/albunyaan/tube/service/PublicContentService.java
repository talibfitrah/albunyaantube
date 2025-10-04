package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for public API endpoints (Android app).
 * Serves only approved/included content without authentication.
 */
@Service
public class PublicContentService {

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;

    public PublicContentService(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            CategoryRepository categoryRepository
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * Get paginated content for Android app.
     *
     * @param type Content type (HOME, CHANNELS, PLAYLISTS, VIDEOS)
     * @param cursor Base64-encoded cursor for pagination
     * @param limit Page size
     * @param category Category filter
     * @param length Video length filter
     * @param date Published date filter
     * @param sort Sort option
     * @return Paginated content
     */
    public CursorPageDto<ContentItemDto> getContent(
            String type, String cursor, int limit,
            String category, String length, String date, String sort
    ) {
        List<ContentItemDto> items = new ArrayList<>();

        switch (type.toUpperCase()) {
            case "HOME":
                items = getMixedContent(limit, category);
                break;
            case "CHANNELS":
                items = getChannels(limit, category);
                break;
            case "PLAYLISTS":
                items = getPlaylists(limit, category);
                break;
            case "VIDEOS":
                items = getVideos(limit, category, length, date, sort);
                break;
            default:
                items = getMixedContent(limit, category);
        }

        // For now, return null cursor (simple pagination will be added later)
        String nextCursor = items.size() >= limit ? encodeCursor(limit) : null;

        return new CursorPageDto<>(items, nextCursor);
    }

    private List<ContentItemDto> getMixedContent(int limit, String category) {
        List<ContentItemDto> mixed = new ArrayList<>();

        // Get mix of channels, playlists, and videos (roughly 1:2:3 ratio)
        int channelCount = limit / 6;
        int playlistCount = (limit / 6) * 2;
        int videoCount = limit - channelCount - playlistCount;

        mixed.addAll(getChannels(channelCount, category));
        mixed.addAll(getPlaylists(playlistCount, category));
        mixed.addAll(getVideos(videoCount, category, null, null, null));

        return mixed;
    }

    private List<ContentItemDto> getChannels(int limit, String category) {
        List<Channel> channels;

        if (category != null && !category.isBlank()) {
            channels = channelRepository.findByCategoryOrderBySubscribersDesc(category);
        } else {
            channels = channelRepository.findAllByOrderBySubscribersDesc();
        }

        return channels.stream()
                .filter(this::isApproved)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> getPlaylists(int limit, String category) {
        List<Playlist> playlists;

        if (category != null && !category.isBlank()) {
            playlists = playlistRepository.findByCategoryOrderByItemCountDesc(category);
        } else {
            playlists = playlistRepository.findAllByOrderByItemCountDesc();
        }

        return playlists.stream()
                .filter(this::isApproved)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> getVideos(int limit, String category,
                                           String length, String date, String sort) {
        List<Video> videos;

        if (category != null && !category.isBlank()) {
            videos = videoRepository.findByCategoryOrderByUploadedAtDesc(category);
        } else {
            videos = videoRepository.findAllByOrderByUploadedAtDesc();
        }

        return videos.stream()
                .filter(this::isApproved)
                .filter(v -> matchesLengthFilter(v, length))
                .filter(v -> matchesDateFilter(v, date))
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<CategoryDto> getCategories() {
        return categoryRepository.findAll().stream()
                .map(this::toCategoryDto)
                .collect(Collectors.toList());
    }

    private CategoryDto toCategoryDto(Category category) {
        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getSlug() != null ? category.getSlug() : category.getName().toLowerCase().replace(" ", "-"),
                category.getParentId()
        );
    }

    public Object getChannelDetails(String channelId) {
        return channelRepository.findByYoutubeId(channelId)
                .orElseThrow(() -> new RuntimeException("Channel not found"));
    }

    public Object getPlaylistDetails(String playlistId) {
        return playlistRepository.findByYoutubeId(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));
    }

    public List<ContentItemDto> search(String query, String type, int limit) {
        List<ContentItemDto> results = new ArrayList<>();

        if (type == null || type.equalsIgnoreCase("CHANNELS")) {
            results.addAll(searchChannels(query, limit));
        }
        if (type == null || type.equalsIgnoreCase("PLAYLISTS")) {
            results.addAll(searchPlaylists(query, limit));
        }
        if (type == null || type.equalsIgnoreCase("VIDEOS")) {
            results.addAll(searchVideos(query, limit));
        }

        return results.stream().limit(limit).collect(Collectors.toList());
    }

    private List<ContentItemDto> searchChannels(String query, int limit) {
        return channelRepository.searchByName(query).stream()
                .filter(this::isApproved)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> searchPlaylists(String query, int limit) {
        return playlistRepository.searchByTitle(query).stream()
                .filter(this::isApproved)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> searchVideos(String query, int limit) {
        return videoRepository.searchByTitle(query).stream()
                .filter(this::isApproved)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // Helper methods
    private boolean isApproved(Channel channel) {
        return "APPROVED".equals(channel.getStatus());
    }

    private boolean isApproved(Playlist playlist) {
        return "APPROVED".equals(playlist.getStatus());
    }

    private boolean isApproved(Video video) {
        return "APPROVED".equals(video.getStatus());
    }

    private boolean matchesLengthFilter(Video video, String length) {
        if (length == null || length.isBlank()) return true;

        int duration = video.getDurationSeconds() / 60; // Convert to minutes
        switch (length.toUpperCase()) {
            case "SHORT":
                return duration < 4;
            case "MEDIUM":
                return duration >= 4 && duration <= 20;
            case "LONG":
                return duration > 20;
            default:
                return true;
        }
    }

    private boolean matchesDateFilter(Video video, String date) {
        if (date == null || date.isBlank()) return true;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime uploadedAt = video.getUploadedAt();

        switch (date.toUpperCase()) {
            case "LAST_24_HOURS":
                return ChronoUnit.HOURS.between(uploadedAt, now) <= 24;
            case "LAST_7_DAYS":
                return ChronoUnit.DAYS.between(uploadedAt, now) <= 7;
            case "LAST_30_DAYS":
                return ChronoUnit.DAYS.between(uploadedAt, now) <= 30;
            default:
                return true;
        }
    }

    private ContentItemDto toDto(Channel channel) {
        return ContentItemDto.channel(
                channel.getYoutubeId(),
                channel.getName(),
                channel.getCategory() != null ? channel.getCategory().getName() : null,
                channel.getSubscribers(),
                channel.getDescription(),
                channel.getThumbnailUrl(),
                channel.getVideoCount()
        );
    }

    private ContentItemDto toDto(Playlist playlist) {
        return ContentItemDto.playlist(
                playlist.getYoutubeId(),
                playlist.getTitle(),
                playlist.getCategory() != null ? playlist.getCategory().getName() : null,
                playlist.getItemCount(),
                playlist.getDescription(),
                playlist.getThumbnailUrl()
        );
    }

    private ContentItemDto toDto(Video video) {
        int durationMinutes = video.getDurationSeconds() / 60;
        LocalDateTime uploadedAt = video.getUploadedAt();
        int uploadedDaysAgo = (int) ChronoUnit.DAYS.between(uploadedAt, LocalDateTime.now());

        return ContentItemDto.video(
                video.getYoutubeId(),
                video.getTitle(),
                video.getCategory() != null ? video.getCategory().getName() : null,
                durationMinutes,
                uploadedDaysAgo,
                video.getDescription(),
                video.getThumbnailUrl(),
                video.getViewCount()
        );
    }

    private String encodeCursor(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes());
    }
}
