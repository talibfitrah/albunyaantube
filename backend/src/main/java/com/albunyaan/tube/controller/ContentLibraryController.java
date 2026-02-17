package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.SortOrderService;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ContentLibraryController {

    private static final Logger log = LoggerFactory.getLogger(ContentLibraryController.class);
    private static final int FIRESTORE_BATCH_LIMIT = 500;

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;
    private final PublicContentCacheService publicContentCacheService;
    private final SortOrderService sortOrderService;

    public ContentLibraryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            Firestore firestore,
            FirestoreTimeoutProperties timeoutProperties,
            PublicContentCacheService publicContentCacheService,
            SortOrderService sortOrderService
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
        this.publicContentCacheService = publicContentCacheService;
        this.sortOrderService = sortOrderService;
    }

    /**
     * Helper to fetch category IDs for a content item.
     */
    private List<String> getCategoryIdsForItem(String type, String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        switch (type.toLowerCase()) {
            case "channel":
                return channelRepository.findById(id).map(Channel::getCategoryIds).orElse(null);
            case "playlist":
                return playlistRepository.findById(id).map(Playlist::getCategoryIds).orElse(null);
            case "video":
                return videoRepository.findById(id).map(Video::getCategoryIds).orElse(null);
            default:
                return null;
        }
    }

    /**
     * Maximum items to fetch per content type to prevent quota exhaustion.
     * This provides a bounded query instead of full collection scans.
     * Admin UI should use pagination for larger datasets.
     */
    private static final int MAX_ITEMS_PER_TYPE = 200;

    /**
     * Helper class to track fetch results including truncation detection.
     * Uses limit+1 approach: fetch one extra item to detect if more exist.
     */
    private static class BoundedFetchResult<T> {
        final List<T> items;
        final boolean hitLimit;

        BoundedFetchResult(List<T> items, boolean hitLimit) {
            this.items = items;
            this.hitLimit = hitLimit;
        }
    }

    /**
     * Get content with filtering (bounded queries to prevent quota exhaustion)
     *
     * Query Parameters:
     * - types: Comma-separated list of content types (channel,playlist,video)
     * - status: Filter by status (all, approved, pending, rejected)
     * - category: Filter by category ID
     * - search: Search in title (prefix match for search, limited results)
     * - sort: Sort order (newest, oldest, custom)
     * - page: Page number (0-indexed)
     * - size: Page size (default 20, max 100)
     *
     * NOTE: For large datasets, this endpoint uses bounded queries (max 200 per type)
     * to prevent Firestore quota exhaustion. Use specific type filters and pagination.
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

        // Cap page size to prevent excessive reads
        int cappedSize = Math.min(size, 100);

        log.info("Content Library request: types={}, status={}, category={}, search={}, sort={}, page={}, size={}",
                types, status, category, search, sort, page, cappedSize);

        // Parse content types
        Set<String> contentTypes = parseTypes(types);

        // Calculate how many items we need to fetch (page offset + page size)
        // Add buffer for in-memory filtering (search)
        int fetchLimit = Math.min((page + 1) * cappedSize + 50, MAX_ITEMS_PER_TYPE);

        // Fetch content with bounded queries using limit+1 approach to detect truncation
        // This prevents false-negatives where post-filtering might reduce count below limit
        List<ContentItem> allContent = new ArrayList<>();
        boolean anyTypeTruncated = false;

        if (contentTypes.contains("channel")) {
            BoundedFetchResult<Channel> result = fetchChannelsBounded(status, category, search, sort, fetchLimit);
            if (result.hitLimit) {
                anyTypeTruncated = true;
            }
            for (Channel ch : result.items) {
                allContent.add(new ContentItem("channel", ch.getId(), ch.getYoutubeId(), ch.getName(),
                        ch.getDescription(), ch.getThumbnailUrl(), ch.getStatus(), ch.getCategoryIds(),
                        ch.getCreatedAt() != null ? ch.getCreatedAt().toDate() : null,
                        ch.getSubscribers(), ch.getDisplayOrder(), ch.getKeywords()));
            }
        }

        if (contentTypes.contains("playlist")) {
            BoundedFetchResult<Playlist> result = fetchPlaylistsBounded(status, category, search, sort, fetchLimit);
            if (result.hitLimit) {
                anyTypeTruncated = true;
            }
            for (Playlist pl : result.items) {
                allContent.add(new ContentItem("playlist", pl.getId(), pl.getYoutubeId(), pl.getTitle(),
                        pl.getDescription(), pl.getThumbnailUrl(), pl.getStatus(), pl.getCategoryIds(),
                        pl.getCreatedAt() != null ? pl.getCreatedAt().toDate() : null,
                        pl.getItemCount() != null ? Long.valueOf(pl.getItemCount()) : null, pl.getDisplayOrder(),
                        pl.getKeywords()));
            }
        }

        if (contentTypes.contains("video")) {
            BoundedFetchResult<Video> result = fetchVideosBounded(status, category, search, sort, fetchLimit);
            if (result.hitLimit) {
                anyTypeTruncated = true;
            }
            for (Video v : result.items) {
                allContent.add(new ContentItem("video", v.getId(), v.getYoutubeId(), v.getTitle(),
                        v.getDescription(), v.getThumbnailUrl(), v.getStatus(), v.getCategoryIds(),
                        v.getCreatedAt() != null ? v.getCreatedAt().toDate() : null,
                        v.getViewCount(), v.getDisplayOrder(), v.getKeywords()));
            }
        }

        // Apply in-memory search filter for comprehensive keyword + title + description matching
        // Search is fully in-memory to enable matching across all fields (title, keywords, description)
        // This provides better search accuracy than Firestore prefix-only queries
        if (search != null && !search.isBlank()) {
            String searchLower = search.toLowerCase(java.util.Locale.ROOT);
            allContent = allContent.stream()
                    .filter(item -> matchesSearch(item, searchLower))
                    .collect(Collectors.toList());
        }

        // Sort combined results with deterministic tie-breakers
        allContent.sort((a, b) -> {
            int dateCmp;
            // Custom sort uses displayOrder (nulls treated as MAX_VALUE to sort last)
            if ("custom".equals(sort)) {
                int orderA = a.displayOrder != null ? a.displayOrder : Integer.MAX_VALUE;
                int orderB = b.displayOrder != null ? b.displayOrder : Integer.MAX_VALUE;
                int cmp = Integer.compare(orderA, orderB);
                if (cmp != 0) return cmp;
                // Primary tie-breaker: newest first by createdAt
                if (a.createdAt == null && b.createdAt == null) {
                    dateCmp = 0;
                } else if (a.createdAt == null) {
                    return 1;
                } else if (b.createdAt == null) {
                    return -1;
                } else {
                    dateCmp = b.createdAt.compareTo(a.createdAt);
                }
                if (dateCmp != 0) return dateCmp;
                // Final tie-breaker: by ID for determinism
                return a.id.compareTo(b.id);
            }
            // Date-based sort
            if (a.createdAt == null && b.createdAt == null) {
                dateCmp = 0;
            } else if (a.createdAt == null) {
                return 1;
            } else if (b.createdAt == null) {
                return -1;
            } else {
                dateCmp = sort.equals("oldest") ? a.createdAt.compareTo(b.createdAt) : b.createdAt.compareTo(a.createdAt);
            }
            if (dateCmp != 0) return dateCmp;
            // Final tie-breaker: by ID for determinism
            return a.id.compareTo(b.id);
        });

        // Paginate
        int start = page * cappedSize;
        int end = Math.min(start + cappedSize, allContent.size());
        List<ContentItem> pagedContent = start < allContent.size() ? allContent.subList(start, end) : List.of();

        // Note: totalItems may be capped due to bounded queries
        int totalItems = allContent.size();
        // Results are truncated if any content type hit the query limit
        boolean isTruncated = anyTypeTruncated;

        ContentLibraryResponse response = new ContentLibraryResponse(
                pagedContent,
                totalItems,
                page,
                cappedSize,
                (int) Math.ceil((double) totalItems / cappedSize),
                isTruncated
        );

        log.info("Returning {} items (total: {}, page: {}/{}, truncated: {})",
                pagedContent.size(), totalItems, page + 1, response.totalPages, isTruncated);

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

    /**
     * Check if a content item matches the search query.
     * Matches against title (contains) and keywords (prefix or contains match).
     * This enables searching by keyword tags as well as title text.
     */
    private boolean matchesSearch(ContentItem item, String searchLower) {
        // Check title (contains match for flexibility)
        if (item.title != null && item.title.toLowerCase(java.util.Locale.ROOT).contains(searchLower)) {
            return true;
        }
        // Check keywords (prefix or contains match for better search accuracy)
        // This allows searching for partial keyword matches (e.g., "cook" matches "cooking")
        if (item.keywords != null) {
            for (String keyword : item.keywords) {
                if (keyword != null) {
                    String keywordLower = keyword.toLowerCase(java.util.Locale.ROOT);
                    // Match if keyword starts with search term OR contains search term
                    if (keywordLower.startsWith(searchLower) || keywordLower.contains(searchLower)) {
                        return true;
                    }
                }
            }
        }
        // Check description for search terms (improves search accuracy)
        if (item.description != null && item.description.toLowerCase(java.util.Locale.ROOT).contains(searchLower)) {
            return true;
        }
        return false;
    }

    /**
     * Fetch channels with bounded query to prevent quota exhaustion.
     * Uses Firestore-level filtering with query-level limits.
     * Returns BoundedFetchResult with limit+1 detection to avoid false-negative truncation.
     *
     * NOTE: Search filtering is done in-memory at the caller level to support
     * comprehensive search across title, keywords, and description fields.
     * This ensures keywords improve search accuracy for typical admin searches.
     */
    private BoundedFetchResult<Channel> fetchChannelsBounded(String status, String category, String search, String sort, int limit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Fetch limit+1 to detect if more results exist (prevents false-negative after filtering)
        int queryLimit = limit + 1;

        // Search filtering is now done in-memory to support keyword + description matching
        // Fetch by status/category at Firestore level, then caller applies search filter

        // Category + status filter - use bounded query at Firestore level
        if (category != null && !category.isBlank()) {
            List<Channel> results;
            if ("all".equalsIgnoreCase(status)) {
                // status=all: show all statuses (APPROVED, PENDING, REJECTED) for the category
                results = channelRepository.findByCategoryIdAllStatus(category, queryLimit);
            } else if ("approved".equalsIgnoreCase(status)) {
                // status=approved: use the existing approved-only method
                results = channelRepository.findByCategoryId(category, queryLimit);
            } else {
                // specific status (PENDING, REJECTED): use category+status combined query
                results = channelRepository.findByCategoryIdAndStatus(category, status.toUpperCase(), queryLimit);
            }
            boolean hitLimit = results.size() > limit;
            if (hitLimit) {
                results = results.subList(0, limit);
            }
            return new BoundedFetchResult<>(results, hitLimit);
        }

        // Status-only filter with limit - use correct sort order at query level
        boolean sortOldest = "oldest".equalsIgnoreCase(sort);
        List<Channel> results;
        if ("all".equalsIgnoreCase(status)) {
            results = sortOldest ? channelRepository.findAllOrderByCreatedAtAsc(queryLimit) : channelRepository.findAll(queryLimit);
        } else {
            results = sortOldest
                    ? channelRepository.findByStatusOrderByCreatedAtAsc(status.toUpperCase(), queryLimit)
                    : channelRepository.findByStatus(status.toUpperCase(), queryLimit);
        }
        boolean hitLimit = results.size() > limit;
        if (hitLimit) {
            results = results.subList(0, limit);
        }
        return new BoundedFetchResult<>(results, hitLimit);
    }

    /**
     * Fetch playlists with bounded query to prevent quota exhaustion.
     * Uses Firestore-level filtering with query-level limits.
     * Returns BoundedFetchResult with limit+1 detection to avoid false-negative truncation.
     *
     * NOTE: Search filtering is done in-memory at the caller level to support
     * comprehensive search across title, keywords, and description fields.
     */
    private BoundedFetchResult<Playlist> fetchPlaylistsBounded(String status, String category, String search, String sort, int limit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Fetch limit+1 to detect if more results exist (prevents false-negative after filtering)
        int queryLimit = limit + 1;

        // Search filtering is now done in-memory to support keyword + description matching
        // Fetch by status/category at Firestore level, then caller applies search filter

        // Category + status filter - use bounded query at Firestore level
        if (category != null && !category.isBlank()) {
            List<Playlist> results;
            if ("all".equalsIgnoreCase(status)) {
                // status=all: show all statuses (APPROVED, PENDING, REJECTED) for the category
                results = playlistRepository.findByCategoryIdAllStatus(category, queryLimit);
            } else if ("approved".equalsIgnoreCase(status)) {
                // status=approved: use the existing approved-only method
                results = playlistRepository.findByCategoryId(category, queryLimit);
            } else {
                // specific status (PENDING, REJECTED): use category+status combined query
                results = playlistRepository.findByCategoryIdAndStatus(category, status.toUpperCase(), queryLimit);
            }
            boolean hitLimit = results.size() > limit;
            if (hitLimit) {
                results = results.subList(0, limit);
            }
            return new BoundedFetchResult<>(results, hitLimit);
        }

        // Status-only filter with limit - use correct sort order at query level
        boolean sortOldest = "oldest".equalsIgnoreCase(sort);
        List<Playlist> results;
        if ("all".equalsIgnoreCase(status)) {
            results = sortOldest ? playlistRepository.findAllOrderByCreatedAtAsc(queryLimit) : playlistRepository.findAll(queryLimit);
        } else {
            results = sortOldest
                    ? playlistRepository.findByStatusOrderByCreatedAtAsc(status.toUpperCase(), queryLimit)
                    : playlistRepository.findByStatus(status.toUpperCase(), queryLimit);
        }
        boolean hitLimit = results.size() > limit;
        if (hitLimit) {
            results = results.subList(0, limit);
        }
        return new BoundedFetchResult<>(results, hitLimit);
    }

    /**
     * Fetch videos with bounded query to prevent quota exhaustion.
     * Uses Firestore-level filtering with query-level limits.
     * Returns BoundedFetchResult with limit+1 detection to avoid false-negative truncation.
     *
     * NOTE: Search filtering is done in-memory at the caller level to support
     * comprehensive search across title, keywords, and description fields.
     */
    private BoundedFetchResult<Video> fetchVideosBounded(String status, String category, String search, String sort, int limit)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Fetch limit+1 to detect if more results exist (prevents false-negative after filtering)
        int queryLimit = limit + 1;

        // Search filtering is now done in-memory to support keyword + description matching
        // Fetch by status/category at Firestore level, then caller applies search filter

        // Category + status filter - use bounded query at Firestore level
        if (category != null && !category.isBlank()) {
            List<Video> results;
            if ("all".equalsIgnoreCase(status)) {
                // status=all: show all statuses (APPROVED, PENDING, REJECTED) for the category
                results = videoRepository.findByCategoryIdAllStatus(category, queryLimit);
            } else if ("approved".equalsIgnoreCase(status)) {
                // status=approved: use the existing approved-only method
                results = videoRepository.findByCategoryId(category, queryLimit);
            } else {
                // specific status (PENDING, REJECTED): use category+status combined query
                results = videoRepository.findByCategoryIdAndStatus(category, status.toUpperCase(), queryLimit);
            }
            boolean hitLimit = results.size() > limit;
            if (hitLimit) {
                results = results.subList(0, limit);
            }
            return new BoundedFetchResult<>(results, hitLimit);
        }

        // Status-only filter with limit - use correct sort order at query level
        boolean sortOldest = "oldest".equalsIgnoreCase(sort);
        List<Video> results;
        if ("all".equalsIgnoreCase(status)) {
            results = sortOldest ? videoRepository.findAllOrderByCreatedAtAsc(queryLimit) : videoRepository.findAll(queryLimit);
        } else {
            results = sortOldest
                    ? videoRepository.findByStatusOrderByCreatedAtAsc(status.toUpperCase(), queryLimit)
                    : videoRepository.findByStatus(status.toUpperCase(), queryLimit);
        }
        boolean hitLimit = results.size() > limit;
        if (hitLimit) {
            results = results.subList(0, limit);
        }
        return new BoundedFetchResult<>(results, hitLimit);
    }

    // DTOs

    public static class ContentLibraryResponse {
        public List<ContentItem> content;
        public int totalItems;
        public int currentPage;
        public int pageSize;
        public int totalPages;
        /** True if results may be incomplete due to bounded query limits */
        public boolean truncated;

        public ContentLibraryResponse(List<ContentItem> content, int totalItems, int currentPage, int pageSize, int totalPages, boolean truncated) {
            this.content = content;
            this.totalItems = totalItems;
            this.currentPage = currentPage;
            this.pageSize = pageSize;
            this.totalPages = totalPages;
            this.truncated = truncated;
        }
    }

    public static class ContentItem {
        public String type;
        public String id;
        public String youtubeId; // YouTube ID for video preview/player
        public String title;
        public String description;
        public String thumbnailUrl;
        public String status;
        public List<String> categoryIds;
        public Date createdAt;
        public Long count; // subscriber/video/view count depending on type
        public Integer displayOrder; // custom ordering position
        public List<String> keywords; // keywords/tags for search

        public ContentItem(String type, String id, String youtubeId, String title, String description, String thumbnailUrl,
                           String status, List<String> categoryIds, Date createdAt, Long count, Integer displayOrder,
                           List<String> keywords) {
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
            this.displayOrder = displayOrder;
            this.keywords = keywords;
        }
    }

    // Bulk Actions

    /**
     * Functional interface for bulk delete operations.
     */
    @FunctionalInterface
    private interface EntityDeleter {
        boolean deleteEntity(BulkActionItem item) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException;
    }

    /**
     * Helper method to execute bulk status update operations using update-only writes.
     *
     * Uses batched getAll() for existence validation (with timeout) and update() instead of set()
     * to prevent lost updates from concurrent modifications. Only modifies status and updatedAt fields.
     *
     * Firestore batches provide atomic commits within each batch (up to 500 operations).
     * Items are processed in chunks: each chunk either fully succeeds or fully fails.
     *
     * @param items List of items to process
     * @param newStatus The new status to set ("APPROVED" or "REJECTED")
     * @param operationName Name of the operation for error messages (e.g., "approving", "rejecting")
     * @return BulkActionResponse with success count and errors
     */
    private BulkActionResponse executeBulkStatusUpdate(List<BulkActionItem> items, String newStatus, String operationName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();
        Set<String> failedKeys = new HashSet<>();

        // Process items in chunks to respect Firestore batch limit (500 operations)
        for (int i = 0; i < items.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, items.size());
            List<BulkActionItem> batch = items.subList(i, endIndex);

            List<BulkActionItem> validatedItems = new ArrayList<>();

            // First pass: validate existence using batched getAll with timeout
            try {
                List<DocumentReference> docRefs = new ArrayList<>();
                for (BulkActionItem item : batch) {
                    try {
                        String collectionName = getCollectionName(item.type);
                        docRefs.add(firestore.collection(collectionName).document(item.id));
                    } catch (IllegalArgumentException e) {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add(e.getMessage());
                    }
                }

                if (!docRefs.isEmpty()) {
                    List<DocumentSnapshot> snapshots = firestore.getAll(docRefs.toArray(new DocumentReference[0]))
                            .get(timeoutProperties.getRead(), TimeUnit.SECONDS);

                    // Match snapshots back to items (preserving order)
                    int snapshotIndex = 0;
                    for (BulkActionItem item : batch) {
                        try {
                            getCollectionName(item.type); // Will throw if invalid type
                            if (snapshotIndex < snapshots.size() && snapshots.get(snapshotIndex).exists()) {
                                validatedItems.add(item);
                            } else {
                                failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                                errors.add(item.type + " not found: " + item.id);
                            }
                            snapshotIndex++;
                        } catch (IllegalArgumentException e) {
                            // Already added to errors above
                        }
                    }
                }
            } catch (TimeoutException e) {
                log.error("Timeout during existence check for batch: {}", e.getMessage());
                for (BulkActionItem item : batch) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                    errors.add("Timeout checking existence of " + item.type + " " + item.id);
                }
                continue; // Skip to next batch
            } catch (Exception e) {
                log.error("Error during existence check for batch: {}", e.getMessage());
                for (BulkActionItem item : batch) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                    errors.add("Error checking " + item.type + " " + item.id + ": " + e.getMessage());
                }
                continue; // Skip to next batch
            }

            // Second pass: UPDATE (not SET) only status and updatedAt fields
            // For channels, also update legacy boolean flags (pending, approved)
            if (!validatedItems.isEmpty()) {
                WriteBatch writeBatch = firestore.batch();
                try {
                    for (BulkActionItem item : validatedItems) {
                        String collectionName = getCollectionName(item.type);
                        DocumentReference docRef = firestore.collection(collectionName).document(item.id);

                        if ("channel".equalsIgnoreCase(item.type)) {
                            // Channel has legacy boolean flags that must stay in sync with status
                            boolean isPending = "PENDING".equals(newStatus);
                            boolean isApproved = "APPROVED".equals(newStatus);
                            writeBatch.update(docRef,
                                    "status", newStatus,
                                    "pending", isPending,
                                    "approved", isApproved,
                                    "updatedAt", com.google.cloud.Timestamp.now());
                        } else {
                            // Playlists and videos don't have legacy boolean flags
                            writeBatch.update(docRef,
                                    "status", newStatus,
                                    "updatedAt", com.google.cloud.Timestamp.now());
                        }
                    }

                    // Atomic commit with timeout - all items succeed or all fail
                    writeBatch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                    successCount += validatedItems.size();
                    log.debug("Batch committed successfully: {} items with status={}", validatedItems.size(), newStatus);

                } catch (TimeoutException e) {
                    log.error("Timeout during batch commit: {}", e.getMessage());
                    for (BulkActionItem item : validatedItems) {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add("Timeout " + operationName + " " + item.type + " " + item.id);
                    }
                } catch (Exception e) {
                    // Batch failed - all items in this batch are rolled back by Firestore
                    log.error("Batch commit failed for {} items: {}", validatedItems.size(), e.getMessage());
                    for (BulkActionItem item : validatedItems) {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add("Batch commit failed for " + item.type + " " + item.id + ": " + e.getMessage());
                    }
                }
            }
        }

        return new BulkActionResponse(successCount, errors, failedKeys);
    }

    /**
     * Helper method to execute bulk delete operations with Firestore batch writes.
     */
    private BulkActionResponse executeBulkDeleteOperation(List<BulkActionItem> items, EntityDeleter deleter, String operationName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();
        Set<String> failedKeys = new HashSet<>();

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
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add(item.type + " not found: " + item.id);
                    }
                } catch (IllegalArgumentException e) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                    errors.add(e.getMessage());
                } catch (Exception e) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
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
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add("Batch delete failed for " + item.type + " " + item.id + ": " + e.getMessage());
                    }
                }
            }
        }

        return new BulkActionResponse(successCount, errors, failedKeys);
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

        BulkActionResponse response = executeBulkStatusUpdate(request.items, "APPROVED", "approving");

        // Add newly approved items to category sort order (only for items that succeeded)
        if (response.successCount > 0) {
            for (BulkActionItem item : request.items) {
                String key = item.type.toLowerCase() + ":" + item.id;
                if (response.failedKeys.contains(key)) continue;
                try {
                    List<String> catIds = getCategoryIdsForItem(item.type, item.id);
                    if (catIds != null) {
                        for (String categoryId : catIds) {
                            sortOrderService.addContentToCategory(categoryId, item.id, item.type.toLowerCase());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to add sort order for {} {}: {}", item.type, item.id, e.getMessage());
                }
            }
        }
        publicContentCacheService.evictPublicContentCaches();

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

        BulkActionResponse response = executeBulkStatusUpdate(request.items, "REJECTED", "rejecting");

        // Remove rejected items from category sort order (only for items that succeeded)
        if (response.successCount > 0) {
            for (BulkActionItem item : request.items) {
                String key = item.type.toLowerCase() + ":" + item.id;
                if (response.failedKeys.contains(key)) continue;
                try {
                    sortOrderService.removeContentFromAllCategories(item.id, item.type.toLowerCase());
                } catch (Exception e) {
                    log.warn("Failed to remove sort order for {} {}: {}", item.type, item.id, e.getMessage());
                }
            }
        }
        publicContentCacheService.evictPublicContentCaches();

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

        // Clean up sort order entries only for items that were actually deleted
        // (i.e., items that no longer exist in the repository)
        if (response.successCount > 0) {
            for (BulkActionItem item : request.items) {
                String key = item.type.toLowerCase() + ":" + item.id;
                if (response.failedKeys.contains(key)) continue; // Skip items that failed to delete
                try {
                    sortOrderService.removeContentFromAllCategories(item.id, item.type.toLowerCase());
                } catch (Exception e) {
                    log.warn("Failed to clean up sort order for {} {}: {}", item.type, item.id, e.getMessage());
                }
            }
        }
        publicContentCacheService.evictPublicContentCaches();

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
                List<String> oldCategoryIds = null;
                String contentType = item.type.toLowerCase();
                boolean isApproved = false;

                switch (contentType) {
                    case "channel":
                        Channel channel = channelRepository.findById(item.id).orElse(null);
                        if (channel != null) {
                            oldCategoryIds = channel.getCategoryIds();
                            isApproved = "APPROVED".equals(channel.getStatus());
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
                            oldCategoryIds = playlist.getCategoryIds();
                            isApproved = "APPROVED".equals(playlist.getStatus());
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
                            oldCategoryIds = video.getCategoryIds();
                            isApproved = "APPROVED".equals(video.getStatus());
                            video.setCategoryIds(request.categoryIds);
                            videoRepository.save(video);
                            successCount++;
                        } else {
                            errors.add("Video not found: " + item.id);
                        }
                        break;
                    default:
                        errors.add("Invalid type: " + item.type);
                        continue;
                }

                // Sync sort order for approved items: remove from old categories, add to new
                if (isApproved && oldCategoryIds != null) {
                    Set<String> oldSet = new HashSet<>(oldCategoryIds);
                    Set<String> newSet = new HashSet<>(request.categoryIds != null ? request.categoryIds : List.of());
                    // Remove from categories no longer assigned
                    for (String oldCat : oldSet) {
                        if (!newSet.contains(oldCat)) {
                            try {
                                sortOrderService.removeContentFromCategory(oldCat, item.id, contentType);
                            } catch (Exception e) {
                                log.warn("Failed to remove sort order for {} {} from category {}: {}", contentType, item.id, oldCat, e.getMessage());
                            }
                        }
                    }
                    // Add to newly assigned categories
                    for (String newCat : newSet) {
                        if (!oldSet.contains(newCat)) {
                            try {
                                sortOrderService.addContentToCategory(newCat, item.id, contentType);
                            } catch (Exception e) {
                                log.warn("Failed to add sort order for {} {} to category {}: {}", contentType, item.id, newCat, e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("Error assigning categories to " + item.type + " " + item.id + ": " + e.getMessage());
            }
        }

        publicContentCacheService.evictPublicContentCaches();
        return ResponseEntity.ok(new BulkActionResponse(successCount, errors));
    }

    /**
     * Update keywords/tags for a single content item.
     *
     * Keywords are used for improved search accuracy.
     * This is an optional field - can be set to null or empty list.
     *
     * Validation:
     * - Maximum 50 keywords per item
     * - Maximum 100 characters per keyword
     */
    private static final int MAX_KEYWORDS = 50;
    private static final int MAX_KEYWORD_LENGTH = 100;

    @PutMapping("/{type}/{id}/keywords")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateKeywords(
            @PathVariable @NotBlank String type,
            @PathVariable @NotBlank String id,
            @RequestBody KeywordsUpdateRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        log.info("Updating keywords for {} {}: {}", type, id, request.keywords);

        // Normalize keywords: trim whitespace, filter blanks, dedupe, then validate bounds
        List<String> normalizedKeywords = null;
        if (request.keywords != null) {
            normalizedKeywords = request.keywords.stream()
                    .filter(k -> k != null)
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());

            // Validate bounds after normalization
            if (normalizedKeywords.size() > MAX_KEYWORDS) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Too many keywords",
                        "message", "Maximum " + MAX_KEYWORDS + " keywords allowed, got " + normalizedKeywords.size() + " (after deduplication)"
                ));
            }
            for (int i = 0; i < normalizedKeywords.size(); i++) {
                String keyword = normalizedKeywords.get(i);
                if (keyword.length() > MAX_KEYWORD_LENGTH) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Keyword too long",
                            "message", "Keyword '" + keyword.substring(0, Math.min(20, keyword.length())) + "...' exceeds " + MAX_KEYWORD_LENGTH + " characters (length: " + keyword.length() + ")"
                    ));
                }
            }
        }

        try {
            switch (type.toLowerCase()) {
                case "channel":
                    Channel channel = channelRepository.findById(id).orElse(null);
                    if (channel == null) {
                        return ResponseEntity.notFound().build();
                    }
                    channel.setKeywords(normalizedKeywords);
                    channel.touch();
                    channelRepository.save(channel);
                    publicContentCacheService.evictPublicContentCaches();
                    return ResponseEntity.ok(Map.of("success", true, "keywords", normalizedKeywords != null ? normalizedKeywords : List.of()));

                case "playlist":
                    Playlist playlist = playlistRepository.findById(id).orElse(null);
                    if (playlist == null) {
                        return ResponseEntity.notFound().build();
                    }
                    playlist.setKeywords(normalizedKeywords);
                    playlist.touch();
                    playlistRepository.save(playlist);
                    publicContentCacheService.evictPublicContentCaches();
                    return ResponseEntity.ok(Map.of("success", true, "keywords", normalizedKeywords != null ? normalizedKeywords : List.of()));

                case "video":
                    Video video = videoRepository.findById(id).orElse(null);
                    if (video == null) {
                        return ResponseEntity.notFound().build();
                    }
                    video.setKeywords(normalizedKeywords);
                    video.touch();
                    videoRepository.save(video);
                    publicContentCacheService.evictPublicContentCaches();
                    return ResponseEntity.ok(Map.of("success", true, "keywords", normalizedKeywords != null ? normalizedKeywords : List.of()));

                default:
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid type: " + type));
            }
        } catch (Exception e) {
            log.error("Error updating keywords for {} {}", type, id, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update keywords. Please try again."));
        }
    }

    public static class KeywordsUpdateRequest {
        public List<String> keywords;
    }

    /**
     * Bulk reorder content items by updating their displayOrder.
     *
     * <p>Request body should contain a list of items with their new displayOrder values.
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 operations).
     * If total operations exceed 500, items are processed in multiple atomic batches.
     * Each batch either fully succeeds or fully fails, preventing partial commits within a batch.
     *
     * <h3>TOCTOU Race Condition Handling</h3>
     * <p>This endpoint uses a two-phase approach: validation then commit. A Time-Of-Check-Time-Of-Use
     * (TOCTOU) race condition exists where documents validated in Phase 1 may be deleted by another
     * process before Phase 2 commits. This is handled as follows:
     *
     * <ul>
     *   <li><b>400 Bad Request</b>: Returned when documents are confirmed missing. This includes:
     *       (1) documents not found during initial validation, and (2) documents deleted between
     *       validation and commit (detected via Firestore's "NOT_FOUND" error on update).</li>
     *   <li><b>503 Service Unavailable</b>: Returned for transient infrastructure failures
     *       (timeouts, network errors). Clients should retry with exponential backoff.</li>
     * </ul>
     *
     * <h3>Client Retry Semantics</h3>
     * <ul>
     *   <li>On 400: Do NOT retry automatically. Refresh the item list from the server and
     *       resubmit only existing items.</li>
     *   <li>On 503: Safe to retry. The operation is idempotent (setting displayOrder to the
     *       same value is a no-op).</li>
     * </ul>
     *
     * <h3>Example request:</h3>
     * <pre>
     * {
     *   "items": [
     *     {"type": "channel", "id": "abc123", "displayOrder": 0},
     *     {"type": "channel", "id": "def456", "displayOrder": 1},
     *     {"type": "playlist", "id": "ghi789", "displayOrder": 2}
     *   ]
     * }
     * </pre>
     */
    @PostMapping("/bulk/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkReorder(@Valid @RequestBody ReorderRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Early validation
        if (request == null || request.items == null || request.items.isEmpty()) {
            log.warn("Bulk reorder rejected: empty or null request");
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, List.of("Request must contain at least one item")));
        }

        // Validate individual items
        List<String> validationErrors = new ArrayList<>();
        for (int i = 0; i < request.items.size(); i++) {
            ReorderItem item = request.items.get(i);
            if (item == null) {
                validationErrors.add("Item at index " + i + " is null");
            } else {
                if (item.type == null || item.type.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank type");
                }
                if (item.id == null || item.id.isBlank()) {
                    validationErrors.add("Item at index " + i + " has null or blank id");
                }
                if (item.displayOrder == null || item.displayOrder < 0) {
                    validationErrors.add("Item at index " + i + " has invalid displayOrder (must be >= 0)");
                }
            }
        }

        if (!validationErrors.isEmpty()) {
            log.warn("Bulk reorder rejected: {} validation errors", validationErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validationErrors));
        }

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk reorder started: user={}, itemCount={}", username, request.items.size());

        // Group items by type for efficient batch updates
        Map<String, Integer> channelOrders = new HashMap<>();
        Map<String, Integer> playlistOrders = new HashMap<>();
        Map<String, Integer> videoOrders = new HashMap<>();

        for (ReorderItem item : request.items) {
            switch (item.type.toLowerCase()) {
                case "channel":
                    channelOrders.put(item.id, item.displayOrder);
                    break;
                case "playlist":
                    playlistOrders.put(item.id, item.displayOrder);
                    break;
                case "video":
                    videoOrders.put(item.id, item.displayOrder);
                    break;
                default:
                    validationErrors.add("Invalid type: " + item.type);
            }
        }

        if (!validationErrors.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validationErrors));
        }

        int totalOperations = channelOrders.size() + playlistOrders.size() + videoOrders.size();
        log.debug("Bulk reorder: {} channels, {} playlists, {} videos (total: {})",
                channelOrders.size(), playlistOrders.size(), videoOrders.size(), totalOperations);

        // Collect all reorder operations into a unified list for atomic batching
        List<ReorderOperation> allOperations = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : channelOrders.entrySet()) {
            allOperations.add(new ReorderOperation("channel", entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, Integer> entry : playlistOrders.entrySet()) {
            allOperations.add(new ReorderOperation("playlist", entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, Integer> entry : videoOrders.entrySet()) {
            allOperations.add(new ReorderOperation("video", entry.getKey(), entry.getValue()));
        }

        // ATOMIC REJECTION MODEL:
        // Phase 1: Validate ALL items exist without making any mutations
        // If ANY item doesn't exist, reject the entire request with 400
        ReorderValidationResult validation;
        try {
            validation = validateAllReorderOperations(allOperations);
        } catch (TimeoutException e) {
            log.error("Timeout during reorder validation for {} items", allOperations.size());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BulkActionResponse(0, List.of("Request timed out during validation. Please try again.")));
        }

        // If any items are invalid/missing, reject the entire request with 400
        // CRITICAL: No mutations have occurred at this point
        if (!validation.allValid) {
            log.warn("Bulk reorder rejected: {} items not found", validation.validationErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validation.validationErrors));
        }

        // Phase 2: Execute the reorder (all items validated to exist)
        ReorderExecutionResult execResult = executeValidatedReorderBatch(validation.validOperations);

        log.info("Bulk reorder completed: user={}, successCount={}, missingDocErrors={}, transientErrors={}",
                username, execResult.successCount,
                execResult.missingDocumentErrors.size(), execResult.transientErrors.size());

        // Evict caches if any operations succeeded
        if (execResult.successCount > 0) {
            publicContentCacheService.evictPublicContentCaches();
        }

        // Handle TOCTOU race condition: documents deleted between validation and commit
        // Return 400 Bad Request - client should refresh and resubmit with existing items only
        if (execResult.hasMissingDocumentErrors()) {
            log.warn("TOCTOU race: {} documents were deleted between validation and commit",
                    execResult.missingDocumentErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(execResult.successCount, execResult.getAllErrors()));
        }

        // Handle transient errors (timeouts, network issues)
        // Return 503 Service Unavailable - client can retry
        if (execResult.hasTransientErrors()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BulkActionResponse(execResult.successCount, execResult.transientErrors));
        }

        return ResponseEntity.ok(new BulkActionResponse(execResult.successCount, List.of()));
    }

    /**
     * Internal DTO for reorder operations.
     */
    private static class ReorderOperation {
        final String type;
        final String id;
        final int displayOrder;

        ReorderOperation(String type, String id, int displayOrder) {
            this.type = type;
            this.id = id;
            this.displayOrder = displayOrder;
        }
    }

    /**
     * Result class for atomic reorder validation.
     * Separates validation from execution for atomic rejection semantics.
     */
    private static class ReorderValidationResult {
        final List<ReorderOperation> validOperations;
        final List<String> validationErrors;
        final boolean allValid;

        ReorderValidationResult(List<ReorderOperation> validOperations, List<String> validationErrors) {
            this.validOperations = validOperations;
            this.validationErrors = validationErrors;
            this.allValid = validationErrors.isEmpty();
        }
    }

    /**
     * Result class for reorder execution phase.
     * Distinguishes between document-not-found errors (TOCTOU race) and transient errors.
     */
    private static class ReorderExecutionResult {
        final int successCount;
        final List<String> missingDocumentErrors;  // 400-worthy: documents deleted between validation and commit
        final List<String> transientErrors;        // 503-worthy: timeouts, network errors

        ReorderExecutionResult(int successCount, List<String> missingDocumentErrors, List<String> transientErrors) {
            this.successCount = successCount;
            this.missingDocumentErrors = missingDocumentErrors;
            this.transientErrors = transientErrors;
        }

        boolean hasMissingDocumentErrors() {
            return !missingDocumentErrors.isEmpty();
        }

        boolean hasTransientErrors() {
            return !transientErrors.isEmpty();
        }

        boolean hasAnyErrors() {
            return hasMissingDocumentErrors() || hasTransientErrors();
        }

        List<String> getAllErrors() {
            List<String> allErrors = new ArrayList<>(missingDocumentErrors);
            allErrors.addAll(transientErrors);
            return allErrors;
        }
    }

    /**
     * Validate all reorder operations exist in Firestore and are APPROVED.
     * This is the first phase of atomic rejection: if ANY item is invalid, reject the entire request.
     *
     * <p><b>APPROVED-ONLY ENFORCEMENT:</b> Only APPROVED items can have their displayOrder modified.
     * This prevents confusion where non-visible (PENDING/REJECTED) items affect the display order
     * of approved content shown to end users.
     *
     * @param operations All operations to validate
     * @return Validation result with valid operations and any errors
     */
    private ReorderValidationResult validateAllReorderOperations(List<ReorderOperation> operations)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<ReorderOperation> validOperations = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Process validation in chunks to respect Firestore limits
        for (int i = 0; i < operations.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, operations.size());
            List<ReorderOperation> batch = operations.subList(i, endIndex);

            // Build document references for batch read
            List<DocumentReference> docRefs = new ArrayList<>();
            for (ReorderOperation op : batch) {
                String collectionName = getCollectionName(op.type);
                docRefs.add(firestore.collection(collectionName).document(op.id));
            }

            // Batched read with timeout
            List<DocumentSnapshot> snapshots = firestore.getAll(docRefs.toArray(new DocumentReference[0]))
                    .get(timeoutProperties.getRead(), TimeUnit.SECONDS);

            // Check which documents exist and are APPROVED
            for (int j = 0; j < snapshots.size(); j++) {
                ReorderOperation op = batch.get(j);
                DocumentSnapshot snapshot = snapshots.get(j);
                if (!snapshot.exists()) {
                    errors.add(op.type + " not found: " + op.id);
                } else {
                    // Enforce approved-only reordering
                    String status = snapshot.getString("status");
                    if (!"APPROVED".equals(status)) {
                        errors.add(op.type + " " + op.id + " cannot be reordered: status is " + status + " (must be APPROVED)");
                    } else {
                        validOperations.add(op);
                    }
                }
            }
        }

        return new ReorderValidationResult(validOperations, errors);
    }

    /**
     * Execute reorder operations using Firestore atomic batch writes.
     * PRECONDITION: All operations have been validated to exist.
     *
     * <p>Uses UPDATE-ONLY semantics to prevent lost updates. Detects TOCTOU race conditions
     * where documents are deleted between validation and commit, distinguishing these from
     * transient infrastructure errors.
     *
     * @param operations List of validated operations to execute
     * @return ReorderExecutionResult with success count and categorized errors
     */
    private ReorderExecutionResult executeValidatedReorderBatch(List<ReorderOperation> operations) {
        if (operations.isEmpty()) {
            return new ReorderExecutionResult(0, List.of(), List.of());
        }

        int successCount = 0;
        List<String> missingDocumentErrors = new ArrayList<>();
        List<String> transientErrors = new ArrayList<>();

        // Process in chunks to respect Firestore batch limit (500 operations)
        for (int i = 0; i < operations.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, operations.size());
            List<ReorderOperation> batch = operations.subList(i, endIndex);

            WriteBatch writeBatch = firestore.batch();
            try {
                for (ReorderOperation op : batch) {
                    String collectionName = getCollectionName(op.type);
                    var docRef = firestore.collection(collectionName).document(op.id);
                    // UPDATE only touches specified fields, preserving all other data
                    writeBatch.update(docRef,
                            "displayOrder", op.displayOrder,
                            "updatedAt", com.google.cloud.Timestamp.now());
                }

                // Atomic commit with timeout - all items in this batch succeed or all fail
                writeBatch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                successCount += batch.size();
                log.debug("Reorder batch committed: {} items (batch {}/{})",
                        batch.size(), (i / FIRESTORE_BATCH_LIMIT) + 1,
                        (int) Math.ceil((double) operations.size() / FIRESTORE_BATCH_LIMIT));

            } catch (TimeoutException e) {
                log.error("Timeout committing reorder batch for {} items: items={}",
                        batch.size(), formatOperationsForLog(batch));
                for (ReorderOperation op : batch) {
                    transientErrors.add("Timeout committing " + op.type + " " + op.id);
                }
            } catch (ExecutionException e) {
                // Check if the root cause is a NOT_FOUND error (TOCTOU race condition)
                Throwable cause = e.getCause();
                if (isDocumentNotFoundError(cause)) {
                    // TOCTOU race: document was deleted between validation and commit
                    log.warn("TOCTOU race detected: document(s) deleted between validation and commit. " +
                            "Batch items: {}", formatOperationsForLog(batch));
                    for (ReorderOperation op : batch) {
                        missingDocumentErrors.add("Document deleted during operation: " + op.type + " " + op.id +
                                " (was valid during validation but missing at commit time)");
                    }
                } else {
                    // Other execution errors are transient
                    log.error("Reorder batch commit failed for {} items: {}. Items: {}",
                            batch.size(), e.getMessage(), formatOperationsForLog(batch));
                    for (ReorderOperation op : batch) {
                        transientErrors.add("Batch commit failed for " + op.type + " " + op.id + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                // Batch failed - Firestore ensures no partial commits within the batch
                log.error("Reorder batch commit failed for {} items: {}. Items: {}",
                        batch.size(), e.getMessage(), formatOperationsForLog(batch));
                for (ReorderOperation op : batch) {
                    transientErrors.add("Batch commit failed for " + op.type + " " + op.id + ": " + e.getMessage());
                }
            }
        }

        return new ReorderExecutionResult(successCount, missingDocumentErrors, transientErrors);
    }

    /**
     * Check if an exception indicates a document was not found (TOCTOU race condition).
     * Firestore throws StatusRuntimeException with NOT_FOUND status when updating a non-existent document.
     */
    private boolean isDocumentNotFoundError(Throwable cause) {
        if (cause == null) {
            return false;
        }
        // Check for Firestore/gRPC NOT_FOUND status
        if (cause instanceof io.grpc.StatusRuntimeException) {
            io.grpc.StatusRuntimeException grpcEx = (io.grpc.StatusRuntimeException) cause;
            return grpcEx.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND;
        }
        // Also check the message for "NOT_FOUND" as a fallback
        String message = cause.getMessage();
        if (message != null && message.contains("NOT_FOUND")) {
            return true;
        }
        // Check nested cause
        return isDocumentNotFoundError(cause.getCause());
    }

    /**
     * Format operations for logging - includes type and ID for each operation.
     */
    private String formatOperationsForLog(List<ReorderOperation> operations) {
        return operations.stream()
                .map(op -> op.type + ":" + op.id)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    // Bulk action DTOs

    public static class ReorderRequest {
        @NotNull(message = "Items list cannot be null")
        @NotEmpty(message = "Items list cannot be empty")
        @Valid
        public List<ReorderItem> items;
    }

    public static class ReorderItem {
        @NotNull(message = "Content type cannot be null")
        @NotBlank(message = "Content type cannot be blank")
        public String type; // channel, playlist, or video

        @NotNull(message = "Content ID cannot be null")
        @NotBlank(message = "Content ID cannot be blank")
        public String id;

        @NotNull(message = "Display order cannot be null")
        @Min(value = 0, message = "Display order must be >= 0")
        public Integer displayOrder;
    }

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
        @JsonIgnore
        public Set<String> failedKeys;

        public BulkActionResponse(int successCount, List<String> errors) {
            this(successCount, errors, Set.of());
        }

        public BulkActionResponse(int successCount, List<String> errors, Set<String> failedKeys) {
            this.successCount = successCount;
            this.errors = errors;
            this.failedKeys = failedKeys != null ? failedKeys : Set.of();
        }
    }
}

