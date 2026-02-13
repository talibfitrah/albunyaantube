package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
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
    private final com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache;

    public RegistryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            AuditLogService auditLogService,
            com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.auditLogService = auditLogService;
        this.workspaceExclusionsCache = workspaceExclusionsCache;
    }

    /**
     * Get all channels in registry
     *
     * @param limit Maximum number of channels to return (default: 100)
     */
    @GetMapping("/channels")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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

        // Respect the status if explicitly set, otherwise auto-approve for admins
        if (channel.getStatus() == null || channel.getStatus().isEmpty()) {
            if (user.isAdmin()) {
                channel.setStatus("APPROVED");
                channel.setApprovedBy(user.getUid());
            } else {
                channel.setStatus("PENDING");
            }
        }
        // If status is explicitly set to PENDING, keep it pending even for admins
        // This supports the approval workflow where admins add items for review

        Channel saved = channelRepository.save(channel);
        auditLogService.log("channel_added_to_registry", "channel", saved.getId(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Update channel in registry
     */
    @PutMapping("/channels/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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

        channelRepository.deleteById(id);
        auditLogService.log("channel_deleted_from_registry", "channel", id, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all playlists in registry
     *
     * @param limit Maximum number of playlists to return (default: 100)
     */
    @GetMapping("/playlists")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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

        // Respect the status if explicitly set, otherwise auto-approve for admins
        if (playlist.getStatus() == null || playlist.getStatus().isEmpty()) {
            if (user.isAdmin()) {
                playlist.setStatus("APPROVED");
                playlist.setApprovedBy(user.getUid());
            } else {
                playlist.setStatus("PENDING");
            }
        }
        // If status is explicitly set to PENDING, keep it pending even for admins
        // This supports the approval workflow where admins add items for review

        Playlist saved = playlistRepository.save(playlist);
        auditLogService.log("playlist_added_to_registry", "playlist", saved.getId(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Update playlist in registry
     */
    @PutMapping("/playlists/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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

        playlistRepository.deleteById(id);
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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

        // Respect the status if explicitly set, otherwise auto-approve for admins
        if (video.getStatus() == null || video.getStatus().isEmpty()) {
            if (user.isAdmin()) {
                video.setStatus("APPROVED");
                video.setApprovedBy(user.getUid());
            } else {
                video.setStatus("PENDING");
            }
        }
        // If status is explicitly set to PENDING, keep it pending even for admins
        // This supports the approval workflow where admins add items for review

        Video saved = videoRepository.save(video);
        auditLogService.log("video_added_to_registry", "video", saved.getId(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Update video in registry
     */
    @PutMapping("/videos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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

        videoRepository.deleteById(id);
        auditLogService.log("video_deleted_from_registry", "video", id, user);
        return ResponseEntity.noContent().build();
    }
}

