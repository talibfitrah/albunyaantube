package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.SortOrderService;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * BACKEND-REG-01: Registry Management Controller
 *
 * Endpoints for managing channels and playlists in the registry.
 * Provides CRUD operations for the master list of approved content.
 */
@RestController
@RequestMapping("/api/admin/registry")
public class RegistryController {

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final AuditLogService auditLogService;
    private final PublicContentCacheService publicContentCacheService;
    private final SortOrderService sortOrderService;
    private final com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache;

    public RegistryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            AuditLogService auditLogService,
            PublicContentCacheService publicContentCacheService,
            SortOrderService sortOrderService,
            com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.auditLogService = auditLogService;
        this.publicContentCacheService = publicContentCacheService;
        this.sortOrderService = sortOrderService;
        this.workspaceExclusionsCache = workspaceExclusionsCache;
    }

    /**
     * Get all channels in registry
     *
     * @param limit Maximum number of channels to return (default: 100)
     */
    @GetMapping("/channels")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Channel>> getAllChannels(
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Channel> channels = channelRepository.findAll(limit);
        return ResponseEntity.ok(channels);
    }

    /**
     * Get channels by status
     *
     * @param status Channel status (APPROVED, PENDING, REJECTED)
     * @param limit Maximum number of channels to return (default: 100)
     */
    @GetMapping("/channels/status/{status}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Channel>> getChannelsByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Channel> channels = channelRepository.findByStatus(status.toUpperCase(), limit);
        return ResponseEntity.ok(channels);
    }

    /**
     * Get channel by ID
     */
    @GetMapping("/channels/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel> getChannelById(@PathVariable String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return channelRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add channel to registry
     */
    @PostMapping("/channels")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Channel> addChannel(
            @RequestBody Channel channel,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Check if channel already exists by youtubeId
        if (channel.getYoutubeId() != null) {
            var existing = channelRepository.findByYoutubeId(channel.getYoutubeId());
            if (existing.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        channel.setSubmittedBy(user.getUid());

        // Non-admin users always get PENDING status regardless of request body
        if (!user.isAdmin()) {
            channel.setStatus("PENDING");
            channel.setApprovedBy(null);
        } else if (channel.getStatus() == null || channel.getStatus().isEmpty()) {
            // Admin with no explicit status: auto-approve
            channel.setStatus("APPROVED");
            channel.setApprovedBy(user.getUid());
        } else if ("APPROVED".equals(channel.getStatus())) {
            // Admin explicitly approving: ensure approvedBy is set to current admin
            channel.setApprovedBy(user.getUid());
        } else {
            // Admin setting PENDING/REJECTED: clear approvedBy
            channel.setApprovedBy(null);
        }

        Channel saved = channelRepository.save(channel);
        if ("APPROVED".equals(saved.getStatus()) && saved.getCategoryIds() != null) {
            for (String categoryId : saved.getCategoryIds()) {
                sortOrderService.addContentToCategory(categoryId, saved.getId(), "channel");
            }
        }
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("channel_added_to_registry", "channel", saved.getId(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Update channel in registry
     */
    @PutMapping("/channels/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel> updateChannel(
            @PathVariable String id,
            @RequestBody Channel channel,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel existing = channelRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // Update fields
        existing.setName(channel.getName());
        existing.setDescription(channel.getDescription());
        existing.setCategoryIds(channel.getCategoryIds());
        existing.setExcludedItems(channel.getExcludedItems());
        existing.setStatus(channel.getStatus());
        existing.setThumbnailUrl(channel.getThumbnailUrl());
        existing.setSubscribers(channel.getSubscribers());
        existing.setVideoCount(channel.getVideoCount());

        Channel updated = channelRepository.save(existing);
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("channel_updated_in_registry", "channel", id, user);
        return ResponseEntity.ok(updated);
    }

    /**
     * Toggle channel include/exclude state
     */
    @PatchMapping("/channels/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel> toggleChannelStatus(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        // Toggle between APPROVED and PENDING
        if ("APPROVED".equals(channel.getStatus())) {
            channel.setStatus("PENDING");
        } else {
            channel.setStatus("APPROVED");
            channel.setApprovedBy(user.getUid());
        }

        Channel updated = channelRepository.save(channel);
        if ("APPROVED".equals(updated.getStatus()) && updated.getCategoryIds() != null) {
            for (String categoryId : updated.getCategoryIds()) {
                sortOrderService.addContentToCategory(categoryId, updated.getId(), "channel");
            }
        } else {
            sortOrderService.removeContentFromAllCategories(updated.getId(), "channel");
        }
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("channel_status_toggled", "channel", id, user);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete channel from registry
     */
    @DeleteMapping("/channels/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteChannel(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (!channelRepository.findById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        sortOrderService.removeContentFromAllCategories(id, "channel");
        channelRepository.deleteById(id);
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("channel_deleted_from_registry", "channel", id, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all playlists in registry
     *
     * @param limit Maximum number of playlists to return (default: 100)
     */
    @GetMapping("/playlists")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Playlist>> getAllPlaylists(
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Playlist> playlists = playlistRepository.findAll(limit);
        return ResponseEntity.ok(playlists);
    }

    /**
     * Get playlists by status
     *
     * @param status Playlist status (APPROVED, PENDING, REJECTED)
     * @param limit Maximum number of playlists to return (default: 100)
     */
    @GetMapping("/playlists/status/{status}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Playlist>> getPlaylistsByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Playlist> playlists = playlistRepository.findByStatus(status.toUpperCase(), limit);
        return ResponseEntity.ok(playlists);
    }

    /**
     * Get playlist by ID
     */
    @GetMapping("/playlists/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Playlist> getPlaylistById(@PathVariable String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return playlistRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add playlist to registry
     */
    @PostMapping("/playlists")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Playlist> addPlaylist(
            @RequestBody Playlist playlist,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Check if playlist already exists by youtubeId
        if (playlist.getYoutubeId() != null) {
            var existing = playlistRepository.findByYoutubeId(playlist.getYoutubeId());
            if (existing.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        playlist.setSubmittedBy(user.getUid());

        // Non-admin users always get PENDING status regardless of request body
        if (!user.isAdmin()) {
            playlist.setStatus("PENDING");
            playlist.setApprovedBy(null);
        } else if (playlist.getStatus() == null || playlist.getStatus().isEmpty()) {
            // Admin with no explicit status: auto-approve
            playlist.setStatus("APPROVED");
            playlist.setApprovedBy(user.getUid());
        } else if ("APPROVED".equals(playlist.getStatus())) {
            // Admin explicitly approving: ensure approvedBy is set to current admin
            playlist.setApprovedBy(user.getUid());
        } else {
            // Admin setting PENDING/REJECTED: clear approvedBy
            playlist.setApprovedBy(null);
        }

        Playlist saved = playlistRepository.save(playlist);
        if ("APPROVED".equals(saved.getStatus()) && saved.getCategoryIds() != null) {
            for (String categoryId : saved.getCategoryIds()) {
                sortOrderService.addContentToCategory(categoryId, saved.getId(), "playlist");
            }
        }
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("playlist_added_to_registry", "playlist", saved.getId(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Update playlist in registry
     */
    @PutMapping("/playlists/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Playlist> updatePlaylist(
            @PathVariable String id,
            @RequestBody Playlist playlist,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist existing = playlistRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // Update fields
        existing.setTitle(playlist.getTitle());
        existing.setDescription(playlist.getDescription());
        existing.setCategoryIds(playlist.getCategoryIds());
        existing.setExcludedVideoIds(playlist.getExcludedVideoIds());
        existing.setStatus(playlist.getStatus());
        existing.setThumbnailUrl(playlist.getThumbnailUrl());
        existing.setItemCount(playlist.getItemCount());

        Playlist updated = playlistRepository.save(existing);
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("playlist_updated_in_registry", "playlist", id, user);
        return ResponseEntity.ok(updated);
    }

    /**
     * Toggle playlist include/exclude state
     */
    @PatchMapping("/playlists/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Playlist> togglePlaylistStatus(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist playlist = playlistRepository.findById(id).orElse(null);
        if (playlist == null) {
            return ResponseEntity.notFound().build();
        }

        // Toggle between APPROVED and PENDING
        if ("APPROVED".equals(playlist.getStatus())) {
            playlist.setStatus("PENDING");
        } else {
            playlist.setStatus("APPROVED");
            playlist.setApprovedBy(user.getUid());
        }

        Playlist updated = playlistRepository.save(playlist);
        if ("APPROVED".equals(updated.getStatus()) && updated.getCategoryIds() != null) {
            for (String categoryId : updated.getCategoryIds()) {
                sortOrderService.addContentToCategory(categoryId, updated.getId(), "playlist");
            }
        } else {
            sortOrderService.removeContentFromAllCategories(updated.getId(), "playlist");
        }
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("playlist_status_toggled", "playlist", id, user);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete playlist from registry
     */
    @DeleteMapping("/playlists/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePlaylist(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (!playlistRepository.findById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        sortOrderService.removeContentFromAllCategories(id, "playlist");
        playlistRepository.deleteById(id);
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("playlist_deleted_from_registry", "playlist", id, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get playlist exclusions (excluded video IDs)
     */
    @GetMapping("/playlists/{id}/exclusions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<String>> getPlaylistExclusions(@PathVariable String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist playlist = playlistRepository.findById(id).orElse(null);
        if (playlist == null) {
            return ResponseEntity.notFound().build();
        }

        List<String> excluded = playlist.getExcludedVideoIds();
        if (excluded == null) {
            excluded = new java.util.ArrayList<>();
        }
        return ResponseEntity.ok(excluded);
    }

    /**
     * Add a video exclusion to playlist
     * @param videoId The YouTube video ID to exclude
     */
    @PostMapping("/playlists/{id}/exclusions/{videoId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<String>> addPlaylistExclusion(
            @PathVariable String id,
            @PathVariable String videoId,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Validate video ID format (basic validation)
        if (videoId == null || videoId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Playlist playlist = playlistRepository.findById(id).orElse(null);
        if (playlist == null) {
            return ResponseEntity.notFound().build();
        }

        List<String> excluded = playlist.getExcludedVideoIds();
        if (excluded == null) {
            excluded = new java.util.ArrayList<>();
        }

        // Add only if not already excluded
        if (!excluded.contains(videoId)) {
            excluded.add(videoId);
            playlist.setExcludedVideoIds(excluded);
            playlist.touch();
            playlistRepository.save(playlist);
            workspaceExclusionsCache.invalidateAll();
            publicContentCacheService.evictPublicContentCaches();
            auditLogService.log("playlist_video_excluded", "playlist", id, user);
        }

        return ResponseEntity.ok(excluded);
    }

    /**
     * Remove a video exclusion from playlist
     * @param videoId The YouTube video ID to remove from exclusions
     */
    @DeleteMapping("/playlists/{id}/exclusions/{videoId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<String>> removePlaylistExclusion(
            @PathVariable String id,
            @PathVariable String videoId,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist playlist = playlistRepository.findById(id).orElse(null);
        if (playlist == null) {
            return ResponseEntity.notFound().build();
        }

        List<String> excluded = playlist.getExcludedVideoIds();
        if (excluded == null) {
            excluded = new java.util.ArrayList<>();
        }

        // Remove if present
        boolean removed = excluded.remove(videoId);
        if (removed) {
            playlist.setExcludedVideoIds(excluded);
            playlist.touch();
            playlistRepository.save(playlist);
            workspaceExclusionsCache.invalidateAll();
            publicContentCacheService.evictPublicContentCaches();
            auditLogService.log("playlist_video_exclusion_removed", "playlist", id, user);
        }

        return ResponseEntity.ok(excluded);
    }

    // ==================== VIDEO ENDPOINTS ====================

    /**
     * Get all videos in registry
     *
     * @param limit Maximum number of videos to return (default: 100)
     */
    @GetMapping("/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Video>> getAllVideos(
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Video> videos = videoRepository.findAll(limit);
        return ResponseEntity.ok(videos);
    }

    /**
     * Get videos by status
     *
     * @param status Video status (APPROVED, PENDING, REJECTED)
     * @param limit Maximum number of videos to return (default: 100)
     */
    @GetMapping("/videos/status/{status}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Video>> getVideosByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Video> videos = videoRepository.findByStatus(status.toUpperCase(), limit);
        return ResponseEntity.ok(videos);
    }

    /**
     * Get video by ID
     */
    @GetMapping("/videos/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Video> getVideoById(@PathVariable String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return videoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add video to registry
     */
    @PostMapping("/videos")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Video> addVideo(
            @RequestBody Video video,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Check if video already exists by youtubeId
        if (video.getYoutubeId() != null) {
            var existing = videoRepository.findByYoutubeId(video.getYoutubeId());
            if (existing.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        video.setSubmittedBy(user.getUid());

        // Non-admin users always get PENDING status regardless of request body
        if (!user.isAdmin()) {
            video.setStatus("PENDING");
            video.setApprovedBy(null);
        } else if (video.getStatus() == null || video.getStatus().isEmpty()) {
            // Admin with no explicit status: auto-approve
            video.setStatus("APPROVED");
            video.setApprovedBy(user.getUid());
        } else if ("APPROVED".equals(video.getStatus())) {
            // Admin explicitly approving: ensure approvedBy is set to current admin
            video.setApprovedBy(user.getUid());
        } else {
            // Admin setting PENDING/REJECTED: clear approvedBy
            video.setApprovedBy(null);
        }

        Video saved = videoRepository.save(video);
        if ("APPROVED".equals(saved.getStatus()) && saved.getCategoryIds() != null) {
            for (String categoryId : saved.getCategoryIds()) {
                sortOrderService.addContentToCategory(categoryId, saved.getId(), "video");
            }
        }
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("video_added_to_registry", "video", saved.getId(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Update video in registry
     */
    @PutMapping("/videos/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Video> updateVideo(
            @PathVariable String id,
            @RequestBody Video video,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Video existing = videoRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // Update fields
        existing.setTitle(video.getTitle());
        existing.setDescription(video.getDescription());
        existing.setCategoryIds(video.getCategoryIds());
        existing.setStatus(video.getStatus());
        existing.setThumbnailUrl(video.getThumbnailUrl());
        existing.setDurationSeconds(video.getDurationSeconds());
        existing.setViewCount(video.getViewCount());

        Video updated = videoRepository.save(existing);
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("video_updated_in_registry", "video", id, user);
        return ResponseEntity.ok(updated);
    }

    /**
     * Toggle video include/exclude state
     */
    @PatchMapping("/videos/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Video> toggleVideoStatus(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Video video = videoRepository.findById(id).orElse(null);
        if (video == null) {
            return ResponseEntity.notFound().build();
        }

        // Toggle between APPROVED and PENDING
        if ("APPROVED".equals(video.getStatus())) {
            video.setStatus("PENDING");
        } else {
            video.setStatus("APPROVED");
            video.setApprovedBy(user.getUid());
        }

        Video updated = videoRepository.save(video);
        if ("APPROVED".equals(updated.getStatus()) && updated.getCategoryIds() != null) {
            for (String categoryId : updated.getCategoryIds()) {
                sortOrderService.addContentToCategory(categoryId, updated.getId(), "video");
            }
        } else {
            sortOrderService.removeContentFromAllCategories(updated.getId(), "video");
        }
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("video_status_toggled", "video", id, user);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete video from registry
     */
    @DeleteMapping("/videos/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteVideo(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (!videoRepository.findById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        sortOrderService.removeContentFromAllCategories(id, "video");
        videoRepository.deleteById(id);
        publicContentCacheService.evictPublicContentCaches();
        auditLogService.log("video_deleted_from_registry", "video", id, user);
        return ResponseEntity.noContent().build();
    }
}

