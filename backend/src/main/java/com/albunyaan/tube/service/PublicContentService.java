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
import java.util.Set;
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

    /**
     * Search for content by query string.
     *
     * Search features:
     * - Bounded queries with configurable over-fetch to prevent quota spikes
     * - YouTube URL/ID parsing: youtube.com/watch?v=XXX, youtu.be/XXX, youtube.com/channel/XXX, etc.
     * - Case-insensitive substring matching (post-query filtering)
     * - Caching of search results (short TTL) to reduce Firestore reads during typing
     *
     * @param query Search query (text, YouTube URL, or YouTube ID)
     * @param type Content type filter (null for all types)
     * @param limit Maximum results to return
     * @return List of matching content items
     */
    @Cacheable(value = CacheConfig.CACHE_PUBLIC_CONTENT_SEARCH,
               key = "#query == null ? '' : #query.trim().toLowerCase(T(java.util.Locale).ROOT) + '-' + #type + '-' + #limit",
               condition = "#query != null && #query.trim().length() >= 2")
    public List<ContentItemDto> search(String query, String type, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        // Normalize query for matching
        String normalizedQuery = query.trim().toLowerCase(java.util.Locale.ROOT);

        // Try to parse as YouTube URL/ID first
        YouTubeIdentifier identifier = parseYouTubeIdentifier(query);

        List<ContentItemDto> results = new ArrayList<>();

        if (identifier != null) {
            // Direct lookup by YouTube ID (most efficient)
            results = searchByYouTubeId(identifier, type, limit);
        } else if (normalizedQuery.length() >= 2) {
            // Text-based search with bounded queries
            results = searchByText(normalizedQuery, type, limit);
        }
        // If query is less than 2 chars and not a YouTube ID, return empty to avoid expensive scans

        return results;
    }

    /**
     * Search by YouTube ID for direct lookups.
     * This is the most efficient path - single document fetch.
     */
    private List<ContentItemDto> searchByYouTubeId(YouTubeIdentifier identifier, String type, int limit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<ContentItemDto> results = new ArrayList<>();

        switch (identifier.type) {
            case CHANNEL:
                if (type == null || type.equalsIgnoreCase("CHANNELS")) {
                    channelRepository.findByYoutubeId(identifier.id)
                            .filter(this::isApproved)
                            .filter(this::isAvailable)
                            .map(this::toDto)
                            .ifPresent(results::add);
                }
                break;
            case PLAYLIST:
                if (type == null || type.equalsIgnoreCase("PLAYLISTS")) {
                    playlistRepository.findByYoutubeId(identifier.id)
                            .filter(this::isApproved)
                            .filter(this::isAvailable)
                            .map(this::toDto)
                            .ifPresent(results::add);
                }
                break;
            case VIDEO:
                if (type == null || type.equalsIgnoreCase("VIDEOS")) {
                    videoRepository.findByYoutubeId(identifier.id)
                            .filter(this::isApproved)
                            .filter(this::isAvailable)
                            .map(this::toDto)
                            .ifPresent(results::add);
                }
                break;
        }

        return results;
    }

    /**
     * Text-based search across content.
     * Uses bounded prefix queries with over-fetch, then filters in memory.
     */
    private List<ContentItemDto> searchByText(String normalizedQuery, String type, int limit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<ContentItemDto> results = new ArrayList<>();

        // Over-fetch factor to account for filtering and improve result quality
        int overFetchLimit = Math.min(limit * 3, 100);

        if (type == null) {
            // When searching all types, distribute limit evenly
            int limitPerType = Math.max(1, limit / 3);
            int overFetchPerType = Math.min(limitPerType * 3, 50);

            results.addAll(searchChannelsByText(normalizedQuery, limitPerType, overFetchPerType));
            results.addAll(searchPlaylistsByText(normalizedQuery, limitPerType, overFetchPerType));

            int remaining = limit - results.size();
            if (remaining > 0) {
                results.addAll(searchVideosByText(normalizedQuery, remaining, Math.min(remaining * 3, 50)));
            }

            // Cap at requested limit in case distributed fetches returned more
            if (results.size() > limit) {
                results = new ArrayList<>(results.subList(0, limit));
            }
        } else if (type.equalsIgnoreCase("CHANNELS")) {
            results.addAll(searchChannelsByText(normalizedQuery, limit, overFetchLimit));
        } else if (type.equalsIgnoreCase("PLAYLISTS")) {
            results.addAll(searchPlaylistsByText(normalizedQuery, limit, overFetchLimit));
        } else if (type.equalsIgnoreCase("VIDEOS")) {
            results.addAll(searchVideosByText(normalizedQuery, limit, overFetchLimit));
        }

        return results;
    }

    private List<ContentItemDto> searchChannelsByText(String normalizedQuery, int limit, int fetchLimit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Use nameLower field for true case-insensitive prefix search
        // normalizedQuery is already lowercase, nameLower is auto-maintained by setName()
        List<Channel> channels = new ArrayList<>(channelRepository.searchByNameLower(normalizedQuery, fetchLimit));

        // Fallback: also query original 'name' field for legacy documents without nameLower
        // This handles documents created before the nameLower field was added
        try {
            List<Channel> legacyResults = channelRepository.searchByName(normalizedQuery, fetchLimit);
            // Merge results, avoiding duplicates by ID
            Set<String> existingIds = channels.stream()
                    .map(Channel::getYoutubeId)
                    .collect(Collectors.toSet());
            for (Channel c : legacyResults) {
                if (!existingIds.contains(c.getYoutubeId())) {
                    channels.add(c);
                }
            }
        } catch (Exception e) {
            // Fallback query failed, proceed with nameLower results only
            // This is expected if the legacy name index doesn't exist
        }

        // Keyword-based search: finds channels where any keyword exactly matches the query.
        // This catches channels whose name doesn't start with the query but have a matching keyword.
        try {
            List<Channel> keywordResults = channelRepository.searchByKeyword(normalizedQuery, fetchLimit);
            Set<String> existingIds = channels.stream()
                    .map(Channel::getYoutubeId)
                    .collect(Collectors.toSet());
            for (Channel c : keywordResults) {
                if (!existingIds.contains(c.getYoutubeId())) {
                    channels.add(c);
                }
            }
        } catch (Exception e) {
            // Keyword query failed (e.g., index not yet created), proceed with name results only
        }

        // Filter and return
        return channels.stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                // Case-insensitive contains matching on name or keywords
                .filter(c -> matchesSearchQuery(c.getName(), c.getKeywords(), normalizedQuery))
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> searchPlaylistsByText(String normalizedQuery, int limit, int fetchLimit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Use titleLower field for true case-insensitive prefix search
        // normalizedQuery is already lowercase, titleLower is auto-maintained by setTitle()
        List<Playlist> playlists = new ArrayList<>(playlistRepository.searchByTitleLower(normalizedQuery, fetchLimit));

        // Fallback: also query original 'title' field for legacy documents without titleLower
        try {
            List<Playlist> legacyResults = playlistRepository.searchByTitle(normalizedQuery, fetchLimit);
            Set<String> existingIds = playlists.stream()
                    .map(Playlist::getYoutubeId)
                    .collect(Collectors.toSet());
            for (Playlist p : legacyResults) {
                if (!existingIds.contains(p.getYoutubeId())) {
                    playlists.add(p);
                }
            }
        } catch (Exception e) {
            // Fallback query failed, proceed with titleLower results only
        }

        // Keyword-based search: finds playlists where any keyword exactly matches the query.
        try {
            List<Playlist> keywordResults = playlistRepository.searchByKeyword(normalizedQuery, fetchLimit);
            Set<String> existingIds = playlists.stream()
                    .map(Playlist::getYoutubeId)
                    .collect(Collectors.toSet());
            for (Playlist p : keywordResults) {
                if (!existingIds.contains(p.getYoutubeId())) {
                    playlists.add(p);
                }
            }
        } catch (Exception e) {
            // Keyword query failed, proceed with title results only
        }

        return playlists.stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                // Case-insensitive contains matching on title or keywords
                .filter(p -> matchesSearchQuery(p.getTitle(), p.getKeywords(), normalizedQuery))
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private List<ContentItemDto> searchVideosByText(String normalizedQuery, int limit, int fetchLimit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Use titleLower field for true case-insensitive prefix search
        // normalizedQuery is already lowercase, titleLower is auto-maintained by setTitle()
        List<Video> videos = new ArrayList<>(videoRepository.searchByTitleLower(normalizedQuery, fetchLimit));

        // Fallback: also query original 'title' field for legacy documents without titleLower
        try {
            List<Video> legacyResults = videoRepository.searchByTitle(normalizedQuery, fetchLimit);
            Set<String> existingIds = videos.stream()
                    .map(Video::getYoutubeId)
                    .collect(Collectors.toSet());
            for (Video v : legacyResults) {
                if (!existingIds.contains(v.getYoutubeId())) {
                    videos.add(v);
                }
            }
        } catch (Exception e) {
            // Fallback query failed, proceed with titleLower results only
        }

        // Keyword-based search: finds videos where any keyword exactly matches the query.
        try {
            List<Video> keywordResults = videoRepository.searchByKeyword(normalizedQuery, fetchLimit);
            Set<String> existingIds = videos.stream()
                    .map(Video::getYoutubeId)
                    .collect(Collectors.toSet());
            for (Video v : keywordResults) {
                if (!existingIds.contains(v.getYoutubeId())) {
                    videos.add(v);
                }
            }
        } catch (Exception e) {
            // Keyword query failed, proceed with title results only
        }

        return videos.stream()
                .filter(this::isApproved)
                .filter(this::isAvailable)
                // Case-insensitive contains matching on title or keywords
                .filter(v -> matchesSearchQuery(v.getTitle(), v.getKeywords(), normalizedQuery))
                .limit(limit)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Check if title or keywords match the search query (case-insensitive contains).
     */
    private boolean matchesSearchQuery(String title, List<String> keywords, String normalizedQuery) {
        // Check title
        if (title != null && title.toLowerCase(java.util.Locale.ROOT).contains(normalizedQuery)) {
            return true;
        }
        // Check keywords
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword != null && keyword.toLowerCase(java.util.Locale.ROOT).contains(normalizedQuery)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ===================== YouTube URL/ID Parsing =====================

    /**
     * Represents a parsed YouTube identifier.
     */
    private static class YouTubeIdentifier {
        enum Type { CHANNEL, PLAYLIST, VIDEO }
        final Type type;
        final String id;

        YouTubeIdentifier(Type type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /**
     * Parse a query string to extract YouTube identifiers.
     * Supports:
     * - Video: youtube.com/watch?v=XXX, youtu.be/XXX, youtube.com/v/XXX
     * - Channel: youtube.com/channel/UCXXX, youtube.com/@handle
     * - Playlist: youtube.com/playlist?list=PLXXX
     * - Direct IDs: 11-char video IDs, UCxxxx channel IDs, PLxxxx playlist IDs
     *
     * @param query The search query to parse
     * @return YouTubeIdentifier if recognized, null otherwise
     */
    private YouTubeIdentifier parseYouTubeIdentifier(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }

        String trimmed = query.trim();

        // Check for URL patterns
        if (trimmed.contains("youtube.com") || trimmed.contains("youtu.be")) {
            return parseYouTubeUrl(trimmed);
        }

        // Check for direct ID patterns
        return parseDirectId(trimmed);
    }

    private YouTubeIdentifier parseYouTubeUrl(String url) {
        try {
            // Video: youtube.com/watch?v=XXX
            if (url.contains("watch?") || url.contains("watch/?")) {
                int vIndex = url.indexOf("v=");
                if (vIndex != -1) {
                    String videoId = extractParamValue(url, vIndex + 2);
                    if (isValidVideoId(videoId)) {
                        return new YouTubeIdentifier(YouTubeIdentifier.Type.VIDEO, videoId);
                    }
                }
            }

            // Video: youtu.be/XXX
            if (url.contains("youtu.be/")) {
                int start = url.indexOf("youtu.be/") + 9;
                String videoId = extractPathSegment(url, start);
                if (isValidVideoId(videoId)) {
                    return new YouTubeIdentifier(YouTubeIdentifier.Type.VIDEO, videoId);
                }
            }

            // Playlist: youtube.com/playlist?list=PLXXX
            if (url.contains("list=")) {
                int listIndex = url.indexOf("list=");
                String playlistId = extractParamValue(url, listIndex + 5);
                if (playlistId.startsWith("PL") && playlistId.length() >= 13 && isAlphanumericWithDashUnderscore(playlistId)) {
                    return new YouTubeIdentifier(YouTubeIdentifier.Type.PLAYLIST, playlistId);
                }
            }

            // Channel: youtube.com/channel/UCXXX
            if (url.contains("/channel/")) {
                int start = url.indexOf("/channel/") + 9;
                String channelId = extractPathSegment(url, start);
                if (channelId.startsWith("UC") && channelId.length() == 24 && isAlphanumericWithDashUnderscore(channelId)) {
                    return new YouTubeIdentifier(YouTubeIdentifier.Type.CHANNEL, channelId);
                }
            }

            // Channel: youtube.com/@handle
            if (url.contains("/@")) {
                // Handle lookups would require additional API call, skip for now
                return null;
            }

        } catch (Exception e) {
            // URL parsing failed, fall through to null
        }

        return null;
    }

    private YouTubeIdentifier parseDirectId(String id) {
        // Channel ID: starts with UC, 24 chars
        if (id.startsWith("UC") && id.length() == 24 && isAlphanumericWithDashUnderscore(id)) {
            return new YouTubeIdentifier(YouTubeIdentifier.Type.CHANNEL, id);
        }

        // Playlist ID: starts with PL, 13+ chars
        if (id.startsWith("PL") && id.length() >= 13 && isAlphanumericWithDashUnderscore(id)) {
            return new YouTubeIdentifier(YouTubeIdentifier.Type.PLAYLIST, id);
        }

        // Video ID: exactly 11 chars, alphanumeric with - and _
        if (id.length() == 11 && isAlphanumericWithDashUnderscore(id)) {
            return new YouTubeIdentifier(YouTubeIdentifier.Type.VIDEO, id);
        }

        return null;
    }

    private String extractParamValue(String url, int startIndex) {
        int end = url.indexOf('&', startIndex);
        // Handle URL fragments (e.g., ?v=XXX#description)
        int fragmentEnd = url.indexOf('#', startIndex);
        if (fragmentEnd != -1 && (end == -1 || fragmentEnd < end)) {
            end = fragmentEnd;
        }
        if (end == -1) end = url.length();
        return url.substring(startIndex, end);
    }

    private String extractPathSegment(String url, int startIndex) {
        int end = url.indexOf('/', startIndex);
        if (end == -1) end = url.indexOf('?', startIndex);
        // Handle URL fragments (e.g., /watch/XXX#section)
        int fragmentEnd = url.indexOf('#', startIndex);
        if (fragmentEnd != -1 && (end == -1 || fragmentEnd < end)) {
            end = fragmentEnd;
        }
        if (end == -1) end = url.length();
        return url.substring(startIndex, end);
    }

    private boolean isValidVideoId(String id) {
        return id != null && id.length() == 11 && isAlphanumericWithDashUnderscore(id);
    }

    private boolean isAlphanumericWithDashUnderscore(String s) {
        for (char c : s.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
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

