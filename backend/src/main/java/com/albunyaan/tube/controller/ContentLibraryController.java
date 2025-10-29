package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Content Library Controller
 *
 * Unified endpoint for managing all approved content (channels, playlists, videos).
 * Powers the Content Library admin view with filtering, search, and pagination.
 *
 * Endpoint: /api/admin/content
 */
@RestController
@RequestMapping("/api/admin/content")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class ContentLibraryController {

    private static final Logger log = LoggerFactory.getLogger(ContentLibraryController.class);

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;

    public ContentLibraryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
    }

    /**
     * Get all content with filtering
     *
     * Query Parameters:
     * - types: Comma-separated list of content types (channel,playlist,video)
     * - status: Filter by status (all, approved, pending, rejected)
     * - category: Filter by category ID
     * - search: Search in title and description
     * - sort: Sort order (newest, oldest)
     * - page: Page number (0-indexed)
     * - size: Page size (default 20)
     */
    @GetMapping
    public ResponseEntity<ContentLibraryResponse> getContent(
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) throws ExecutionException, InterruptedException {

        log.info("Content Library request: types={}, status={}, category={}, search={}, sort={}, page={}, size={}",
                types, status, category, search, sort, page, size);

        // Parse content types
        Set<String> contentTypes = parseTypes(types);

        // Fetch all content types
        List<ContentItem> allContent = new ArrayList<>();

        if (contentTypes.contains("channel")) {
            List<Channel> channels = fetchChannels(status, category);
            for (Channel ch : channels) {
                allContent.add(new ContentItem("channel", ch.getId(), ch.getName(),
                        ch.getDescription(), ch.getThumbnailUrl(), ch.getStatus(), ch.getCategoryIds(),
                        ch.getCreatedAt() != null ? ch.getCreatedAt().toDate() : null,
                        ch.getSubscribers()));
            }
        }

        if (contentTypes.contains("playlist")) {
            List<Playlist> playlists = fetchPlaylists(status, category);
            for (Playlist pl : playlists) {
                allContent.add(new ContentItem("playlist", pl.getId(), pl.getTitle(),
                        pl.getDescription(), pl.getThumbnailUrl(), pl.getStatus(), pl.getCategoryIds(),
                        pl.getCreatedAt() != null ? pl.getCreatedAt().toDate() : null,
                        pl.getItemCount() != null ? Long.valueOf(pl.getItemCount()) : null));
            }
        }

        if (contentTypes.contains("video")) {
            List<Video> videos = fetchVideos(status, category);
            for (Video v : videos) {
                allContent.add(new ContentItem("video", v.getId(), v.getTitle(),
                        v.getDescription(), v.getThumbnailUrl(), v.getStatus(), v.getCategoryIds(),
                        v.getCreatedAt() != null ? v.getCreatedAt().toDate() : null,
                        v.getViewCount()));
            }
        }

        // Apply search filter
        if (search != null && !search.isBlank()) {
            String searchLower = search.toLowerCase();
            allContent = allContent.stream()
                    .filter(item -> item.title.toLowerCase().contains(searchLower) ||
                            (item.description != null && item.description.toLowerCase().contains(searchLower)))
                    .collect(Collectors.toList());
        }

        // Sort
        allContent.sort((a, b) -> {
            if (a.createdAt == null || b.createdAt == null) return 0;
            return sort.equals("oldest") ? a.createdAt.compareTo(b.createdAt) : b.createdAt.compareTo(a.createdAt);
        });

        // Paginate
        int start = page * size;
        int end = Math.min(start + size, allContent.size());
        List<ContentItem> pagedContent = start < allContent.size() ? allContent.subList(start, end) : List.of();

        ContentLibraryResponse response = new ContentLibraryResponse(
                pagedContent,
                allContent.size(),
                page,
                size,
                (int) Math.ceil((double) allContent.size() / size)
        );

        log.info("Returning {} items (total: {}, page: {}/{})",
                pagedContent.size(), allContent.size(), page + 1, response.totalPages);

        return ResponseEntity.ok(response);
    }

    private Set<String> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return Set.of("channel", "playlist", "video"); // Default: all types
        }
        return Arrays.stream(types.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private List<Channel> fetchChannels(String status, String category) throws ExecutionException, InterruptedException {
        if (category != null && !category.isBlank()) {
            return channelRepository.findByCategoryId(category);
        }
        if ("all".equals(status)) {
            return channelRepository.findAll();
        }
        return channelRepository.findByStatus(status);
    }

    private List<Playlist> fetchPlaylists(String status, String category) throws ExecutionException, InterruptedException {
        if (category != null && !category.isBlank()) {
            return playlistRepository.findByCategoryId(category);
        }
        if ("all".equals(status)) {
            return playlistRepository.findAll();
        }
        return playlistRepository.findByStatus(status);
    }

    private List<Video> fetchVideos(String status, String category) throws ExecutionException, InterruptedException {
        if (category != null && !category.isBlank()) {
            return videoRepository.findByCategoryId(category);
        }
        if ("all".equals(status)) {
            return videoRepository.findAll();
        }
        return videoRepository.findByStatus(status);
    }

    // DTOs

    public static class ContentLibraryResponse {
        public List<ContentItem> content;
        public int totalItems;
        public int currentPage;
        public int pageSize;
        public int totalPages;

        public ContentLibraryResponse(List<ContentItem> content, int totalItems, int currentPage, int pageSize, int totalPages) {
            this.content = content;
            this.totalItems = totalItems;
            this.currentPage = currentPage;
            this.pageSize = pageSize;
            this.totalPages = totalPages;
        }
    }

    public static class ContentItem {
        public String type;
        public String id;
        public String title;
        public String description;
        public String thumbnailUrl;
        public String status;
        public List<String> categoryIds;
        public Date createdAt;
        public Long count; // subscriber/video/view count depending on type

        public ContentItem(String type, String id, String title, String description, String thumbnailUrl,
                           String status, List<String> categoryIds, Date createdAt, Long count) {
            this.type = type;
            this.id = id;
            this.title = title;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.status = status;
            this.categoryIds = categoryIds != null ? categoryIds : List.of();
            this.createdAt = createdAt;
            this.count = count;
        }
    }
}
