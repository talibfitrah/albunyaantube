package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.dto.CategoryDto;
import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.HomeCategoryDto;
import com.albunyaan.tube.service.PublicContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Public API controller for Android app content browsing.
 * No authentication required - serves only approved/included content.
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "${app.security.cors.allowed-origins}")
public class PublicContentController {

    private static final Logger log = LoggerFactory.getLogger(PublicContentController.class);

    private final PublicContentService contentService;

    public PublicContentController(PublicContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * Get home feed with paginated category sections.
     * Each category section contains content items in admin-defined sort order.
     *
     * @param cursor Pagination cursor from previous page
     * @param categoryLimit Categories per page (default 5, max 10)
     * @param contentLimit Items per category (default 10, max 20)
     * @return Paginated home feed with category sections
     */
    @GetMapping("/home")
    public ResponseEntity<?> getHomeFeed(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "5") int categoryLimit,
            @RequestParam(required = false, defaultValue = "10") int contentLimit
    ) {
        int validCategoryLimit = Math.min(Math.max(categoryLimit, 1), 10);
        int validContentLimit = Math.min(Math.max(contentLimit, 1), 20);

        try {
            CursorPageDto<HomeCategoryDto> feed = contentService.getHomeFeed(cursor, validCategoryLimit, validContentLimit);
            // cachePublic() is safe here because this endpoint is unauthenticated and returns
            // identical content for all users. If auth/personalisation is ever added, switch
            // to cachePrivate() to prevent shared proxy caches from leaking user-specific data.
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                    .body(feed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while fetching home feed", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Failed to load home feed"));
        } catch (Exception e) {
            log.error("Error fetching home feed (cursor={}, catLimit={}, contentLimit={})",
                    cursor, validCategoryLimit, validContentLimit, e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Failed to load home feed"));
        }
    }

    /**
     * Fetch paginated content for the Android app.
     *
     * @param type Content type: HOME (mixed), CHANNELS, PLAYLISTS, VIDEOS
     * @param cursor Pagination cursor from previous page
     * @param limit Page size (default 20, max 50)
     * @param category Filter by category slug
     * @param length Filter by video length: SHORT, MEDIUM, LONG
     * @param date Filter by published date: LAST_24_HOURS, LAST_7_DAYS, LAST_30_DAYS
     * @param sort Sort order: DEFAULT, MOST_POPULAR, NEWEST
     * @return Paginated content with next cursor
     */
    @GetMapping("/content")
    public ResponseEntity<CursorPageDto<ContentItemDto>> getContent(
            @RequestParam(required = false, defaultValue = "HOME") String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String length,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String sort
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Validate and cap limit
        int validLimit = Math.min(Math.max(limit, 1), 50);

        CursorPageDto<ContentItemDto> page = contentService.getContent(
                type, cursor, validLimit, category, length, date, sort
        );

        return ResponseEntity.ok(page);
    }

    /**
     * Get categories for filtering.
     * BACKEND-PERF-01: Cached for 1 hour
     *
     * @return List of all active categories
     */
    @GetMapping("/categories")
    @Cacheable(value = CacheConfig.CACHE_CATEGORY_TREE, key = "'public-categories'")
    public ResponseEntity<List<CategoryDto>> getCategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return ResponseEntity.ok(contentService.getCategories());
    }

    /**
     * Get channel details by ID.
     *
     * @param channelId YouTube channel ID
     * @return Channel details with playlists
     */
    @GetMapping("/channels/{channelId}")
    public ResponseEntity<?> getChannelDetails(@PathVariable String channelId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return ResponseEntity.ok(contentService.getChannelDetails(channelId));
    }

    /**
     * Get playlist details by ID.
     *
     * @param playlistId YouTube playlist ID
     * @return Playlist details with videos
     */
    @GetMapping("/playlists/{playlistId}")
    public ResponseEntity<?> getPlaylistDetails(@PathVariable String playlistId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return ResponseEntity.ok(contentService.getPlaylistDetails(playlistId));
    }

    /**
     * Get video details by ID.
     *
     * @param videoId YouTube video ID
     * @return Video details including metadata and approval status
     */
    @GetMapping("/videos/{videoId}")
    public ResponseEntity<?> getVideoDetails(@PathVariable String videoId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return ResponseEntity.ok(contentService.getVideoDetails(videoId));
    }

    /**
     * Search across all content types.
     *
     * @param q Search query
     * @param type Filter by content type (optional)
     * @param limit Results limit (default 20)
     * @return Search results
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "20") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        int validLimit = Math.min(Math.max(limit, 1), 50);
        return ResponseEntity.ok(contentService.search(q, type, validLimit));
    }
}

