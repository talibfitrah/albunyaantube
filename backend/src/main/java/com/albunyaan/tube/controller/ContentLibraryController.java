package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
@Validated
public class ContentLibraryController {

    private static final Logger log = LoggerFactory.getLogger(ContentLibraryController.class);
    private static final int FIRESTORE_BATCH_LIMIT = 500;

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final Firestore firestore;

    public ContentLibraryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            Firestore firestore
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.firestore = firestore;
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
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @RequestParam(defaultValue = "20") int size
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        log.info("Content Library request: types={}, status={}, category={}, search={}, sort={}, page={}, size={}",
                types, status, category, search, sort, page, size);

        // Parse content types
        Set<String> contentTypes = parseTypes(types);

        // Fetch all content types
        List<ContentItem> allContent = new ArrayList<>();

        if (contentTypes.contains("channel")) {
            List<Channel> channels = fetchChannels(status, category);
            for (Channel ch : channels) {
                allContent.add(new ContentItem("channel", ch.getId(), ch.getYoutubeId(), ch.getName(),
                        ch.getDescription(), ch.getThumbnailUrl(), ch.getStatus(), ch.getCategoryIds(),
                        ch.getCreatedAt() != null ? ch.getCreatedAt().toDate() : null,
                        ch.getSubscribers()));
            }
        }

        if (contentTypes.contains("playlist")) {
            List<Playlist> playlists = fetchPlaylists(status, category);
            for (Playlist pl : playlists) {
                allContent.add(new ContentItem("playlist", pl.getId(), pl.getYoutubeId(), pl.getTitle(),
                        pl.getDescription(), pl.getThumbnailUrl(), pl.getStatus(), pl.getCategoryIds(),
                        pl.getCreatedAt() != null ? pl.getCreatedAt().toDate() : null,
                        pl.getItemCount() != null ? Long.valueOf(pl.getItemCount()) : null));
            }
        }

