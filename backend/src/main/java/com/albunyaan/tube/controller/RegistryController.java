package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
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
    private final AuditLogService auditLogService;

    public RegistryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            AuditLogService auditLogService
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Get all channels in registry
     */
    @GetMapping("/channels")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<List<Channel>> getAllChannels() throws ExecutionException, InterruptedException {
        List<Channel> channels = channelRepository.findAll();
        return ResponseEntity.ok(channels);
    }

    /**
     * Get channels by status
     */
    @GetMapping("/channels/status/{status}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<List<Channel>> getChannelsByStatus(@PathVariable String status)
            throws ExecutionException, InterruptedException {
        List<Channel> channels = channelRepository.findByStatus(status.toUpperCase());
        return ResponseEntity.ok(channels);
    }

    /**
     * Get channel by ID
     */
    @GetMapping("/channels/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Channel> getChannelById(@PathVariable String id)
            throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
        if (!channelRepository.findById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        channelRepository.deleteById(id);
        auditLogService.log("channel_deleted_from_registry", "channel", id, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all playlists in registry
     */
    @GetMapping("/playlists")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<List<Playlist>> getAllPlaylists() throws ExecutionException, InterruptedException {
        List<Playlist> playlists = playlistRepository.findAll();
        return ResponseEntity.ok(playlists);
    }

    /**
     * Get playlists by status
     */
    @GetMapping("/playlists/status/{status}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<List<Playlist>> getPlaylistsByStatus(@PathVariable String status)
            throws ExecutionException, InterruptedException {
        List<Playlist> playlists = playlistRepository.findByStatus(status.toUpperCase());
        return ResponseEntity.ok(playlists);
    }

    /**
     * Get playlist by ID
     */
    @GetMapping("/playlists/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Playlist> getPlaylistById(@PathVariable String id)
            throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
        if (!playlistRepository.findById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        playlistRepository.deleteById(id);
        auditLogService.log("playlist_deleted_from_registry", "playlist", id, user);
        return ResponseEntity.noContent().build();
    }
}
