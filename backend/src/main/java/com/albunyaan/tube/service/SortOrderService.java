package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CategorySortDto;
import com.albunyaan.tube.dto.ContentSortDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Service for managing sort order of categories and content within categories.
 *
 * Implements insert-and-shift logic: when an item is moved to position N,
 * items at N and beyond shift down by one. This is an insert, not a swap.
 */
@Service
public class SortOrderService {

    private static final Logger log = LoggerFactory.getLogger(SortOrderService.class);

    private final CategoryRepository categoryRepository;
    private final CategoryContentOrderRepository orderRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final PublicContentCacheService cacheService;

    public SortOrderService(
            CategoryRepository categoryRepository,
            CategoryContentOrderRepository orderRepository,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            PublicContentCacheService cacheService
    ) {
        this.categoryRepository = categoryRepository;
        this.orderRepository = orderRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.cacheService = cacheService;
    }

    // ======================== CATEGORY SORT ORDER ========================

    /**
     * Get all categories in sort order with content counts for the admin sorting page.
     */
    public List<CategorySortDto> getCategorySortOrder()
            throws ExecutionException, InterruptedException, TimeoutException {
        List<Category> categories = categoryRepository.findAll();

        // Batch-count: single query to get all counts instead of N individual count queries
        Map<String, Long> countsByCategory = orderRepository.countAllGroupedByCategoryId();

        List<CategorySortDto> result = new ArrayList<>();
        for (Category cat : categories) {
            long count = countsByCategory.getOrDefault(cat.getId(), 0L);
            result.add(new CategorySortDto(
                    cat.getId(),
                    cat.getName(),
                    cat.getIcon(),
                    cat.getLocalizedNames(),
                    cat.getDisplayOrder(),
                    (int) count
            ));
        }

        return result;
    }

    /**
     * Reorder a category using insert-and-shift logic.
     *
     * 1. Fetch all categories ordered by displayOrder
     * 2. Remove target from list
     * 3. Insert at newPosition (clamped to valid range)
     * 4. Renumber all (0-indexed)
     * 5. Batch-write updated displayOrder values
     * 6. Evict caches
     */
    public List<CategorySortDto> reorderCategory(String categoryId, int newPosition)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<Category> categories = categoryRepository.findAll();

        // Find and remove the target category
        Category target = null;
        List<Category> remaining = new ArrayList<>();
        for (Category cat : categories) {
            if (cat.getId().equals(categoryId)) {
                target = cat;
            } else {
                remaining.add(cat);
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Category not found: " + categoryId);
        }

        // Clamp newPosition to valid range
        int clampedPosition = Math.max(0, Math.min(newPosition, remaining.size()));

        // Insert at new position
        remaining.add(clampedPosition, target);

        // Renumber all categories (0-indexed) and batch save
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setDisplayOrder(i);
        }
        categoryRepository.batchSave(remaining);

        cacheService.evictPublicContentCaches();
        log.info("Reordered category {} to position {}", categoryId, clampedPosition);

