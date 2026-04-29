package com.albunyaan.tube.service;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.dto.CategoryDto;
import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.HomeCategoryDto;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Service for public API endpoints (Android app).
 * Serves only approved/included content without authentication.
 */
@Service
public class PublicContentService {

    private static final Logger log = LoggerFactory.getLogger(PublicContentService.class);

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryContentOrderRepository orderRepository;
    private final Executor contentExecutor;

    public PublicContentService(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            CategoryRepository categoryRepository,
            CategoryContentOrderRepository orderRepository,
            @org.springframework.beans.factory.annotation.Qualifier("publicContentExecutor") Executor contentExecutor
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
        this.orderRepository = orderRepository;
        this.contentExecutor = contentExecutor;
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
               key = "(#type == null || #type.isBlank() ? 'HOME' : #type).toUpperCase(T(java.util.Locale).ROOT) + '-' + T(com.albunyaan.tube.service.PublicContentService).cacheCursorKey(#type, #cursor) + '-' + #limit + '-' + #category + '-' + #length + '-' + #date + '-' + #sort + '-q' + (#q == null ? '' : #q)",
               condition = "#q == null || #q.isBlank()")
    public CursorPageDto<ContentItemDto> getContent(
            String type, String cursor, int limit,
            String category, String length, String date, String sort, String q
    ) throws ExecutionException, InterruptedException, TimeoutException {

        // Null-safe: default to HOME if type is null or blank
        if (type == null || type.isBlank()) {
            type = "HOME";
        }

        // Resolve parent category → parent + children for aggregation.
        // If category is a parent, allCategoryIds includes it and all its subcategories.
        // This ensures "See All" and content list views show aggregated content.
        List<String> allCategoryIds = null;
        if (category != null && !category.isBlank()) {
            allCategoryIds = resolveAllCategoryIds(category);
        }

        TextFilter textFilter = new TextFilter(q);

        // For content types that support real cursor pagination
        switch (type.toUpperCase(Locale.ROOT)) {
            case "CHANNELS":
                if (textFilter.isActive()) {
                    return searchWithOffsetPagination(
                            getChannels(MAX_SEARCH_FETCH, category, allCategoryIds), textFilter, cursor, limit);
                }
                return getChannelsWithCursor(limit, category, allCategoryIds, cursor);
            case "PLAYLISTS":
                if (textFilter.isActive()) {
                    return searchWithOffsetPagination(
                            getPlaylists(MAX_SEARCH_FETCH, category, allCategoryIds), textFilter, cursor, limit);
                }
                return getPlaylistsWithCursor(limit, category, allCategoryIds, cursor);
            case "VIDEOS":
                if (textFilter.isActive()) {
                    return searchWithOffsetPagination(
                            getVideos(MAX_SEARCH_FETCH, category, allCategoryIds, length, date, sort),
                            textFilter, cursor, limit);
                }
                return getVideosWithCursor(limit, category, allCategoryIds, cursor, length, date, sort);
            case "HOME":
            default:
                List<ContentItemDto> items;
                if (allCategoryIds != null) {
                    // Category specified: use admin-defined sort order with offset pagination.
                    // Over-fetch by 1 to reliably detect whether a next page exists,
                    // avoiding phantom cursors when total items are an exact multiple of limit.
                    int offset = decodeCursorOffset(cursor);
                    items = getCategoryContentItems(allCategoryIds, limit + 1, offset);
                    boolean hasNext = items.size() > limit;
                    if (hasNext) {
                        items = items.subList(0, limit);
                    }
                    String nc = hasNext ? encodeCursor(offset + limit) : null;
                    return new CursorPageDto<>(items, nc);
                } else {
                    // No category filter: mix content types with default sorting.
                    // getMixedContent does not support offset/cursor, so no next page.
                    items = getMixedContent(limit, category, allCategoryIds);
                    return new CursorPageDto<>(items, null);
                }
        }
    }

    private List<ContentItemDto> getMixedContent(int limit, String category, List<String> allCategoryIds)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<ContentItemDto> mixed = new ArrayList<>();

