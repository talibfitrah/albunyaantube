package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * FIREBASE-MIGRATE-03: Channel Management Controller
 *
 * Endpoints for channel CRUD operations.
 * Moderators can submit channels for approval.
 * Admins can approve/reject/delete channels.
 */
@RestController
@RequestMapping("/api/admin/channels")
public class ChannelController {

    private final ChannelRepository channelRepository;

    public ChannelController(ChannelRepository channelRepository) {
        this.channelRepository = channelRepository;
    }

    /**
     * Get all channels with optional status filter
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<List<Channel>> getChannels(
            @RequestParam(required = false) String status
    ) throws ExecutionException, InterruptedException {
        List<Channel> channels;
        if (status != null) {
            channels = channelRepository.findByStatus(status);
        } else {
            channels = channelRepository.findByStatus("approved"); // Default to approved
        }
        return ResponseEntity.ok(channels);
    }

    /**
     * Get channels by category
     * BACKEND-PERF-01: Cached for 15 minutes
     */
    @GetMapping("/category/{categoryId}")
    @Cacheable(value = CacheConfig.CACHE_CHANNELS, key = "'category-' + #categoryId")
    public ResponseEntity<List<Channel>> getChannelsByCategory(@PathVariable String categoryId)
            throws ExecutionException, InterruptedException {
        List<Channel> channels = channelRepository.findByCategoryId(categoryId);
        return ResponseEntity.ok(channels);
    }

    /**
     * Get channel by ID
     * BACKEND-PERF-01: Cached for 15 minutes
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    @Cacheable(value = CacheConfig.CACHE_CHANNELS, key = "#id")
    public ResponseEntity<Channel> getChannelById(@PathVariable String id)
            throws ExecutionException, InterruptedException {
        return channelRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Submit channel for approval (moderator or admin)
     * BACKEND-PERF-01: Evict channel cache on create
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    @CacheEvict(value = CacheConfig.CACHE_CHANNELS, allEntries = true)
    public ResponseEntity<Channel> createChannel(
            @RequestBody Channel channel,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException {
        // Check if channel already exists
        var existing = channelRepository.findByYoutubeId(channel.getYoutubeId());
        if (existing.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        channel.setSubmittedBy(user.getUid());

        // Admins can auto-approve, moderators submit as pending
        if (user.isAdmin()) {
            channel.setStatus("approved");
            channel.setApprovedBy(user.getUid());
        } else {
            channel.setStatus("pending");
        }

        Channel saved = channelRepository.save(channel);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Approve channel (admin only)
     * BACKEND-PERF-01: Evict channel cache on approve
     */
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = CacheConfig.CACHE_CHANNELS, allEntries = true)
    public ResponseEntity<Channel> approveChannel(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        channel.setStatus("approved");
        channel.setApprovedBy(user.getUid());
        Channel updated = channelRepository.save(channel);
        return ResponseEntity.ok(updated);
    }

    /**
     * Reject channel (admin only)
     * BACKEND-PERF-01: Evict channel cache on reject
     */
    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = CacheConfig.CACHE_CHANNELS, allEntries = true)
    public ResponseEntity<Channel> rejectChannel(@PathVariable String id)
            throws ExecutionException, InterruptedException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        channel.setStatus("rejected");
        Channel updated = channelRepository.save(channel);
        return ResponseEntity.ok(updated);
    }

    /**
     * Update channel exclusions (bulk update)
     */
    @PutMapping("/{id}/exclusions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel> updateExclusions(
            @PathVariable String id,
            @RequestBody Channel.ExcludedItems excludedItems
    ) throws ExecutionException, InterruptedException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        channel.setExcludedItems(excludedItems);
        Channel updated = channelRepository.save(channel);
        return ResponseEntity.ok(updated);
    }

    /**
     * Get channel exclusions
     */
    @GetMapping("/{id}/exclusions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel.ExcludedItems> getExclusions(@PathVariable String id)
            throws ExecutionException, InterruptedException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        Channel.ExcludedItems excluded = channel.getExcludedItems();
        if (excluded == null) {
            excluded = new Channel.ExcludedItems();
        }
        return ResponseEntity.ok(excluded);
    }

    /**
     * Add a single exclusion to channel
     * @param type The type of content to exclude: video, playlist, livestream, short, post
     * @param youtubeId The YouTube ID of the content to exclude
     */
    @PostMapping("/{id}/exclusions/{type}/{youtubeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel.ExcludedItems> addExclusion(
            @PathVariable String id,
            @PathVariable String type,
            @PathVariable String youtubeId
    ) throws ExecutionException, InterruptedException {
        // Validate YouTube ID format (basic validation)
        if (youtubeId == null || youtubeId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        Channel.ExcludedItems excluded = channel.getExcludedItems();
        if (excluded == null) {
            excluded = new Channel.ExcludedItems();
        }

        // Add to appropriate list based on type
        boolean added = false;
        switch (type.toLowerCase()) {
            case "video":
                if (!excluded.getVideos().contains(youtubeId)) {
                    excluded.getVideos().add(youtubeId);
                    added = true;
                }
                break;
            case "playlist":
                if (!excluded.getPlaylists().contains(youtubeId)) {
                    excluded.getPlaylists().add(youtubeId);
                    added = true;
                }
                break;
            case "livestream":
                if (!excluded.getLiveStreams().contains(youtubeId)) {
                    excluded.getLiveStreams().add(youtubeId);
                    added = true;
                }
                break;
            case "short":
                if (!excluded.getShorts().contains(youtubeId)) {
                    excluded.getShorts().add(youtubeId);
                    added = true;
                }
                break;
            case "post":
                if (!excluded.getPosts().contains(youtubeId)) {
                    excluded.getPosts().add(youtubeId);
                    added = true;
                }
                break;
            default:
                return ResponseEntity.badRequest().build();
        }

        if (added) {
            channel.setExcludedItems(excluded);
            channel.touch();
            channelRepository.save(channel);
        }

        return ResponseEntity.ok(excluded);
    }

    /**
     * Remove a single exclusion from channel
     * @param type The type of content to remove exclusion from: video, playlist, livestream, short, post
     * @param youtubeId The YouTube ID of the content to remove exclusion from
     */
    @DeleteMapping("/{id}/exclusions/{type}/{youtubeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel.ExcludedItems> removeExclusion(
            @PathVariable String id,
            @PathVariable String type,
            @PathVariable String youtubeId
    ) throws ExecutionException, InterruptedException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        Channel.ExcludedItems excluded = channel.getExcludedItems();
        if (excluded == null) {
            return ResponseEntity.notFound().build();
        }

        // Remove from appropriate list based on type
        boolean removed = false;
        switch (type.toLowerCase()) {
            case "video":
                removed = excluded.getVideos().remove(youtubeId);
                break;
            case "playlist":
                removed = excluded.getPlaylists().remove(youtubeId);
                break;
            case "livestream":
                removed = excluded.getLiveStreams().remove(youtubeId);
                break;
            case "short":
                removed = excluded.getShorts().remove(youtubeId);
                break;
            case "post":
                removed = excluded.getPosts().remove(youtubeId);
                break;
            default:
                return ResponseEntity.badRequest().build();
        }

        if (removed) {
            channel.setExcludedItems(excluded);
            channel.touch();
            channelRepository.save(channel);
        }

        return ResponseEntity.ok(excluded);
    }

    /**
     * Delete channel (admin only)
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteChannel(@PathVariable String id)
            throws ExecutionException, InterruptedException {
        if (!channelRepository.findById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        channelRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