        if (contentTypes.contains("video")) {
            List<Video> videos = fetchVideos(status, category);
            for (Video v : videos) {
                allContent.add(new ContentItem("video", v.getId(), v.getYoutubeId(), v.getTitle(),
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

    private List<Channel> fetchChannels(String status, String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (category != null && !category.isBlank()) {
            return channelRepository.findByCategoryId(category);
        }
        if ("all".equalsIgnoreCase(status)) {
            return channelRepository.findAll();
        }
        // Normalize status to uppercase for Firestore query
        return channelRepository.findByStatus(status.toUpperCase());
    }

    private List<Playlist> fetchPlaylists(String status, String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (category != null && !category.isBlank()) {
            return playlistRepository.findByCategoryId(category);
        }
        if ("all".equalsIgnoreCase(status)) {
            return playlistRepository.findAll();
        }
        // Normalize status to uppercase for Firestore query
        return playlistRepository.findByStatus(status.toUpperCase());
    }

    private List<Video> fetchVideos(String status, String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (category != null && !category.isBlank()) {
            return videoRepository.findByCategoryId(category);
        }
        if ("all".equalsIgnoreCase(status)) {
            return videoRepository.findAll();
        }
        // Normalize status to uppercase for Firestore query
        return videoRepository.findByStatus(status.toUpperCase());
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
        public String youtubeId;
        public String title;
        public String description;
        public String thumbnailUrl;
        public String status;
        public List<String> categoryIds;
        public Date createdAt;
        public Long count; // subscriber/video/view count depending on type

        public ContentItem(String type, String id, String youtubeId, String title, String description, String thumbnailUrl,
                           String status, List<String> categoryIds, Date createdAt, Long count) {
            this.type = type;
            this.id = id;
            this.youtubeId = youtubeId;
            this.title = title;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.status = status;
            this.categoryIds = categoryIds != null ? categoryIds : List.of();
            this.createdAt = createdAt;
            this.count = count;
        }
    }

    // Bulk Actions

    /**
     * Functional interface for bulk status update operations.
     * Modifies the entity in-place and returns it for batching.
     * Returns null if the item was not found.
     */
    @FunctionalInterface
    private interface EntityModifier {
        Object modifyEntity(BulkActionItem item) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException;
    }

    /**
     * Functional interface for bulk delete operations.
     */
    @FunctionalInterface
    private interface EntityDeleter {
        boolean deleteEntity(BulkActionItem item) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException;
    }

    /**
     * Helper method to execute bulk update operations with Firestore batch writes for transactional guarantees.
     *
     * Firestore batches provide atomic commits within each batch (up to 500 operations).
     * Items are processed in chunks: each chunk either fully succeeds or fully fails.
     * This prevents partial commits within a batch while still allowing continuation if one batch fails.
     *
     * @param items List of items to process
     * @param modifier The operation to fetch and modify each entity
     * @param operationName Name of the operation for error messages (e.g., "approving", "rejecting")
     * @return BulkActionResponse with success count and errors
     */
    private BulkActionResponse executeBulkUpdateOperation(List<BulkActionItem> items, EntityModifier modifier, String operationName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();

        // Process items in chunks to respect Firestore batch limit (500 operations)
        for (int i = 0; i < items.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, items.size());
            List<BulkActionItem> batch = items.subList(i, endIndex);

            WriteBatch writeBatch = firestore.batch();
            Map<BulkActionItem, Object> entitiesToCommit = new HashMap<>();

            // First pass: fetch and modify all items (in-memory only)
            for (BulkActionItem item : batch) {
                try {
                    Object modifiedEntity = modifier.modifyEntity(item);
                    if (modifiedEntity != null) {
                        entitiesToCommit.put(item, modifiedEntity);
                    } else {
                        errors.add(item.type + " not found: " + item.id);
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                } catch (Exception e) {
                    errors.add("Error " + operationName + " " + item.type + " " + item.id + ": " + e.getMessage());
                }
            }

            // Second pass: add all modifications to batch and commit atomically
            if (!entitiesToCommit.isEmpty()) {
                try {
                    for (Map.Entry<BulkActionItem, Object> entry : entitiesToCommit.entrySet()) {
                        BulkActionItem item = entry.getKey();
                        Object entity = entry.getValue();
                        String collectionName = getCollectionName(item.type);
                        writeBatch.set(firestore.collection(collectionName).document(item.id), entity);
                    }

                    // Atomic commit - all items succeed or all fail
                    writeBatch.commit().get();
                    successCount += entitiesToCommit.size();
                    log.debug("Batch committed successfully: {} items", entitiesToCommit.size());

                } catch (Exception e) {
                    // Batch failed - all items in this batch are rolled back by Firestore
                    log.error("Batch commit failed for {} items: {}", entitiesToCommit.size(), e.getMessage());
                    for (BulkActionItem item : entitiesToCommit.keySet()) {
                        errors.add("Batch commit failed for " + item.type + " " + item.id + ": " + e.getMessage());
                    }
                }
            }
        }

        return new BulkActionResponse(successCount, errors);
    }

    /**
     * Helper method to execute bulk delete operations with Firestore batch writes.
     */
    private BulkActionResponse executeBulkDeleteOperation(List<BulkActionItem> items, EntityDeleter deleter, String operationName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();

        // Process items in chunks to respect Firestore batch limit (500 operations)
        for (int i = 0; i < items.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, items.size());
            List<BulkActionItem> batch = items.subList(i, endIndex);

            WriteBatch writeBatch = firestore.batch();
            List<BulkActionItem> itemsToDelete = new ArrayList<>();

            // First pass: verify all items exist
            for (BulkActionItem item : batch) {
                try {
                    boolean exists = deleter.deleteEntity(item);
                    if (exists) {
                        itemsToDelete.add(item);
                    } else {
                        errors.add(item.type + " not found: " + item.id);
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                } catch (Exception e) {
                    errors.add("Error " + operationName + " " + item.type + " " + item.id + ": " + e.getMessage());
                }
            }

            // Second pass: delete all items atomically
            if (!itemsToDelete.isEmpty()) {
                try {
                    for (BulkActionItem item : itemsToDelete) {
                        String collectionName = getCollectionName(item.type);
                        writeBatch.delete(firestore.collection(collectionName).document(item.id));
                    }

                    // Atomic commit
                    writeBatch.commit().get();
                    successCount += itemsToDelete.size();
                    log.debug("Batch delete committed successfully: {} items", itemsToDelete.size());

                } catch (Exception e) {
                    log.error("Batch delete failed for {} items: {}", itemsToDelete.size(), e.getMessage());
                    for (BulkActionItem item : itemsToDelete) {
                        errors.add("Batch delete failed for " + item.type + " " + item.id + ": " + e.getMessage());
                    }
                }
            }
        }

        return new BulkActionResponse(successCount, errors);
    }

    /**
     * Maps content type to Firestore collection name.
     */
    private String getCollectionName(String type) {
        switch (type.toLowerCase()) {
            case "channel": return "channels";
            case "playlist": return "playlists";
            case "video": return "videos";
            default: throw new IllegalArgumentException("Invalid type: " + type);
        }
    }

    /**
     * Bulk approve content items.
     *
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 items per batch).
     * Each batch either fully succeeds or fully fails, preventing partial commits.
     */
    @PostMapping("/bulk/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkApprove(@Valid @RequestBody BulkActionRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Early validation: check for null/empty items (redundant with @Valid but explicit)
        if (request == null || request.items == null || request.items.isEmpty()) {
            log.warn("Bulk approve rejected: empty or null request");
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, List.of("Request must contain at least one item")));
        }

        // Validate individual items for null, blank type/id
        List<String> validationErrors = new ArrayList<>();
        for (int i = 0; i < request.items.size(); i++) {
            BulkActionItem item = request.items.get(i);
            if (item == null) {
                validationErrors.add("Item at index " + i + " is null");
            } else {
                if (item.type == null || item.type.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank type");
                }
                if (item.id == null || item.id.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank id");
                }
            }
        }

        if (!validationErrors.isEmpty()) {
            log.warn("Bulk approve rejected: {} validation errors", validationErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validationErrors));
        }

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk approve started: user={}, itemCount={}", username, request.items.size());

        BulkActionResponse response = executeBulkUpdateOperation(request.items, item -> {
            switch (item.type.toLowerCase()) {
                case "channel":
                    Channel channel = channelRepository.findById(item.id).orElse(null);
                    if (channel != null) {
                        channel.setStatus("APPROVED");
                        return channel;
                    }
                    return null;
                case "playlist":
                    Playlist playlist = playlistRepository.findById(item.id).orElse(null);
                    if (playlist != null) {
                        playlist.setStatus("APPROVED");
                        return playlist;
                    }
                    return null;
                case "video":
                    Video video = videoRepository.findById(item.id).orElse(null);
                    if (video != null) {
                        video.setStatus("APPROVED");
                        return video;
                    }
                    return null;
                default:
                    throw new IllegalArgumentException("Invalid type: " + item.type);
            }
        }, "approving");

        log.info("Bulk approve completed: user={}, successCount={}, errorCount={}",
                username, response.successCount, response.errors.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Bulk reject content items.
     *
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 items per batch).
     * Each batch either fully succeeds or fully fails, preventing partial commits.
     */
    @PostMapping("/bulk/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkReject(@Valid @RequestBody BulkActionRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Early validation: check for null/empty items
        if (request == null || request.items == null || request.items.isEmpty()) {
            log.warn("Bulk reject rejected: empty or null request");
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, List.of("Request must contain at least one item")));
        }

        // Validate individual items for null, blank type/id
        List<String> validationErrors = new ArrayList<>();
        for (int i = 0; i < request.items.size(); i++) {
            BulkActionItem item = request.items.get(i);
            if (item == null) {
                validationErrors.add("Item at index " + i + " is null");
            } else {
                if (item.type == null || item.type.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank type");
                }
                if (item.id == null || item.id.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank id");
                }
            }
        }

        if (!validationErrors.isEmpty()) {
            log.warn("Bulk reject rejected: {} validation errors", validationErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validationErrors));
        }

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk reject started: user={}, itemCount={}", username, request.items.size());

