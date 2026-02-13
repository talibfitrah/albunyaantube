package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Exclusions Workspace Controller
 *
 * Provides a unified view of all exclusions across all channels and playlists.
 * Used by the admin Exclusions Workspace page.
 *
 * Endpoints:
 * - GET  /api/admin/exclusions          - List all exclusions (paginated)
 * - POST /api/admin/exclusions          - Create a new exclusion
 * - DELETE /api/admin/exclusions/{id}   - Remove an exclusion
 *
 * Cache strategy:
 * Uses a dedicated Caffeine cache (5-minute TTL, single entry) to avoid repeated
 * full collection scans. The cache stores the aggregated exclusion list plus a
 * truncation flag indicating whether safety limits were hit.
 */
@RestController
@RequestMapping("/api/admin/exclusions")
@PreAuthorize("hasRole('ADMIN')")
public class ExclusionsWorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(ExclusionsWorkspaceController.class);

    private static final String CACHE_KEY = "all";
    private static final int MAX_CHANNEL_EXCLUSIONS = 500;
    private static final int MAX_PAGE_LIMIT = 200;

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final Cache<String, Object> workspaceExclusionsCache;

    public ExclusionsWorkspaceController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            Cache<String, Object> workspaceExclusionsCache
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.workspaceExclusionsCache = workspaceExclusionsCache;
    }

    /**
     * Cached result containing all aggregated exclusions and truncation state.
     * Single cache entry to avoid cardinality issues.
     */
    static class CachedExclusions {
        final List<ExclusionDto> exclusions;
        final boolean truncated;

        CachedExclusions(List<ExclusionDto> exclusions, boolean truncated) {
            this.exclusions = exclusions;
            this.truncated = truncated;
        }
    }

    /**
     * Flattened exclusion DTO for the workspace view.
     * Each row represents one excluded item within one parent.
     *
     * Wire contract: excludeType is the resource kind (VIDEO or PLAYLIST).
     * The reason field encodes the content sub-type (LIVESTREAM, SHORT, POST, or null).
     * The synthetic ID's 3rd segment keeps the storage type for correct delete routing.
     */
    public static class ExclusionDto {
        public String id;              // Synthetic ID: "{parentType}:{parentId}:{storageType}:{excludeId}"
        public String parentType;      // "CHANNEL" or "PLAYLIST"
        public String parentId;        // Firestore document ID of the parent
        public String parentYoutubeId; // YouTube ID of the parent
        public String parentName;      // Display name of the parent
        public String excludeType;     // Wire type: "VIDEO" or "PLAYLIST" only
        public String excludeId;       // YouTube ID of the excluded item
        public String reason;          // Content sub-type: "LIVESTREAM", "SHORT", "POST", or null
        public String createdAt;       // Parent's updatedAt as ISO string (best available timestamp)
        public Object createdBy;       // null (no per-exclusion creator tracked)

        ExclusionDto() {}
    }

    /**
     * Get all exclusions (paginated, filterable).
     *
     * @param cursor   Pagination cursor (0-based index into the aggregated list)
     * @param limit    Page size (default 50, max 200)
     * @param parentType Filter by parent type: CHANNEL or PLAYLIST
     * @param excludeType Filter by exclude type: VIDEO or PLAYLIST
     * @param search   Search term for parent name or exclude ID
     */
    @GetMapping
    public ResponseEntity<CursorPageDto<ExclusionDto>> getExclusions(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String parentType,
            @RequestParam(required = false) String excludeType,
            @RequestParam(required = false) String search
    ) throws ExecutionException, InterruptedException, TimeoutException {

        int cappedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_LIMIT);

        // Get or compute cached aggregation
        CachedExclusions cached = getCachedExclusions();

        // Apply filters
        List<ExclusionDto> filtered = cached.exclusions;

        if (parentType != null && !parentType.isBlank()) {
            String pt = parentType.toUpperCase();
            filtered = filtered.stream()
                    .filter(e -> pt.equals(e.parentType))
                    .collect(Collectors.toList());
        }

        if (excludeType != null && !excludeType.isBlank()) {
            String et = excludeType.toUpperCase();
            filtered = filtered.stream()
                    .filter(e -> et.equals(e.excludeType))
                    .collect(Collectors.toList());
        }

        if (search != null && !search.isBlank()) {
            String searchLower = search.toLowerCase(java.util.Locale.ROOT);
            filtered = filtered.stream()
                    .filter(e -> matchesSearch(e, searchLower))
                    .collect(Collectors.toList());
        }

        // Cursor-based pagination (cursor is a 0-based offset index)
        int startIndex = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                startIndex = Integer.parseInt(cursor);
                if (startIndex < 0) {
                    return ResponseEntity.badRequest().body(
                            new CursorPageDto<>(List.of(), null, 0, false));
                }
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(
                        new CursorPageDto<>(List.of(), null, 0, false));
            }
        }

        int endIndex = Math.min(startIndex + cappedLimit, filtered.size());
        List<ExclusionDto> page = startIndex < filtered.size()
                ? filtered.subList(startIndex, endIndex)
                : List.of();

        boolean hasNext = endIndex < filtered.size();
        String nextCursor = hasNext ? String.valueOf(endIndex) : null;

        CursorPageDto<ExclusionDto> response = new CursorPageDto<>(
                page, nextCursor, filtered.size(), cached.truncated
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Create a new exclusion.
     * Routes to the appropriate channel or playlist exclusion mechanism.
     */
    @PostMapping
    public ResponseEntity<?> createExclusion(@RequestBody CreateExclusionRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {

        if (request.parentType == null || request.parentId == null ||
                request.excludeType == null || request.excludeId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "parentType, parentId, excludeType, and excludeId are required"));
        }

        String pt = request.parentType.toUpperCase();
        String et = request.excludeType.toUpperCase();

        // Validate excludeType is a wire type (VIDEO or PLAYLIST only)
        if (!"VIDEO".equals(et) && !"PLAYLIST".equals(et)) {
            return ResponseEntity.badRequest().body(Map.of("error", "excludeType must be VIDEO or PLAYLIST"));
        }

        // Normalize reason
        String reason = request.reason != null ? request.reason.toUpperCase() : null;
        if (reason != null && !"LIVESTREAM".equals(reason) && !"SHORT".equals(reason) && !"POST".equals(reason)) {
            reason = null; // Unknown reasons are ignored
        }

        if ("CHANNEL".equals(pt)) {
            Channel channel = channelRepository.findById(request.parentId).orElse(null);
            if (channel == null) {
                return ResponseEntity.notFound().build();
            }

            Channel.ExcludedItems excluded = channel.getExcludedItems();
            if (excluded == null) {
                excluded = new Channel.ExcludedItems();
            }

            // Determine storage type from excludeType + reason
            String storageType = resolveStorageType(et, reason);
            boolean added = addToChannelExclusions(excluded, storageType, request.excludeId);
            if (added) {
                channel.setExcludedItems(excluded);
                channel.touch();
                channelRepository.save(channel);
                // Invalidate cache
                workspaceExclusionsCache.invalidateAll();
            }

            // Build response DTO
            ExclusionDto dto = new ExclusionDto();
            dto.id = pt + ":" + request.parentId + ":" + storageType + ":" + request.excludeId;
            dto.parentType = pt;
            dto.parentId = request.parentId;
            dto.parentYoutubeId = channel.getYoutubeId();
            dto.parentName = channel.getName();
            dto.excludeType = et;
            dto.excludeId = request.excludeId;
            dto.reason = reason;

            return ResponseEntity.status(201).body(dto);

        } else if ("PLAYLIST".equals(pt)) {
            if (!"VIDEO".equals(et)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Playlist exclusions only support excludeType=VIDEO"));
            }

            Playlist playlist = playlistRepository.findById(request.parentId).orElse(null);
            if (playlist == null) {
                return ResponseEntity.notFound().build();
            }

            List<String> excludedIds = playlist.getExcludedVideoIds();
            if (excludedIds == null) {
                excludedIds = new ArrayList<>();
            }

            if (!excludedIds.contains(request.excludeId)) {
                excludedIds.add(request.excludeId);
                playlist.setExcludedVideoIds(excludedIds);
                playlist.touch();
                playlistRepository.save(playlist);
                // Invalidate cache
                workspaceExclusionsCache.invalidateAll();
            }

            ExclusionDto dto = new ExclusionDto();
            dto.id = pt + ":" + request.parentId + ":VIDEO:" + request.excludeId;
            dto.parentType = pt;
            dto.parentId = request.parentId;
            dto.parentYoutubeId = playlist.getYoutubeId();
            dto.parentName = playlist.getTitle();
            dto.excludeType = et;
            dto.excludeId = request.excludeId;
            dto.reason = null; // Playlists don't have reason sub-types

            return ResponseEntity.status(201).body(dto);
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "parentType must be CHANNEL or PLAYLIST"));
        }
    }

    /**
     * Remove an exclusion by its synthetic ID.
     * ID format: "{parentType}:{parentId}:{storageType}:{excludeId}"
     * storageType = VIDEO, PLAYLIST, LIVESTREAM, SHORT, or POST
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> removeExclusion(@PathVariable String id)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Parse synthetic ID
        String[] parts = id.split(":", 4);
        if (parts.length != 4) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid exclusion ID format. Expected: parentType:parentId:excludeType:excludeId"));
        }

        String pt = parts[0].toUpperCase();
        String parentId = parts[1];
        String et = parts[2].toUpperCase();
        String excludeId = parts[3];

        if ("CHANNEL".equals(pt)) {
            Channel channel = channelRepository.findById(parentId).orElse(null);
            if (channel == null) {
                return ResponseEntity.notFound().build();
            }

            Channel.ExcludedItems excluded = channel.getExcludedItems();
            if (excluded == null) {
                return ResponseEntity.notFound().build();
            }

            boolean removed = removeFromChannelExclusions(excluded, et, excludeId);
            if (removed) {
                channel.setExcludedItems(excluded);
                channel.touch();
                channelRepository.save(channel);
                workspaceExclusionsCache.invalidateAll();
            }

            return ResponseEntity.noContent().build();

        } else if ("PLAYLIST".equals(pt)) {
            Playlist playlist = playlistRepository.findById(parentId).orElse(null);
            if (playlist == null) {
                return ResponseEntity.notFound().build();
            }

            List<String> excludedIds = playlist.getExcludedVideoIds();
            if (excludedIds != null) {
                boolean removed = excludedIds.remove(excludeId);
                if (removed) {
                    playlist.setExcludedVideoIds(excludedIds);
                    playlist.touch();
                    playlistRepository.save(playlist);
                    workspaceExclusionsCache.invalidateAll();
                }
            }

            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid parentType in ID: " + pt));
        }
    }

    // --- Private helpers ---

    /**
     * Get or compute the cached exclusions aggregation.
     * Uses the dedicated Caffeine cache with 5-minute TTL and single-entry capacity.
     */
    private CachedExclusions getCachedExclusions() throws ExecutionException, InterruptedException, TimeoutException {
        Object cached = workspaceExclusionsCache.getIfPresent(CACHE_KEY);
        if (cached instanceof CachedExclusions) {
            log.debug("Workspace exclusions cache HIT");
            return (CachedExclusions) cached;
        }

        log.info("Workspace exclusions cache MISS - aggregating from Firestore");
        CachedExclusions result = aggregateExclusions();
        workspaceExclusionsCache.put(CACHE_KEY, result);
        return result;
    }

    /**
     * Aggregate all exclusions from channels and playlists into a flat list.
     * Uses bounded queries to prevent quota exhaustion.
     */
    private CachedExclusions aggregateExclusions() throws ExecutionException, InterruptedException, TimeoutException {
        List<ExclusionDto> allExclusions = new ArrayList<>();
        boolean truncated = false;

        // Fetch channels with exclusions (bounded)
        List<Channel> channelsWithExclusions = channelRepository.findAllWithExclusions(MAX_CHANNEL_EXCLUSIONS + 1);
        if (channelsWithExclusions.size() > MAX_CHANNEL_EXCLUSIONS) {
            truncated = true;
            channelsWithExclusions = channelsWithExclusions.subList(0, MAX_CHANNEL_EXCLUSIONS);
        }

        for (Channel channel : channelsWithExclusions) {
            Channel.ExcludedItems items = channel.getExcludedItems();
            if (items == null) continue;

            String updatedAtStr = channel.getUpdatedAt() != null
                    ? channel.getUpdatedAt().toDate().toInstant().toString()
                    : null;

            // Wire excludeType is resource kind (VIDEO or PLAYLIST), reason is content sub-type.
            // Synthetic ID 3rd segment keeps storage type for correct delete routing.
            flattenChannelExclusions(allExclusions, channel, items.getVideos(), "VIDEO", "VIDEO", null, updatedAtStr);
            flattenChannelExclusions(allExclusions, channel, items.getPlaylists(), "PLAYLIST", "PLAYLIST", null, updatedAtStr);
            flattenChannelExclusions(allExclusions, channel, items.getLiveStreams(), "LIVESTREAM", "VIDEO", "LIVESTREAM", updatedAtStr);
            flattenChannelExclusions(allExclusions, channel, items.getShorts(), "SHORT", "VIDEO", "SHORT", updatedAtStr);
            flattenChannelExclusions(allExclusions, channel, items.getPosts(), "POST", "VIDEO", "POST", updatedAtStr);
        }

        // Fetch playlists with exclusions (bounded)
        List<Playlist> playlistsWithExclusions = playlistRepository.findAllWithExclusions();
        // PlaylistRepository already enforces a hard limit of 1000 internally

        for (Playlist playlist : playlistsWithExclusions) {
            List<String> excludedIds = playlist.getExcludedVideoIds();
            if (excludedIds == null || excludedIds.isEmpty()) continue;

            String updatedAtStr = playlist.getUpdatedAt() != null
                    ? playlist.getUpdatedAt().toDate().toInstant().toString()
                    : null;

            for (String videoId : excludedIds) {
                ExclusionDto dto = new ExclusionDto();
                dto.id = "PLAYLIST:" + playlist.getId() + ":VIDEO:" + videoId;
                dto.parentType = "PLAYLIST";
                dto.parentId = playlist.getId();
                dto.parentYoutubeId = playlist.getYoutubeId();
                dto.parentName = playlist.getTitle();
                dto.excludeType = "VIDEO";
                dto.excludeId = videoId;
                dto.createdAt = updatedAtStr;
                allExclusions.add(dto);
            }
        }

        // Sort by createdAt descending (newest first), with null-safe handling
        allExclusions.sort((a, b) -> {
            if (a.createdAt == null && b.createdAt == null) return 0;
            if (a.createdAt == null) return 1;
            if (b.createdAt == null) return -1;
            return b.createdAt.compareTo(a.createdAt);
        });

        log.info("Aggregated {} exclusions (channels: {}, playlists: {}, truncated: {})",
                allExclusions.size(), channelsWithExclusions.size(), playlistsWithExclusions.size(), truncated);

        return new CachedExclusions(allExclusions, truncated);
    }

    private void flattenChannelExclusions(List<ExclusionDto> target, Channel channel,
                                           List<String> ids, String storageType,
                                           String wireExcludeType, String reason, String createdAt) {
        if (ids == null) return;
        for (String excludeId : ids) {
            ExclusionDto dto = new ExclusionDto();
            dto.id = "CHANNEL:" + channel.getId() + ":" + storageType + ":" + excludeId;
            dto.parentType = "CHANNEL";
            dto.parentId = channel.getId();
            dto.parentYoutubeId = channel.getYoutubeId();
            dto.parentName = channel.getName();
            dto.excludeType = wireExcludeType;
            dto.excludeId = excludeId;
            dto.reason = reason;
            dto.createdAt = createdAt;
            target.add(dto);
        }
    }

    private boolean matchesSearch(ExclusionDto exclusion, String searchLower) {
        if (exclusion.parentName != null && exclusion.parentName.toLowerCase(java.util.Locale.ROOT).contains(searchLower)) {
            return true;
        }
        if (exclusion.excludeId != null && exclusion.excludeId.toLowerCase(java.util.Locale.ROOT).contains(searchLower)) {
            return true;
        }
        if (exclusion.parentYoutubeId != null && exclusion.parentYoutubeId.toLowerCase(java.util.Locale.ROOT).contains(searchLower)) {
            return true;
        }
        return false;
    }

    /**
     * Resolve the internal storage type from the wire excludeType and optional reason.
     * VIDEO + LIVESTREAM → LIVESTREAM, VIDEO + SHORT → SHORT, VIDEO + POST → POST,
     * VIDEO + null → VIDEO, PLAYLIST → PLAYLIST.
     */
    private String resolveStorageType(String wireExcludeType, String reason) {
        if ("VIDEO".equals(wireExcludeType) && reason != null) {
            switch (reason) {
                case "LIVESTREAM": return "LIVESTREAM";
                case "SHORT": return "SHORT";
                case "POST": return "POST";
            }
        }
        return wireExcludeType; // VIDEO or PLAYLIST
    }

    private boolean addToChannelExclusions(Channel.ExcludedItems excluded, String excludeType, String excludeId) {
        List<String> list;
        switch (excludeType) {
            case "VIDEO": list = excluded.getVideos(); break;
            case "PLAYLIST": list = excluded.getPlaylists(); break;
            case "LIVESTREAM": list = excluded.getLiveStreams(); break;
            case "SHORT": list = excluded.getShorts(); break;
            case "POST": list = excluded.getPosts(); break;
            default: return false;
        }
        if (list == null) return false;
        if (!list.contains(excludeId)) {
            list.add(excludeId);
            return true;
        }
        return false;
    }

    private boolean removeFromChannelExclusions(Channel.ExcludedItems excluded, String excludeType, String excludeId) {
        List<String> list;
        switch (excludeType) {
            case "VIDEO": list = excluded.getVideos(); break;
            case "PLAYLIST": list = excluded.getPlaylists(); break;
            case "LIVESTREAM": list = excluded.getLiveStreams(); break;
            case "SHORT": list = excluded.getShorts(); break;
            case "POST": list = excluded.getPosts(); break;
            default: return false;
        }
        return list != null && list.remove(excludeId);
    }

    // --- Request DTOs ---

    public static class CreateExclusionRequest {
        public String parentType;
        public String parentId;
        public String excludeType;
        public String excludeId;
        public String reason;
    }
}
