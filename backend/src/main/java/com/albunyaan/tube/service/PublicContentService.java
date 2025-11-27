package com.albunyaan.tube.service;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.dto.CategoryDto;
import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
     * BACKEND-PERF-01: Cached for 1 hour to improve response time
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
    @Cacheable(value = CacheConfig.CACHE_PUBLIC_CONTENT,
               key = "#type + '-' + #cursor + '-' + #limit + '-' + #category + '-' + #length + '-' + #date + '-' + #sort")
    public CursorPageDto<ContentItemDto> getContent(
            String type, String cursor, int limit,
            String category, String length, String date, String sort
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // For content types that support real cursor pagination
        switch (type.toUpperCase()) {
            case "CHANNELS":
                return getChannelsWithCursor(limit, category, cursor);
            case "PLAYLISTS":
                return getPlaylistsWithCursor(limit, category, cursor);
            case "VIDEOS":
                return getVideosWithCursor(limit, category, cursor, length, date, sort);
            case "HOME":
            default:
                // HOME type mixes content types, so cursor doesn't apply cleanly
                // Use legacy approach for mixed content
                List<ContentItemDto> items = getMixedContent(limit, category);
                String nextCursor = items.size() >= limit ? encodeCursor(limit) : null;
                return new CursorPageDto<>(items, nextCursor);
        }
    }

    private List<ContentItemDto> getMixedContent(int limit, String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
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

    private List<ContentItemDto> getChannels(int limit, String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Channel> channels;

        // Use repository methods with limits for better performance
        // This reduces data transfer from Firestore
        if (category != null && !category.isBlank()) {
            channels = channelRepository.findByCategoryOrderBySubscribersDesc(category, limit * 2);
        } else {
            channels = channelRepository.findAllByOrderBySubscribersDesc(limit * 2);
        }

        return channels.stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Get channels with real cursor-based pagination.
     */
    private CursorPageDto<ContentItemDto> getChannelsWithCursor(int limit, String category, String cursor)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        ChannelRepository.PaginatedResult<Channel> result;

        if (category != null && !category.isBlank()) {
            result = channelRepository.findApprovedByCategoryAndSubscribersDescWithCursor(category, limit, cursor);
        } else {
            result = channelRepository.findApprovedBySubscribersDescWithCursor(limit, cursor);
        }

        List<ContentItemDto> items = result.getItems().stream()
                .filter(this::isAvailable)
                .map(this::toDto)
                .collect(Collectors.toList());

        return new CursorPageDto<>(items, result.getNextCursor());
    }

    private List<ContentItemDto> getPlaylists(int limit, String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Playlist> playlists;

        // Use repository methods with limits for better performance
        // This reduces data transfer from Firestore
        if (category != null && !category.isBlank()) {
            playlists = playlistRepository.findByCategoryOrderByItemCountDesc(category, limit * 2);
        } else {
            playlists = playlistRepository.findAllByOrderByItemCountDesc(limit * 2);
        }

        return playlists.stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Get playlists with real cursor-based pagination.
     */
    private CursorPageDto<ContentItemDto> getPlaylistsWithCursor(int limit, String category, String cursor)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        PlaylistRepository.PaginatedResult<Playlist> result;

        if (category != null && !category.isBlank()) {
            result = playlistRepository.findApprovedByCategoryAndItemCountDescWithCursor(category, limit, cursor);
        } else {
            result = playlistRepository.findApprovedByItemCountDescWithCursor(limit, cursor);
        }

        List<ContentItemDto> items = result.getItems().stream()
                .filter(this::isAvailable)
                .map(this::toDto)
                .collect(Collectors.toList());

        return new CursorPageDto<>(items, result.getNextCursor());
    }

    /**
     * Get videos with real cursor-based pagination.
     * Note: When length/date/sort filters are applied, cursor pagination may be less efficient
     * as filtering happens client-side after fetching.
     */
    private CursorPageDto<ContentItemDto> getVideosWithCursor(int limit, String category, String cursor,
                                                              String length, String date, String sort)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // For default case (no filters, newest first), use efficient cursor pagination
        boolean hasFilters = (length != null && !length.isBlank()) ||
                            (date != null && !date.isBlank()) ||
                            (sort != null && !sort.isBlank() && !"NEWEST".equalsIgnoreCase(sort));

        if (!hasFilters) {
            VideoRepository.PaginatedResult<Video> result;

            if (category != null && !category.isBlank()) {
                result = videoRepository.findApprovedByCategoryAndUploadedAtDescWithCursor(category, limit, cursor);
            } else {
                result = videoRepository.findApprovedByUploadedAtDescWithCursor(limit, cursor);
            }

            List<ContentItemDto> items = result.getItems().stream()
                    .filter(this::isAvailable)
                    .map(this::toDto)
                    .collect(Collectors.toList());

            return new CursorPageDto<>(items, result.getNextCursor());
        }

        // Fall back to legacy approach when filters are applied
        List<ContentItemDto> items = getVideos(limit, category, length, date, sort);
        String nextCursor = items.size() >= limit ? encodeCursor(limit) : null;
        return new CursorPageDto<>(items, nextCursor);
    }

    private List<ContentItemDto> getVideos(int limit, String category,
                                           String length, String date, String sort) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Video> videos;

        // Use repository methods with limits for better performance
        // Fetch more than needed to account for filters
        int fetchLimit = limit * 3; // 3x buffer for filters
        if (category != null && !category.isBlank()) {
            videos = videoRepository.findByCategoryOrderByUploadedAtDesc(category, fetchLimit);
        } else {
            videos = videoRepository.findAllByOrderByUploadedAtDesc(fetchLimit);
        }

        // Apply filters
        var stream = videos.stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                .filter(v -> matchesLengthFilter(v, length))
                .filter(v -> matchesDateFilter(v, date));

        // Apply sorting based on sort parameter
        if (sort != null && !sort.isBlank()) {
            switch (sort.toUpperCase()) {
                case "OLDEST":
                    stream = stream.sorted((v1, v2) -> {
                        if (v1.getUploadedAt() == null) return 1;
                        if (v2.getUploadedAt() == null) return -1;
                        return v1.getUploadedAt().compareTo(v2.getUploadedAt());
                    });
                    break;
                case "POPULAR":
                    stream = stream.sorted((v1, v2) -> {
                        Long views1 = v1.getViewCount() != null ? v1.getViewCount() : 0L;
                        Long views2 = v2.getViewCount() != null ? v2.getViewCount() : 0L;
                        return views2.compareTo(views1); // Descending
                    });
                    break;
                case "ALPHABETICAL":
                    stream = stream.sorted((v1, v2) -> {
                        String title1 = v1.getTitle() != null ? v1.getTitle() : "";
                        String title2 = v2.getTitle() != null ? v2.getTitle() : "";
                        return title1.compareToIgnoreCase(title2);
                    });
                    break;
                case "NEWEST":
                default:
                    // Already sorted by uploadedAt descending from repository
                    break;
            }
        }

        return stream
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<CategoryDto> getCategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
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

    public Object getChannelDetails(String channelId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel channel = channelRepository.findByYoutubeId(channelId)
                .orElseThrow(() -> new ResourceNotFoundException("Channel", channelId));

        // Only return approved channels
        if (!"APPROVED".equals(channel.getStatus())) {
            throw new ResourceNotFoundException("Channel", channelId);
        }

        // Exclude archived channels
        if (channel.getValidationStatus() == ValidationStatus.ARCHIVED) {
            throw new ResourceNotFoundException("Channel", channelId);
        }

        return channel;
    }

    public Object getPlaylistDetails(String playlistId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist playlist = playlistRepository.findByYoutubeId(playlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", playlistId));

        // Only return approved playlists
        if (!"APPROVED".equals(playlist.getStatus())) {
            throw new ResourceNotFoundException("Playlist", playlistId);
        }

        // Exclude archived playlists
        if (playlist.getValidationStatus() == ValidationStatus.ARCHIVED) {
            throw new ResourceNotFoundException("Playlist", playlistId);
        }

        return playlist;
    }

    public Video getVideoDetails(String videoId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Video video = videoRepository.findByYoutubeId(videoId)
                .orElseThrow(() -> new ResourceNotFoundException("Video", videoId));

        // Only return approved and available videos
        if (!"APPROVED".equals(video.getStatus())) {
            throw new ResourceNotFoundException("Video", videoId);
        }

        // Exclude unavailable or archived videos
        if (video.getValidationStatus() == ValidationStatus.UNAVAILABLE
                || video.getValidationStatus() == ValidationStatus.ARCHIVED) {
            throw new ResourceNotFoundException("Video", videoId);
        }

        return video;
    }

    public List<ContentItemDto> search(String query, String type, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<ContentItemDto> results = new ArrayList<>();

        if (type == null) {
            // When searching all types, distribute limit evenly across content types
            int limitPerType = limit / 3;

            // Add channels and playlists first
            results.addAll(searchChannels(query, limitPerType));
            results.addAll(searchPlaylists(query, limitPerType));

            // Calculate remaining space and fill with videos in a single call
            // This ensures we never get duplicate videos from calling searchVideos twice
            int remaining = limit - results.size();
            if (remaining > 0) {
                results.addAll(searchVideos(query, remaining));
            }
        } else if (type.equalsIgnoreCase("CHANNELS")) {
            results.addAll(searchChannels(query, limit));
        } else if (type.equalsIgnoreCase("PLAYLISTS")) {
            results.addAll(searchPlaylists(query, limit));
        } else if (type.equalsIgnoreCase("VIDEOS")) {
            results.addAll(searchVideos(query, limit));
        }

        return results;
    }

    private List<ContentItemDto> searchChannels(String query, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return channelRepository.searchByName(query).stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> searchPlaylists(String query, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return playlistRepository.searchByTitle(query).stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> searchVideos(String query, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return videoRepository.searchByTitle(query).stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
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

    /**
     * Check if video is available (not marked as UNAVAILABLE or ARCHIVED by validation)
     */
    private boolean isAvailable(Video video) {
        ValidationStatus validationStatus = video.getValidationStatus();
        return validationStatus != ValidationStatus.UNAVAILABLE
                && validationStatus != ValidationStatus.ARCHIVED;
    }

    /**
     * Check if channel is available (not marked as UNAVAILABLE or ARCHIVED by validation)
     */
    private boolean isAvailable(Channel channel) {
        ValidationStatus validationStatus = channel.getValidationStatus();
        return validationStatus != ValidationStatus.UNAVAILABLE
                && validationStatus != ValidationStatus.ARCHIVED;
    }

    /**
     * Check if playlist is available (not marked as UNAVAILABLE or ARCHIVED by validation)
     */
    private boolean isAvailable(Playlist playlist) {
        ValidationStatus validationStatus = playlist.getValidationStatus();
        return validationStatus != ValidationStatus.UNAVAILABLE
                && validationStatus != ValidationStatus.ARCHIVED;
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
        if (video.getUploadedAt() == null) return true;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime uploadedAt = video.getUploadedAt().toDate().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();

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
        int durationSeconds = video.getDurationSeconds() != null ? video.getDurationSeconds() : 0;
        LocalDateTime uploadedAt = video.getUploadedAt() != null ?
            video.getUploadedAt().toDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() :
            LocalDateTime.now();
        int uploadedDaysAgo = (int) ChronoUnit.DAYS.between(uploadedAt, LocalDateTime.now());

        // Category name will be null for now - will be populated by client-side lookup
        // To avoid Firestore query in stream operations
        String categoryName = null;

        return ContentItemDto.video(
                video.getYoutubeId(),
                video.getTitle(),
                categoryName,
                durationSeconds,
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

