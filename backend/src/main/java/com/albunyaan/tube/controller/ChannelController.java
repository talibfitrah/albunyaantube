package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
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
     */
    @GetMapping("/category/{categoryId}")
    public ResponseEntity<List<Channel>> getChannelsByCategory(@PathVariable String categoryId)
            throws ExecutionException, InterruptedException {
        List<Channel> channels = channelRepository.findByCategoryId(categoryId);
        return ResponseEntity.ok(channels);
    }

    /**
     * Get channel by ID
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Channel> getChannelById(@PathVariable String id)
            throws ExecutionException, InterruptedException {
        return channelRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Submit channel for approval (moderator or admin)
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
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
     */
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
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
     */
    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
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
     * Update channel exclusions
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