        BulkActionResponse response = executeBulkUpdateOperation(request.items, item -> {
            switch (item.type.toLowerCase()) {
                case "channel":
                    Channel channel = channelRepository.findById(item.id).orElse(null);
                    if (channel != null) {
                        channel.setStatus("REJECTED");
                        return channel;
                    }
                    return null;
                case "playlist":
                    Playlist playlist = playlistRepository.findById(item.id).orElse(null);
                    if (playlist != null) {
                        playlist.setStatus("REJECTED");
                        return playlist;
                    }
                    return null;
                case "video":
                    Video video = videoRepository.findById(item.id).orElse(null);
                    if (video != null) {
                        video.setStatus("REJECTED");
                        return video;
                    }
                    return null;
                default:
                    throw new IllegalArgumentException("Invalid type: " + item.type);
            }
        }, "rejecting");

        log.info("Bulk reject completed: user={}, successCount={}, errorCount={}",
                username, response.successCount, response.errors.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Bulk delete content items.
     *
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 items per batch).
     * Each batch either fully succeeds or fully fails, preventing partial commits.
     */
    @PostMapping("/bulk/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkDelete(@Valid @RequestBody BulkActionRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Early validation: check for null/empty items
        if (request == null || request.items == null || request.items.isEmpty()) {
            log.warn("Bulk delete rejected: empty or null request");
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, List.of("Request must contain at least one item")));
        }

        // Validate individual items for null, blank type/id
        List<String> validationErrors = new ArrayList<>();
        for (int i = 0; i < request.items.size(); i++) {
            BulkActionItem item = request.items.get(i);
            if (item == null) {
                validationErrors.add("Item at index " + i + " is null");
            } else {
                if (item.type == null || item.type.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank type");
                }
                if (item.id == null || item.id.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank id");
                }
            }
        }

        if (!validationErrors.isEmpty()) {
            log.warn("Bulk delete rejected: {} validation errors", validationErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validationErrors));
        }

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk delete started: user={}, itemCount={}", username, request.items.size());

        BulkActionResponse response = executeBulkDeleteOperation(request.items, item -> {
            switch (item.type.toLowerCase()) {
                case "channel":
                    return channelRepository.findById(item.id).isPresent();
                case "playlist":
                    return playlistRepository.findById(item.id).isPresent();
                case "video":
                    return videoRepository.findById(item.id).isPresent();
                default:
                    throw new IllegalArgumentException("Invalid type: " + item.type);
            }
        }, "deleting");

        log.info("Bulk delete completed: user={}, successCount={}, errorCount={}",
                username, response.successCount, response.errors.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Bulk assign categories to content items
     */
    @PostMapping("/bulk/assign-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkAssignCategories(@Valid @RequestBody BulkCategoryAssignmentRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Early validation: check for null/empty items and categoryIds
        if (request == null || request.items == null || request.items.isEmpty()) {
            log.warn("Bulk assign categories rejected: empty or null items");
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, List.of("Request must contain at least one item")));
        }

        if (request.categoryIds == null) {
            log.warn("Bulk assign categories rejected: null categoryIds");
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, List.of("Category IDs cannot be null")));
        }

        // Validate individual items for null, blank type/id
        List<String> validationErrors = new ArrayList<>();
        for (int i = 0; i < request.items.size(); i++) {
            BulkActionItem item = request.items.get(i);
            if (item == null) {
                validationErrors.add("Item at index " + i + " is null");
            } else {
                if (item.type == null || item.type.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank type");
                }
                if (item.id == null || item.id.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank id");
                }
            }
        }

        if (!validationErrors.isEmpty()) {
            log.warn("Bulk assign categories rejected: {} validation errors", validationErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validationErrors));
        }

        int successCount = 0;
        List<String> errors = new ArrayList<>();

        for (BulkActionItem item : request.items) {
            try {
                switch (item.type.toLowerCase()) {
                    case "channel":
                        Channel channel = channelRepository.findById(item.id).orElse(null);
                        if (channel != null) {
                            channel.setCategoryIds(request.categoryIds);
                            channelRepository.save(channel);
                            successCount++;
                        } else {
                            errors.add("Channel not found: " + item.id);
                        }
                        break;
                    case "playlist":
                        Playlist playlist = playlistRepository.findById(item.id).orElse(null);
                        if (playlist != null) {
                            playlist.setCategoryIds(request.categoryIds);
                            playlistRepository.save(playlist);
                            successCount++;
                        } else {
                            errors.add("Playlist not found: " + item.id);
                        }
                        break;
                    case "video":
                        Video video = videoRepository.findById(item.id).orElse(null);
                        if (video != null) {
                            video.setCategoryIds(request.categoryIds);
                            videoRepository.save(video);
                            successCount++;
                        } else {
                            errors.add("Video not found: " + item.id);
                        }
                        break;
                    default:
                        errors.add("Invalid type: " + item.type);
                }
            } catch (Exception e) {
                errors.add("Error assigning categories to " + item.type + " " + item.id + ": " + e.getMessage());
            }
        }

        return ResponseEntity.ok(new BulkActionResponse(successCount, errors));
    }

    // Bulk action DTOs

    public static class BulkActionRequest {
        @NotNull(message = "Items list cannot be null")
        @NotEmpty(message = "Items list cannot be empty")
        @Valid
        public List<BulkActionItem> items;
    }

    public static class BulkActionItem {
        @NotNull(message = "Content type cannot be null")
        @NotBlank(message = "Content type cannot be blank")
        public String type; // channel, playlist, or video

        @NotNull(message = "Content ID cannot be null")
        @NotBlank(message = "Content ID cannot be blank")
        public String id;
    }

    public static class BulkCategoryAssignmentRequest {
        @NotNull(message = "Items list cannot be null")
        @NotEmpty(message = "Items list cannot be empty")
        @Valid
        public List<BulkActionItem> items;

        @NotNull(message = "Category IDs list cannot be null")
        public List<String> categoryIds;
    }

    public static class BulkActionResponse {
        public int successCount;
        public List<String> errors;

        public BulkActionResponse(int successCount, List<String> errors) {
            this.successCount = successCount;
            this.errors = errors;
        }
    }
}

