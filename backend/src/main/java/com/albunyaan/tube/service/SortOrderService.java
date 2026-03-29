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
                    (int) count,
                    cat.getParentCategoryId()
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
     * Add multiple content items to a category's sort order and update their categoryIds.
     *
     * Validates all content items exist before writing. If a write fails mid-batch,
     * already-written entries are rolled back (sort-order docs deleted, categoryIds reverted).
     * Returns the updated content list for the category.
     *
     * @param items list of (contentId, contentType) pairs
     */
    public List<ContentSortDto> addMultipleContentToCategory(
            String categoryId,
            List<String[]> items)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Verify category exists
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryId));

        // Phase 1: Validate all content items exist before writing anything.
        // Note: contentExists does a findById read that is repeated in addCategoryIdToContent.
        // For small batch sizes (admin UI) this 2N read is acceptable vs. the complexity
        // of pre-fetching heterogeneous entity types.
        for (String[] item : items) {
            if (item == null || item.length < 2) {
                throw new IllegalArgumentException("Malformed content item, expected [contentId, contentType]");
            }
            if (!contentExists(item[0], item[1])) {
                throw new IllegalArgumentException(item[1] + " not found: " + item[0]);
            }
        }

        // Phase 2: Write sort-order entries and update categoryIds with rollback on failure
        List<String[]> writtenSortOrders = new ArrayList<>();
        List<String[]> writtenCategoryIds = new ArrayList<>();

        try {
            for (String[] item : items) {
                String contentId = item[0];
                String contentType = item[1];

                addContentToCategory(categoryId, contentId, contentType);
                writtenSortOrders.add(item);

                addCategoryIdToContent(contentId, contentType, categoryId);
                writtenCategoryIds.add(item);
            }
        } catch (Exception e) {
            // Rollback: remove sort-order docs and revert categoryIds for items already written
            log.warn("Add batch failed after {} items, rolling back: {}", writtenSortOrders.size(), e.getMessage());
            for (String[] written : writtenSortOrders) {
                try {
                    removeContentFromCategory(categoryId, written[0], written[1]);
                } catch (Exception rollbackEx) {
                    log.error("Rollback failed for sort-order {}/{}: {}", written[1], written[0], rollbackEx.getMessage());
                }
            }
            for (String[] written : writtenCategoryIds) {
                try {
                    removeCategoryIdFromContent(written[0], written[1], categoryId);
                } catch (Exception rollbackEx) {
                    log.error("Rollback failed for categoryId {}/{}: {}", written[1], written[0], rollbackEx.getMessage());
                }
            }
            throw e;
        }

        cacheService.evictPublicContentCaches();
        return getContentSortOrder(categoryId);
    }

    /**
     * Check whether a content document exists in Firestore.
     */
    private boolean contentExists(String contentId, String contentType)
            throws ExecutionException, InterruptedException, TimeoutException {
        switch (contentType) {
            case "channel":  return channelRepository.findById(contentId).isPresent();
            case "playlist": return playlistRepository.findById(contentId).isPresent();
            case "video":    return videoRepository.findById(contentId).isPresent();
            default:         return false;
        }
    }

    /**
     * Add a category ID to a content item's categoryIds list (if not already present).
     * Throws on failure so callers know the update did not succeed.
     *
     * Note: The switch-per-type pattern mirrors removeCategoryIdFromContent and resolveContentInfo.
     * The entities (Channel, Playlist, Video) don't share a categoryIds interface, so each case
     * is handled explicitly. If a 4th content type is added, all three methods must be updated.
     */
    private void addCategoryIdToContent(String contentId, String contentType, String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        switch (contentType) {
            case "channel": {
                Channel ch = channelRepository.findById(contentId)
                        .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + contentId));
                List<String> cats = ch.getCategoryIds() != null ? new ArrayList<>(ch.getCategoryIds()) : new ArrayList<>();
                if (!cats.contains(categoryId)) {
                    cats.add(categoryId);
                    ch.setCategoryIds(cats);
                    channelRepository.save(ch);
                }
                break;
            }
            case "playlist": {
                Playlist pl = playlistRepository.findById(contentId)
                        .orElseThrow(() -> new IllegalArgumentException("Playlist not found: " + contentId));
                List<String> cats = pl.getCategoryIds() != null ? new ArrayList<>(pl.getCategoryIds()) : new ArrayList<>();
                if (!cats.contains(categoryId)) {
                    cats.add(categoryId);
                    pl.setCategoryIds(cats);
                    playlistRepository.save(pl);
                }
                break;
            }
            case "video": {
                Video v = videoRepository.findById(contentId)
                        .orElseThrow(() -> new IllegalArgumentException("Video not found: " + contentId));
                List<String> cats = v.getCategoryIds() != null ? new ArrayList<>(v.getCategoryIds()) : new ArrayList<>();
                if (!cats.contains(categoryId)) {
                    cats.add(categoryId);
                    v.setCategoryIds(cats);
                    videoRepository.save(v);
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown content type: " + contentType);
        }
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

    /**
     * Remove a content item from a category's sort order and also remove the category
     * from the content item's categoryIds. Updates categoryIds first (idempotent/recoverable),
     * then removes the sort-order entry. Returns the updated content list.
     */
    public List<ContentSortDto> removeContentFromCategoryAndUpdate(
            String categoryId, String contentId, String contentType)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Update categoryIds first — if this fails, nothing has been deleted
        removeCategoryIdFromContent(contentId, contentType, categoryId);
        // Then remove sort-order entry — if this fails, categoryId is already removed
        // which is the safer partial state (orphan sort-order row vs stale categoryId)
        removeContentFromCategory(categoryId, contentId, contentType);
        cacheService.evictPublicContentCaches();
        return getContentSortOrder(categoryId);
    }

    /**
     * Remove a category ID from a content item's categoryIds list.
     * If the content document no longer exists (e.g., deleted), logs a warning
     * but does not throw — the sort-order entry was already removed.
     */
    private void removeCategoryIdFromContent(String contentId, String contentType, String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        switch (contentType) {
            case "channel": {
                Channel ch = channelRepository.findById(contentId).orElse(null);
                if (ch == null) {
                    log.warn("Channel {} not found during category removal — sort-order entry already removed", contentId);
                    return;
                }
                List<String> cats = ch.getCategoryIds();
                if (cats != null && cats.contains(categoryId)) {
                    cats = new ArrayList<>(cats);
                    cats.remove(categoryId);
                    ch.setCategoryIds(cats);
                    channelRepository.save(ch);
                }
                break;
            }
            case "playlist": {
                Playlist pl = playlistRepository.findById(contentId).orElse(null);
                if (pl == null) {
                    log.warn("Playlist {} not found during category removal — sort-order entry already removed", contentId);
                    return;
                }
                List<String> cats = pl.getCategoryIds();
                if (cats != null && cats.contains(categoryId)) {
                    cats = new ArrayList<>(cats);
                    cats.remove(categoryId);
                    pl.setCategoryIds(cats);
                    playlistRepository.save(pl);
                }
                break;
            }
            case "video": {
                Video v = videoRepository.findById(contentId).orElse(null);
                if (v == null) {
                    log.warn("Video {} not found during category removal — sort-order entry already removed", contentId);
                    return;
                }
                List<String> cats = v.getCategoryIds();
                if (cats != null && cats.contains(categoryId)) {
                    cats = new ArrayList<>(cats);
                    cats.remove(categoryId);
                    v.setCategoryIds(cats);
                    videoRepository.save(v);
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown content type: " + contentType);
        }
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