        // Get mix of channels, playlists, and videos (roughly 1:2:3 ratio)
        int channelCount = limit / 6;
        int playlistCount = (limit / 6) * 2;
        int videoCount = limit - channelCount - playlistCount;

        mixed.addAll(getChannels(channelCount, category, allCategoryIds));
        mixed.addAll(getPlaylists(playlistCount, category, allCategoryIds));
        mixed.addAll(getVideos(videoCount, category, allCategoryIds, null, null, null));

        return mixed;
    }

    private List<ContentItemDto> getChannels(int limit, String category, List<String> allCategoryIds)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<Channel> channels;

        // Use repository methods with limits for better performance
        if (allCategoryIds != null && allCategoryIds.size() > 1) {
            // Parent category with subcategories: aggregate across all
            channels = channelRepository.findByCategoryIds(allCategoryIds, limit * 2);
        } else if (category != null && !category.isBlank()) {
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
     * When allCategoryIds has multiple entries (parent + children), falls back to
     * non-cursor aggregation since cursor pagination across multiple categories is complex.
     */
    private CursorPageDto<ContentItemDto> getChannelsWithCursor(int limit, String category,
                                                                 List<String> allCategoryIds, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Multi-category aggregation: no cursor support (getChannels lacks offset)
        if (allCategoryIds != null && allCategoryIds.size() > 1) {
            List<ContentItemDto> items = getChannels(limit, category, allCategoryIds);
            return new CursorPageDto<>(items, null);
        }

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

    private List<ContentItemDto> getPlaylists(int limit, String category, List<String> allCategoryIds)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<Playlist> playlists;

        // Use repository methods with limits for better performance
        if (allCategoryIds != null && allCategoryIds.size() > 1) {
            playlists = playlistRepository.findByCategoryIds(allCategoryIds, limit * 2);
        } else if (category != null && !category.isBlank()) {
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
     * When allCategoryIds has multiple entries, falls back to non-cursor aggregation.
     */
    private CursorPageDto<ContentItemDto> getPlaylistsWithCursor(int limit, String category,
                                                                  List<String> allCategoryIds, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Multi-category aggregation: no cursor support (getPlaylists lacks offset)
        if (allCategoryIds != null && allCategoryIds.size() > 1) {
            List<ContentItemDto> items = getPlaylists(limit, category, allCategoryIds);
            return new CursorPageDto<>(items, null);
        }

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
     * When allCategoryIds has multiple entries, falls back to non-cursor aggregation.
     */
    private CursorPageDto<ContentItemDto> getVideosWithCursor(int limit, String category,
                                                              List<String> allCategoryIds, String cursor,
                                                              String length, String date, String sort)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Multi-category aggregation: no cursor support (getVideos lacks offset)
        if (allCategoryIds != null && allCategoryIds.size() > 1) {
            List<ContentItemDto> items = getVideos(limit, category, allCategoryIds, length, date, sort);
            return new CursorPageDto<>(items, null);
        }

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
        List<ContentItemDto> items = getVideos(limit, category, allCategoryIds, length, date, sort);
        String nextCursor = items.size() >= limit ? encodeCursor(limit) : null;
        return new CursorPageDto<>(items, nextCursor);
    }

    private List<ContentItemDto> getVideos(int limit, String category, List<String> allCategoryIds,
                                           String length, String date, String sort) throws ExecutionException, InterruptedException, TimeoutException {
        List<Video> videos;

        // Use repository methods with limits for better performance
        // Fetch more than needed to account for filters
        int fetchLimit = limit * 3; // 3x buffer for filters
        if (allCategoryIds != null && allCategoryIds.size() > 1) {
            videos = videoRepository.findByCategoryIds(allCategoryIds, fetchLimit);
        } else if (category != null && !category.isBlank()) {
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
            switch (sort.toUpperCase(Locale.ROOT)) {
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

    @Cacheable(value = CacheConfig.CACHE_PUBLIC_CONTENT, key = "'active-categories'")
    public List<CategoryDto> getCategories() throws ExecutionException, InterruptedException, TimeoutException {
        // Collect all categoryIds that have at least one approved content item
        Set<String> activeCategoryIds = new HashSet<>();

        for (Channel ch : channelRepository.findByStatus("APPROVED")) {
            if (ch.getCategoryIds() != null) activeCategoryIds.addAll(ch.getCategoryIds());
        }
        for (Playlist pl : playlistRepository.findByStatus("APPROVED")) {
            if (pl.getCategoryIds() != null) activeCategoryIds.addAll(pl.getCategoryIds());
        }
        for (Video v : videoRepository.findByStatus("APPROVED")) {
            if (v.getCategoryIds() != null) activeCategoryIds.addAll(v.getCategoryIds());
        }

        // Expand to include ancestor categories so parent categories remain navigable
        // even when only their children have direct approved content
        List<Category> allCategories = categoryRepository.findAll();
        Map<String, Category> categoryById = allCategories.stream()
                .collect(Collectors.toMap(Category::getId, c -> c, (a, b) -> a));

        Set<String> visibleCategoryIds = new HashSet<>(activeCategoryIds);
        for (String id : new HashSet<>(activeCategoryIds)) {
            String parentId = categoryById.containsKey(id) ? categoryById.get(id).getParentCategoryId() : null;
            while (parentId != null && visibleCategoryIds.add(parentId)) {
                Category parent = categoryById.get(parentId);
                parentId = parent != null ? parent.getParentCategoryId() : null;
            }
        }

        // Return categories with approved content or that are ancestors of such categories
        return allCategories.stream()
                .filter(cat -> visibleCategoryIds.contains(cat.getId()))
                .map(this::toCategoryDto)
                .collect(Collectors.toList());
    }

    /**
     * Get home feed with paginated category sections.
     *
     * Returns categories in displayOrder, each containing up to contentLimit items.
     * Uses category_content_order for admin-defined sort, falls back to default
     * sort (channels by subscribers, playlists by itemCount, videos by uploadedAt)
     * if no sort order exists for a category.
     *
     * @param cursor Base64-encoded displayOrder of last category (null for first page)
     * @param categoryLimit Max categories per page (default 5, max 10)
     * @param contentLimit Max items per category (default 10, max 20)
     * @return Paginated home feed
     */
    @Cacheable(value = CacheConfig.CACHE_PUBLIC_CONTENT,
               key = "'home-' + T(com.albunyaan.tube.service.PublicContentService).normalizeCursor(#cursor) + '-' + #categoryLimit + '-' + #contentLimit + '-' + #category")
    public CursorPageDto<HomeCategoryDto> getHomeFeed(String cursor, int categoryLimit, int contentLimit, String category)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Fetch all categories to build parent→children map.
        // Categories are admin-managed and typically < 50, so findAll() is bounded.
        List<Category> allCategories = new ArrayList<>(categoryRepository.findAll());

        // Build parent → children mapping for subcategory aggregation
        Map<String, List<String>> childrenMap = new HashMap<>();
        for (Category cat : allCategories) {
            if (cat.getParentCategoryId() != null) {
                childrenMap.computeIfAbsent(cat.getParentCategoryId(), k -> new ArrayList<>())
                        .add(cat.getId());
            }
        }

        // Filter to parent (top-level) categories only for home feed display.
        // Subcategory content is aggregated under its parent section.
        List<Category> parentCategories = allCategories.stream()
                .filter(cat -> cat.getParentCategoryId() == null)
                .collect(Collectors.toList());

        // If category filter is specified, only include that category.
        // Handle both parent and subcategory IDs.
        if (category != null && !category.isBlank()) {
            boolean isParent = parentCategories.stream().anyMatch(cat -> category.equals(cat.getId()));
            if (isParent) {
                List<String> children = childrenMap.get(category);
                if (children != null && !children.isEmpty()) {
                    // Parent has subcategories: show each as its own section
                    Set<String> childSet = new HashSet<>(children);
                    List<Category> subcats = allCategories.stream()
                            .filter(cat -> childSet.contains(cat.getId()))
                            .collect(Collectors.toList());
                    parentCategories.clear();
                    parentCategories.addAll(subcats);
                } else {
                    // Leaf parent (no children): keep as single section
                    parentCategories.removeIf(cat -> !category.equals(cat.getId()));
                }
            } else {
                // Subcategory filter: show the subcategory itself as a standalone section
                Category subcategory = allCategories.stream()
                        .filter(cat -> category.equals(cat.getId()))
                        .findFirst()
                        .orElse(null);
                parentCategories.clear();
                if (subcategory != null) {
                    parentCategories.add(subcategory);
                } else {
                    // Unknown category ID — return empty without caching garbage
                    return new CursorPageDto<>(List.of(), null);
                }
            }
        }

        parentCategories.sort((a, b) -> {
            int orderA = a.getDisplayOrder() != null ? a.getDisplayOrder() : Integer.MAX_VALUE;
            int orderB = b.getDisplayOrder() != null ? b.getDisplayOrder() : Integer.MAX_VALUE;
            if (orderA != orderB) return Integer.compare(orderA, orderB);
            return a.getId().compareTo(b.getId());
        });

        // Apply cursor: skip categories with displayOrder <= cursor value
        int startAfterOrder = -1;
        String startAfterId = null;
        if (cursor != null && !cursor.isEmpty()) {
            try {
                String decoded = new String(Base64.getDecoder().decode(cursor));
                // Cursor format: "displayOrder:categoryId"
                String[] parts = decoded.split(":", 2);
                startAfterOrder = Integer.parseInt(parts[0]);
                startAfterId = parts.length > 1 ? parts[1] : null;
            } catch (Exception e) {
                log.warn("Invalid home feed cursor: {}", cursor);
            }
        }

        // Filter categories past the cursor
        List<Category> remaining = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isEmpty());
        for (Category cat : parentCategories) {
            if (pastCursor) {
                remaining.add(cat);
            } else {
                int order = cat.getDisplayOrder() != null ? cat.getDisplayOrder() : Integer.MAX_VALUE;
                if (order > startAfterOrder) {
                    pastCursor = true;
                    remaining.add(cat);
                } else if (order == startAfterOrder && startAfterId != null && cat.getId().compareTo(startAfterId) > 0) {
                    pastCursor = true;
                    remaining.add(cat);
                }
            }
        }

        // Build sections for up to categoryLimit + 1 (to detect hasMore)
        List<HomeCategoryDto> sections = new ArrayList<>();
        int fetchLimit = categoryLimit + 1;

        // Parallelize in batches: scan categories until we have enough non-empty sections.
        // Each batch launches parallel content + count queries. Empty categories are skipped
        // and we continue to the next batch, ensuring sparse categories don't truncate the page.
        record CategoryFuture(Category category,
                              List<String> allCategoryIds,
                              CompletableFuture<List<ContentItemDto>> itemsFuture,
                              CompletableFuture<Long> countFuture) {}

        int index = 0;
        int failureCount = 0;
        while (sections.size() < fetchLimit && index < remaining.size()) {
            int needed = fetchLimit - sections.size();
            // Over-fetch by 3x to account for empty categories in this batch
            int batchSize = Math.min(remaining.size() - index, needed * 3);
            List<Category> batch = remaining.subList(index, index + batchSize);
            index += batchSize;

            // Launch all content + count queries for this batch in parallel
            List<CategoryFuture> futures = new ArrayList<>();
            for (Category cat : batch) {
                // Resolve parent + all child category IDs for aggregation
                List<String> allIds = new ArrayList<>();
                allIds.add(cat.getId());
                List<String> children = childrenMap.get(cat.getId());
                if (children != null) {
                    allIds.addAll(children);
                }

                CompletableFuture<List<ContentItemDto>> itemsFuture =
                        asyncSupply(() -> getCategoryContentItems(allIds, contentLimit, 0));
                CompletableFuture<Long> countFuture =
                        asyncSupply(() -> orderRepository.countByCategoryIds(allIds));
                futures.add(new CategoryFuture(cat, allIds, itemsFuture, countFuture));
            }

            // Collect results in order, skipping empty or failed categories
            for (CategoryFuture cf : futures) {
                if (sections.size() >= fetchLimit) break;

                List<ContentItemDto> items;
                try {
                    items = cf.itemsFuture().join();
                } catch (CompletionException e) {
                    Throwable cause = e.getCause();
                    Throwable root = cause != null && cause.getCause() != null ? cause.getCause() : cause;
                    log.error("Error loading content for category {}: {}", cf.category().getId(),
                            root != null ? root.getMessage() : e.getMessage());
                    failureCount++;
                    continue;
                }
                if (items.isEmpty()) continue;

                long totalCount;
                try {
                    totalCount = cf.countFuture().join();
                } catch (CompletionException e) {
                    totalCount = 0;
                }
                if (totalCount == 0) {
                    totalCount = items.size();
                }

                Category cat = cf.category();
                String slug = cat.getSlug() != null ? cat.getSlug() :
                        cat.getName().toLowerCase().replace(" ", "-");

                sections.add(new HomeCategoryDto(
                        cat.getId(),
                        cat.getName(),
                        slug,
                        cat.getLocalizedNames(),
                        cat.getDisplayOrder(),
                        cat.getIcon(),
                        items,
                        (int) totalCount
                ));
            }
        }

        // Prevent caching a degraded empty response when failures occurred
        if (sections.isEmpty() && failureCount > 0) {
            throw new ExecutionException("All category content fetches failed (" + failureCount + " failures)", null);
        }

        // Build cursor from last included category
        boolean hasMore = sections.size() > categoryLimit;
        if (hasMore) {
            sections = new ArrayList<>(sections.subList(0, categoryLimit));
        }

        String nextCursor = null;
        if (hasMore && !sections.isEmpty()) {
            HomeCategoryDto last = sections.get(sections.size() - 1);
            int order = last.getDisplayOrder() != null ? last.getDisplayOrder() : Integer.MAX_VALUE;
            nextCursor = Base64.getEncoder().encodeToString((order + ":" + last.getId()).getBytes());
        }

        return new CursorPageDto<>(sections, nextCursor);
    }

    /**
     * Resolve all category IDs for a given category: itself + any child subcategories.
     * Used by getContent() to aggregate subcategory content when filtering by a parent.
     */
    private List<String> resolveAllCategoryIds(String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<String> allIds = new ArrayList<>();
        allIds.add(categoryId);

        List<Category> children = categoryRepository.findByParentId(categoryId);
        for (Category child : children) {
            allIds.add(child.getId());
        }
        return allIds;
    }

    /**
     * Get content items for one or more categories (parent + subcategories),
     * using admin-defined sort order if available, falling back to default sort.
     *
     * @param categoryIds List of category IDs (parent first, then children)
     * @param limit Max items to return
     * @param offset Number of valid items to skip (for pagination)
     */
    private List<ContentItemDto> getCategoryContentItems(List<String> categoryIds, int limit, int offset)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Build subcategory name map when serving a parent category with children.
        Map<String, String> subcatNameMap = null;
        if (categoryIds.size() > 1) {
            String parentId = categoryIds.get(0);
            try {
                List<Category> children = categoryRepository.findByParentId(parentId);
                subcatNameMap = new HashMap<>();
                for (Category child : children) {
                    subcatNameMap.put(child.getId(), child.getName());
                }
            } catch (Exception e) {
                log.warn("Could not fetch subcategories for {}: {}", parentId, e.getMessage());
            }
        }

        int totalNeeded = offset + limit;
        List<ContentItemDto> orderedItems = new ArrayList<>();
        Set<String> seenContentKeys = new HashSet<>();

        // Prefer the parent category's explicit order when present. Child-category sort rows
        // should not override a parent's aggregate order on public "See All" and home screens.
        List<CategoryContentOrder> orderEntries = getEffectiveOrderEntries(categoryIds);
        if (!orderEntries.isEmpty()) {
            List<String> channelIds = new ArrayList<>();
            List<String> playlistIds = new ArrayList<>();
            List<String> videoIds = new ArrayList<>();

            for (CategoryContentOrder entry : orderEntries) {
                switch (entry.getContentType()) {
                    case "channel": channelIds.add(entry.getContentId()); break;
                    case "playlist": playlistIds.add(entry.getContentId()); break;
                    case "video": videoIds.add(entry.getContentId()); break;
                    default: break;
                }
            }

            Map<String, Channel> channelMap = channelRepository.findAllByIds(channelIds);
            Map<String, Playlist> playlistMap = playlistRepository.findAllByIds(playlistIds);
            Map<String, Video> videoMap = videoRepository.findAllByIds(videoIds);

            for (CategoryContentOrder entry : orderEntries) {
                if (orderedItems.size() >= totalNeeded) break;

                ContentItemDto dto = resolveFromBatchMaps(entry, channelMap, playlistMap, videoMap);
                if (dto == null) {
                    continue;
                }

                String contentKey = toContentKey(entry.getContentType(), entry.getContentId());
                if (!seenContentKeys.add(contentKey)) {
                    continue;
                }

                enrichSubcategoryName(dto, entry, channelMap, playlistMap, videoMap, subcatNameMap);
                orderedItems.add(dto);
            }
        }

        // Append any approved content that is missing from the stored order rows.
        // This keeps pagination complete even when category_content_order is stale or partial.
        int missingItemsNeeded = Math.max(0, totalNeeded - orderedItems.size());
        if (missingItemsNeeded > 0) {
            orderedItems.addAll(getFallbackCategoryContentItems(
                    categoryIds,
                    missingItemsNeeded,
                    subcatNameMap,
                    seenContentKeys
            ));
        }

        if (offset >= orderedItems.size()) {
            return new ArrayList<>();
        }
        int end = Math.min(offset + limit, orderedItems.size());
        return new ArrayList<>(orderedItems.subList(offset, end));
    }

    private List<CategoryContentOrder> getEffectiveOrderEntries(List<String> categoryIds)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }

        if (categoryIds.size() > 1) {
            List<CategoryContentOrder> parentEntries =
                    orderRepository.findByCategoryIdOrderByPosition(categoryIds.get(0));
            if (parentEntries != null && !parentEntries.isEmpty()) {
                return parentEntries;
            }
        }

        List<CategoryContentOrder> mergedEntries =
                orderRepository.findByCategoryIdsOrderByPosition(categoryIds);
        return mergedEntries != null ? mergedEntries : List.of();
    }

    private List<ContentItemDto> getFallbackCategoryContentItems(
            List<String> categoryIds,
            int needed,
            Map<String, String> subcatNameMap,
            Set<String> seenContentKeys)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (needed <= 0) {
            return List.of();
        }

        List<ContentItemDto> items = new ArrayList<>();
        int fetchLimit = Math.max(needed, 1);

        List<Channel> channels = channelRepository.findByCategoryIds(categoryIds, fetchLimit);
        if (channels == null) {
            channels = List.of();
        }
        for (Channel channel : channels) {
            if (!isApproved(channel) || !isAvailable(channel)) {
                continue;
            }
            if (!seenContentKeys.add(toContentKey("channel", channel.getId()))) {
                continue;
            }

            ContentItemDto dto = toDto(channel);
            enrichSubcategoryName(dto, channel.getCategoryIds(), subcatNameMap);
            items.add(dto);
            if (items.size() >= needed) {
                return items;
            }
        }

        List<Playlist> playlists = playlistRepository.findByCategoryIds(categoryIds, fetchLimit);
        if (playlists == null) {
            playlists = List.of();
        }
        for (Playlist playlist : playlists) {
            if (!isApproved(playlist) || !isAvailable(playlist)) {
                continue;
            }
            if (!seenContentKeys.add(toContentKey("playlist", playlist.getId()))) {
                continue;
            }

            ContentItemDto dto = toDto(playlist);
            enrichSubcategoryName(dto, playlist.getCategoryIds(), subcatNameMap);
            items.add(dto);
            if (items.size() >= needed) {
                return items;
            }
        }

        List<Video> videos = videoRepository.findByCategoryIds(categoryIds, fetchLimit);
        if (videos == null) {
            videos = List.of();
        }
        for (Video video : videos) {
            if (!isApproved(video) || !isAvailable(video)) {
                continue;
            }
            if (!seenContentKeys.add(toContentKey("video", video.getId()))) {
                continue;
            }

            ContentItemDto dto = toDto(video);
            enrichSubcategoryName(dto, video.getCategoryIds(), subcatNameMap);
            items.add(dto);
            if (items.size() >= needed) {
                return items;
            }
        }

        return items;
    }

    private String toContentKey(String contentType, String contentId) {
        return contentType + ":" + contentId;
    }

    /**
     * Resolve a content order entry to DTO using pre-fetched batch maps.
     * Returns null if content not found, not approved, or unavailable.
     */
    private ContentItemDto resolveFromBatchMaps(
            CategoryContentOrder entry,
            Map<String, Channel> channelMap,
            Map<String, Playlist> playlistMap,
            Map<String, Video> videoMap) {
        switch (entry.getContentType()) {
            case "channel":
                Channel ch = channelMap.get(entry.getContentId());
                if (ch != null && isApproved(ch) && isAvailable(ch)) return toDto(ch);
                return null;
            case "playlist":
                Playlist pl = playlistMap.get(entry.getContentId());
                if (pl != null && isApproved(pl) && isAvailable(pl)) return toDto(pl);
                return null;
            case "video":
                Video v = videoMap.get(entry.getContentId());
                if (v != null && isApproved(v) && isAvailable(v)) return toDto(v);
                return null;
            default:
                return null;
        }
    }

    /**
     * Enrich a DTO with subcategory name using the content's categoryIds from batch maps.
     * Used by the admin-ordered path where we have the CategoryContentOrder entry.
     */
    private void enrichSubcategoryName(ContentItemDto dto, CategoryContentOrder entry,
                                        Map<String, Channel> channelMap,
                                        Map<String, Playlist> playlistMap,
                                        Map<String, Video> videoMap,
                                        Map<String, String> subcatNameMap) {
        if (subcatNameMap == null || subcatNameMap.isEmpty()) return;
        List<String> catIds = null;
        switch (entry.getContentType()) {
            case "channel":
                Channel ch = channelMap.get(entry.getContentId());
                if (ch != null) catIds = ch.getCategoryIds();
                break;
            case "playlist":
                Playlist pl = playlistMap.get(entry.getContentId());
                if (pl != null) catIds = pl.getCategoryIds();
                break;
            case "video":
                Video v = videoMap.get(entry.getContentId());
                if (v != null) catIds = v.getCategoryIds();
                break;
        }
        enrichSubcategoryName(dto, catIds, subcatNameMap);
    }

    /**
     * Set the DTO's category to the matching subcategory name, if the content
     * belongs to one of the subcategories in the map.
     */
    private void enrichSubcategoryName(ContentItemDto dto, List<String> contentCategoryIds,
                                        Map<String, String> subcatNameMap) {
        if (dto == null || subcatNameMap == null || subcatNameMap.isEmpty() || contentCategoryIds == null) return;
        for (String catId : contentCategoryIds) {
            String subcatName = subcatNameMap.get(catId);
            if (subcatName != null) {
                dto.setCategory(subcatName);
                return;
            }
        }
    }

    private CategoryDto toCategoryDto(Category category) {
        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getSlug() != null ? category.getSlug() : category.getName().toLowerCase().replace(" ", "-"),
                category.getParentId(),
                category.getDisplayOrder(),
                category.getLocalizedNames(),
                category.getIcon()
        );
    }

    public Object getChannelDetails(String channelId) throws ExecutionException, InterruptedException, TimeoutException {
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

    public Object getPlaylistDetails(String playlistId) throws ExecutionException, InterruptedException, TimeoutException {
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

    public Video getVideoDetails(String videoId) throws ExecutionException, InterruptedException, TimeoutException {
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
    public List<ContentItemDto> search(String query, String type, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        // Normalize query for matching
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);

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
            throws ExecutionException, InterruptedException, TimeoutException {
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
            throws ExecutionException, InterruptedException, TimeoutException {
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
            throws ExecutionException, InterruptedException, TimeoutException {
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
            throws ExecutionException, InterruptedException, TimeoutException {
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
            throws ExecutionException, InterruptedException, TimeoutException {
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
        if (title != null && title.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
            return true;
        }
        // Check keywords
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword != null && keyword.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
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
        switch (length.toUpperCase(Locale.ROOT)) {
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
                .atZone(ZoneId.systemDefault()).toLocalDateTime();

        switch (date.toUpperCase(Locale.ROOT)) {
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
            video.getUploadedAt().toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() :
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

    /**
     * Normalize a Base64-encoded cursor to a stable string for use in cache keys.
     * Handles two cursor formats:
     * - Home feed: "displayOrder:categoryId" (e.g. "5:abc123") → extracts displayOrder
     * - Content: plain integer offset (e.g. "10") → parsed directly
     * Prevents cache pollution: arbitrary/invalid cursors all normalize to "0".
     * Must be public+static so Spring SpEL can reference it in @Cacheable key expressions.
     */
    public static String normalizeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) return "0";
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            if (decoded.contains(":")) {
                // Home feed cursors: "displayOrder:categoryId"
                // Include both parts in the key so two categories with the same displayOrder
                // but different IDs don't collide (e.g. "5:abc" vs "5:xyz").
                String[] parts = decoded.split(":", 2);
                int order = Math.max(0, Integer.parseInt(parts[0]));
                return order + "-" + parts[1].hashCode();
            }
            return String.valueOf(Math.max(0, Integer.parseInt(decoded)));
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * Type-aware cache key for cursors. HOME uses numeric offset cursors that should be
     * normalized to prevent cache pollution. CHANNELS/PLAYLISTS/VIDEOS use opaque Firestore
     * cursors (JSON/Base64) that must be preserved as-is; normalizing them would collapse
     * different pages into the same cache entry.
     *
     * Must be public+static so Spring SpEL can reference it in @Cacheable key expressions.
     */
    public static String cacheCursorKey(String type, String cursor) {
        if (cursor == null || cursor.isEmpty()) return "0";
        String resolvedType = (type == null || type.isBlank()) ? "HOME" : type.toUpperCase(Locale.ROOT);
        if ("HOME".equals(resolvedType)) {
            return normalizeCursor(cursor);
        }
        // Opaque cursor for CHANNELS/PLAYLISTS/VIDEOS — return as-is
        return cursor;
    }

    private static final int MAX_SEARCH_FETCH = 1000;

    private CursorPageDto<ContentItemDto> searchWithOffsetPagination(
            List<ContentItemDto> allItems, TextFilter filter, String cursor, int limit) {
        List<ContentItemDto> filtered = filter.apply(allItems);
        int offset = decodeCursorOffset(cursor);
        int from = Math.min(offset, filtered.size());
        int to = Math.min(offset + limit, filtered.size());
        List<ContentItemDto> page = filtered.subList(from, to);
        boolean hasNext = to < filtered.size();
        return new CursorPageDto<>(page, hasNext ? encodeCursor(offset + limit) : null);
    }

    private String encodeCursor(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes());
    }

    private int decodeCursorOffset(String cursor) {
        if (cursor == null || cursor.isEmpty()) return 0;
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            return Math.max(0, Integer.parseInt(decoded));
        } catch (Exception e) {
            log.warn("Invalid content cursor: {}", cursor);
            return 0;
        }
    }

    /** Supplier that can throw checked exceptions. */
    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    /** Wrap a checked-exception supplier into a CompletableFuture using the bounded executor. */
    private <T> CompletableFuture<T> asyncSupply(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
            }
        }, contentExecutor);
    }

    /** Package-visible for unit testing. Case-insensitive text filter across DTO fields. */
    static class TextFilter {
        private final String lower;

        TextFilter(String q) {
            this.lower = (q == null) ? "" : q.trim().toLowerCase(Locale.ROOT);
        }

        boolean isActive() { return !lower.isEmpty(); }

        boolean matches(ContentItemDto dto) {
            return containsLower(dto.getName())
                    || containsLower(dto.getTitle())
                    || containsLower(dto.getDescription());
        }

        List<ContentItemDto> apply(List<ContentItemDto> items) {
            if (!isActive()) return items;
            return items.stream().filter(this::matches).collect(Collectors.toList());
        }

        private boolean containsLower(String field) {
            return field != null && field.toLowerCase(Locale.ROOT).contains(lower);
        }
    }
}
