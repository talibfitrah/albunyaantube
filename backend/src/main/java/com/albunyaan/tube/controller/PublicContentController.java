package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.CategoryDto;
import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.service.PublicContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public API controller for Android app content browsing.
 * No authentication required - serves only approved/included content.
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class PublicContentController {

    private final PublicContentService contentService;

    public PublicContentController(PublicContentService contentService) {
        this.contentService = contentService;
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
    ) {
        // Validate and cap limit
        int validLimit = Math.min(Math.max(limit, 1), 50);

        CursorPageDto<ContentItemDto> page = contentService.getContent(
                type, cursor, validLimit, category, length, date, sort
        );

        return ResponseEntity.ok(page);
    }

    /**
     * Get categories for filtering.
     *
     * @return List of all active categories
     */
    @GetMapping("/categories")
    public ResponseEntity<List<CategoryDto>> getCategories() {
        return ResponseEntity.ok(contentService.getCategories());
    }

    /**
     * Get channel details by ID.
     *
     * @param channelId YouTube channel ID
     * @return Channel details with playlists
     */
    @GetMapping("/channels/{channelId}")
    public ResponseEntity<?> getChannelDetails(@PathVariable String channelId) {
        return ResponseEntity.ok(contentService.getChannelDetails(channelId));
    }

    /**
     * Get playlist details by ID.
     *
     * @param playlistId YouTube playlist ID
     * @return Playlist details with videos
     */
    @GetMapping("/playlists/{playlistId}")
    public ResponseEntity<?> getPlaylistDetails(@PathVariable String playlistId) {
        return ResponseEntity.ok(contentService.getPlaylistDetails(playlistId));
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
    ) {
        int validLimit = Math.min(Math.max(limit, 1), 50);
        return ResponseEntity.ok(contentService.search(q, type, validLimit));
    }
}