        return getCategorySortOrder();
    }

    // ======================== CONTENT SORT ORDER WITHIN CATEGORY ========================

    /**
     * Get content items within a category in sort order for the admin sorting page.
     * Joins order entries with actual content documents for display info.
     * Initializes sort order if none exists yet.
     */
    public List<ContentSortDto> getContentSortOrder(String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<CategoryContentOrder> orderEntries = orderRepository.findByCategoryIdOrderByPosition(categoryId);

        // If no order entries exist, initialize from existing approved content.
        // Re-check after initialization to guard against concurrent calls both seeing empty.
        if (orderEntries.isEmpty()) {
            initializeCategoryContentOrder(categoryId);
            orderEntries = orderRepository.findByCategoryIdOrderByPosition(categoryId);
        }

        List<ContentSortDto> result = new ArrayList<>();
        for (CategoryContentOrder entry : orderEntries) {
            try {
                ContentSortDto dto = resolveContentInfo(entry);
                if (dto != null) {
                    result.add(dto);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("Failed to resolve content info for {} {} in category {}: {}",
                        entry.getContentType(), entry.getContentId(), categoryId, e.getMessage());
            }
        }

        return result;
    }

    /**
     * Reorder content within a category using insert-and-shift logic.
     */
    public List<ContentSortDto> reorderContentInCategory(
            String categoryId, String contentId, String contentType, int newPosition)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<CategoryContentOrder> entries = orderRepository.findByCategoryIdOrderByPosition(categoryId);

        // Find and remove the target entry
        CategoryContentOrder target = null;
        List<CategoryContentOrder> remaining = new ArrayList<>();
        for (CategoryContentOrder entry : entries) {
            if (entry.getContentId().equals(contentId) && entry.getContentType().equals(contentType)) {
                target = entry;
            } else {
                remaining.add(entry);
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Content not found in category: " + contentType + " " + contentId);
        }

        // Clamp newPosition to valid range
        int clampedPosition = Math.max(0, Math.min(newPosition, remaining.size()));

        // Insert at new position
        remaining.add(clampedPosition, target);

        // Renumber all entries (0-indexed)
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setPosition(i);
        }

        // Batch save all updated entries
        orderRepository.batchSave(remaining);

        cacheService.evictPublicContentCaches();
        log.info("Reordered {} {} to position {} in category {}",
                contentType, contentId, clampedPosition, categoryId);

        return getContentSortOrder(categoryId);
    }

    /**
     * Initialize sort order entries for a category from existing approved content.
     * Called when the admin opens the sorting page for a category that has never been sorted.
     */
    public void initializeCategoryContentOrder(String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Re-check if entries were created by a concurrent call
        List<CategoryContentOrder> existing = orderRepository.findByCategoryIdOrderByPosition(categoryId);
        if (!existing.isEmpty()) {
            log.debug("Sort order for category {} already initialized ({} entries), skipping", categoryId, existing.size());
            return;
        }

        List<CategoryContentOrder> entries = new ArrayList<>();
        int position = 0;

        // Bounded fetch: limit to 500 per content type to prevent unbounded reads
        int initLimit = 500;

        // Add channels in this category, sorted by subscribers desc as default
        List<Channel> channels = new ArrayList<>(channelRepository.findByCategoryId(categoryId, initLimit));
        channels.sort((a, b) -> {
            long sa = a.getSubscribers() != null ? a.getSubscribers() : 0;
            long sb = b.getSubscribers() != null ? b.getSubscribers() : 0;
            return Long.compare(sb, sa);
        });
        for (Channel ch : channels) {
            entries.add(new CategoryContentOrder(categoryId, ch.getId(), "channel", position++));
        }

        // Add playlists in this category, sorted by itemCount desc as default
        List<Playlist> playlists = new ArrayList<>(playlistRepository.findByCategoryId(categoryId, initLimit));
        playlists.sort((a, b) -> {
            int ia = a.getItemCount() != null ? a.getItemCount() : 0;
            int ib = b.getItemCount() != null ? b.getItemCount() : 0;
            return Integer.compare(ib, ia);
        });
        for (Playlist pl : playlists) {
            entries.add(new CategoryContentOrder(categoryId, pl.getId(), "playlist", position++));
        }

        // Add videos in this category, sorted by uploadedAt desc as default
        List<Video> videos = new ArrayList<>(videoRepository.findByCategoryId(categoryId, initLimit));
        videos.sort((a, b) -> {
            if (a.getUploadedAt() == null && b.getUploadedAt() == null) return 0;
            if (a.getUploadedAt() == null) return 1;
            if (b.getUploadedAt() == null) return -1;
            return b.getUploadedAt().compareTo(a.getUploadedAt());
        });
        for (Video v : videos) {
            entries.add(new CategoryContentOrder(categoryId, v.getId(), "video", position++));
        }

        if (!entries.isEmpty()) {
            orderRepository.batchSave(entries);
            log.info("Initialized sort order for category {} with {} items", categoryId, entries.size());
        }
    }

    /**
     * Add a content item to a category's sort order at the end.
     */
    public void addContentToCategory(String categoryId, String contentId, String contentType)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Check if entry already exists
        String docId = CategoryContentOrder.generateId(categoryId, contentType, contentId);
        if (orderRepository.findById(docId).isPresent()) {
            return; // Already tracked
        }

        // Find the max position and append.
        // Note: count+save is not atomic; concurrent appends may produce duplicate positions.
        // This is acceptable as positions are renumbered on next reorder operation.
        long count = orderRepository.countByCategoryId(categoryId);
        CategoryContentOrder order = new CategoryContentOrder(categoryId, contentId, contentType, (int) count);
        orderRepository.save(order);
        log.debug("Added {} {} to category {} at position {}", contentType, contentId, categoryId, count);
    }

    /**
     * Remove a content item from a category's sort order and renumber remaining items.
     */
    public void removeContentFromCategory(String categoryId, String contentId, String contentType)
            throws ExecutionException, InterruptedException, TimeoutException {

        String docId = CategoryContentOrder.generateId(categoryId, contentType, contentId);
        if (orderRepository.findById(docId).isEmpty()) {
            return; // Not tracked
        }

        orderRepository.deleteById(docId);

        // Renumber remaining entries to close the gap
        List<CategoryContentOrder> remaining = orderRepository.findByCategoryIdOrderByPosition(categoryId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setPosition(i);
        }
        if (!remaining.isEmpty()) {
            orderRepository.batchSave(remaining);
        }

        log.debug("Removed {} {} from category {} and renumbered {} remaining items",
                contentType, contentId, categoryId, remaining.size());
    }

    // ======================== LIFECYCLE HELPERS ========================

    /**
     * Remove a content item from sort order across all categories.
     * Used when content is deleted or toggled to non-approved status.
     * After deletion, renumbers remaining items in each affected category
     * to maintain contiguous 0..n-1 positions.
     */
    public void removeContentFromAllCategories(String contentId, String contentType)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Find all affected categories before deleting
        List<CategoryContentOrder> entries = orderRepository.findByContentIdAndType(contentId, contentType);
        Set<String> affectedCategoryIds = new java.util.HashSet<>();
        for (CategoryContentOrder entry : entries) {
            affectedCategoryIds.add(entry.getCategoryId());
        }

        // Delete the entries
        orderRepository.deleteByContentIdAndType(contentId, contentType);

        // Renumber remaining items in each affected category to close gaps
        for (String categoryId : affectedCategoryIds) {
            List<CategoryContentOrder> remaining = orderRepository.findByCategoryIdOrderByPosition(categoryId);
            boolean needsRenumber = false;
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).getPosition() != i) {
                    needsRenumber = true;
                    remaining.get(i).setPosition(i);
                }
            }
            if (needsRenumber && !remaining.isEmpty()) {
                orderRepository.batchSave(remaining);
            }
        }
    }

    /**
     * Delete all sort order entries for a category.
     * Used when a category is deleted entirely.
     */
    public void deleteAllOrdersForCategory(String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        orderRepository.deleteByCategoryId(categoryId);
    }

    // ======================== HELPERS ========================

    /**
     * Resolve display info for a content order entry by fetching the actual content document.
     */
    private ContentSortDto resolveContentInfo(CategoryContentOrder entry)
            throws ExecutionException, InterruptedException, TimeoutException {

        switch (entry.getContentType()) {
            case "channel":
                return channelRepository.findById(entry.getContentId())
                        .map(ch -> new ContentSortDto(
                                ch.getId(), "channel", ch.getName(),
                                ch.getThumbnailUrl(), entry.getPosition(), ch.getYoutubeId()))
                        .orElse(null);

            case "playlist":
                return playlistRepository.findById(entry.getContentId())
                        .map(pl -> new ContentSortDto(
                                pl.getId(), "playlist", pl.getTitle(),
                                pl.getThumbnailUrl(), entry.getPosition(), pl.getYoutubeId()))
                        .orElse(null);

            case "video":
                return videoRepository.findById(entry.getContentId())
                        .map(v -> new ContentSortDto(
                                v.getId(), "video", v.getTitle(),
                                v.getThumbnailUrl(), entry.getPosition(), v.getYoutubeId()))
                        .orElse(null);

            default:
                log.warn("Unknown content type: {}", entry.getContentType());
                return null;
        }
    }
}
