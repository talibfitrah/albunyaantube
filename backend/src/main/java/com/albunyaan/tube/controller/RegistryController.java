package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.SubmitterNoteUpdateRequest;
import com.albunyaan.tube.dto.registry.BulkPreviewRequest;
import com.albunyaan.tube.dto.registry.BulkPreviewResponse;
import com.albunyaan.tube.dto.registry.BulkSubmitRequest;
import com.albunyaan.tube.dto.registry.BulkSubmitResponse;
import com.albunyaan.tube.service.BulkSubmissionService;
import jakarta.validation.Valid;
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
import java.util.Set;
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

    private static final Set<String> VALID_STATUSES = Set.of("APPROVED", "PENDING", "REJECTED");

    /** Statuses where a submitter is still allowed to edit/delete their own row. */
    private static final Set<String> SUBMITTER_OWNED_STATUSES = Set.of("PENDING", "REQUEST_CHANGES");

    /** Server-side cap on submitterNote so users can't blow up Firestore docs with megabyte essays. */
    static final int MAX_SUBMITTER_NOTE_LEN = 1000;
    // Cubic R5 P1: original broader range [​-‏‪-‮⁠-⁯﻿]
    // also stripped U+200C ZWNJ, U+200D ZWJ, U+200E LRM, U+200F RLM — all LEGITIMATE
    // characters required by Arabic/Persian joining and bidi rendering and by emoji ZWJ
    // sequences. Stripping them corrupted Arabic content (an Arabic-first app).
    //
    // Narrowed to actual spoofing / invisible-content chars only:
    //   U+200B  ZWSP        — invisible space, content spoofing
    //   U+202A-E LRE/RLE/PDF/LRO/RLO — bidi overrides, RTL/LTR rendering hijack
    //   U+2060  WJ          — word joiner, invisible
    //   U+FEFF  BOM/ZWNBSP  — should never appear mid-string
    // Compile once, not on every call (cubic R3 P2).
    private static final java.util.regex.Pattern ZW_BIDI_CONTROLS =
            java.util.regex.Pattern.compile("[\\u200B\\u202A-\\u202E\\u2060\\uFEFF]");

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final AuditLogService auditLogService;
    private final PublicContentCacheService publicContentCacheService;
    private final SortOrderService sortOrderService;
    private final com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache;
    private final BulkSubmissionService bulkSubmissionService;

    public RegistryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            AuditLogService auditLogService,
            PublicContentCacheService publicContentCacheService,
            SortOrderService sortOrderService,
            com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache,
            BulkSubmissionService bulkSubmissionService
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.auditLogService = auditLogService;
        this.publicContentCacheService = publicContentCacheService;
        this.sortOrderService = sortOrderService;
        this.workspaceExclusionsCache = workspaceExclusionsCache;
        this.bulkSubmissionService = bulkSubmissionService;
    }

    /**
     * Trim, blank-to-null, and length-check a submitter note coming in from the wire.
     *
     * @throws IllegalArgumentException when the trimmed value exceeds {@link #MAX_SUBMITTER_NOTE_LEN}
     */
    static String sanitizeSubmitterNote(String raw) {
        if (raw == null) return null;
        // Cubic R4 P2: reject impossibly-long payloads BEFORE running the regex, so
        // a multi-hundred-KB body of bidi/zero-width junk doesn't waste CPU on a
        // full-string scan only to be rejected at the length check below. 4× cap
        // is generous enough that legitimate Unicode payloads (e.g. emoji surrogate
        // pairs that strip() doesn't shrink) still pass through.
        if (raw.length() > MAX_SUBMITTER_NOTE_LEN * 4) {
            throw new IllegalArgumentException("submitterNote impossibly long: " + raw.length());
        }
        // strip() (Java 11+) handles Unicode whitespace (NBSP U+00A0, etc.) but NOT
        // ZWSP / bidi-override controls (see ZW_BIDI_CONTROLS above for the exact
        // narrowed range — ZWNJ/ZWJ/LRM/RLM are preserved for Arabic). Without scrubbing:
        //   (a) a "note" of pure ZWNJ/ZWJ/BOM bypasses the empty-check as a non-null
        //       string of invisible garbage that admin reviewers can't see;
        //   (b) RLO (U+202E) injected anywhere in the note will hijack RTL/LTR
        //       rendering of surrounding admin UI text — a spoofing vector.
        // Scrub the disallowed range, then re-strip so any whitespace they were
        // masking collapses to empty correctly.
        String stripped = ZW_BIDI_CONTROLS.matcher(raw.strip()).replaceAll("").strip();
        if (stripped.isEmpty()) return null;
        if (stripped.length() > MAX_SUBMITTER_NOTE_LEN) {
            throw new IllegalArgumentException("submitterNote exceeds max length " + MAX_SUBMITTER_NOTE_LEN);
        }
        return stripped;
    }

    /**
     * Normalize status and approvedBy for a new registry item based on user role.
     * Non-admins always get PENDING. Admins can specify a valid status or default to APPROVED.
     *
     * @return the normalized status, or null if the admin-provided status is invalid (caller should return 400)
     */
    private String normalizeStatusAndApprovedBy(FirebaseUserDetails user, String requestedStatus,
                                                 java.util.function.Consumer<String> setStatus,
                                                 java.util.function.Consumer<String> setApprovedBy) {
        if (!user.isAdmin()) {
            setStatus.accept("PENDING");
            setApprovedBy.accept(null);
            return "PENDING";
        }
        if (requestedStatus == null || requestedStatus.isEmpty()) {
            setStatus.accept("APPROVED");
            setApprovedBy.accept(user.getUid());
            return "APPROVED";
        }
        String normalized = requestedStatus.toUpperCase(java.util.Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            return null; // invalid status
        }
        setStatus.accept(normalized);
        if ("APPROVED".equals(normalized)) {
            setApprovedBy.accept(user.getUid());
        } else {
            setApprovedBy.accept(null);
        }
        return normalized;
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
        String normalized = status.toUpperCase(java.util.Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            return ResponseEntity.badRequest().build();
        }
        List<Channel> channels = channelRepository.findByStatus(normalized, limit);
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
                Channel ex = existing.get();
                if ("REQUEST_CHANGES".equals(ex.getStatus()) && user.getUid().equals(ex.getSubmittedBy())) {
                    // Plan E: re-submit of an admin-bounced row. Flip back to PENDING.
                    ex.setStatus("PENDING");
                    ex.setApprovalMetadata(null);
                    ex.setUpdatedAt(com.google.cloud.Timestamp.now());
                    if (channel.getCategoryIds() != null) ex.setCategoryIds(channel.getCategoryIds());
                    // Only overwrite submitterNote if the resubmit body explicitly provided a NON-EMPTY one.
                    // null OR "" on the body means "I'm not touching the note" (gstack R6 P2: many
                    // HTTP clients always send the key with "" rather than omitting it; the prior
                    // null-only check silently wiped the existing note for those clients). The
                    // PATCH .../submitter-note endpoint is the authoritative way to clear it.
                    // Cubic R4 P2: sanitize first, then decide. sanitize collapses
                    // whitespace + zero-width + bidi-override chars to null, so the
                    // null-check below correctly treats all "no real content" payloads
                    // as "don't touch" — no special-case isBlank() / isEmpty() needed.
                    String sanitizedChannelNote;
                    try {
                        sanitizedChannelNote = sanitizeSubmitterNote(channel.getSubmitterNote());
                    } catch (IllegalArgumentException tooLong) {
                        return ResponseEntity.badRequest().build();
                    }
                    if (sanitizedChannelNote != null) {
                        ex.setSubmitterNote(sanitizedChannelNote);
                    }
                    // Cubic R5 P1: optimistic concurrency. Two concurrent resubmits,
                    // or a resubmit racing an admin's `requestChanges` retry, would
                    // otherwise silently overwrite each other. `saveIfStatus` runs
                    // the write inside a Firestore tx that re-asserts the current
                    // status is REQUEST_CHANGES; the loser gets IllegalStateException
                    // → we return 409 so the client can re-fetch.
                    try {
                        channelRepository.saveIfStatus(ex, "REQUEST_CHANGES");
                    } catch (IllegalStateException race) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).build();
                    }
                    auditLogService.log("channel_resubmitted_after_changes", "channel", ex.getId(), user);
                    return ResponseEntity.ok(ex);
                }
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        // Clear server-controlled fields to prevent mass assignment:
        // - id=null forces auto-generation in repository (prevents document overwrite)
        // - createdAt set to server time (prevents timestamp forgery)
        // - approval/validation metadata cleared (only set by approval workflow)
        channel.setId(null);
        channel.setCreatedAt(com.google.cloud.Timestamp.now());
        channel.setApprovalMetadata(null);
        channel.setValidationStatus(null);
        channel.setLastValidatedAt(null);
        channel.setDisplayOrder(null);
        channel.setSubmittedBy(user.getUid());
        try {
            channel.setSubmitterNote(sanitizeSubmitterNote(channel.getSubmitterNote()));
        } catch (IllegalArgumentException tooLong) {
            return ResponseEntity.badRequest().build();
        }

        String status = normalizeStatusAndApprovedBy(user, channel.getStatus(),
                channel::setStatus, channel::setApprovedBy);
        if (status == null) {
            return ResponseEntity.badRequest().build();
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
     * Submitter-owned: update the free-text "why I'm suggesting this" note on a row the
     * caller submitted while it's still PENDING or REQUEST_CHANGES. APPROVED/REJECTED rows
     * are immutable from the submitter's side — admin already adjudicated them.
     */
    @PatchMapping("/channels/{id}/submitter-note")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Void> updateChannelSubmitterNote(
            @PathVariable String id,
            @RequestBody SubmitterNoteUpdateRequest request,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel existing = channelRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUid().equals(existing.getSubmittedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String expectedStatus = existing.getStatus();
        if (!SUBMITTER_OWNED_STATUSES.contains(expectedStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            existing.setSubmitterNote(sanitizeSubmitterNote(request.getSubmitterNote()));
        } catch (IllegalArgumentException tooLong) {
            return ResponseEntity.badRequest().build();
        }
        existing.setUpdatedAt(com.google.cloud.Timestamp.now());
        // Cubic R3 P1: TOCTOU — between the status check above and the save, an admin
        // could approve/reject the row. saveIfStatus runs the write inside a Firestore
        // transaction that re-asserts the status, so a concurrent adjudication is
        // surfaced as 409 instead of silently overwriting an APPROVED/REJECTED row.
        try {
            channelRepository.saveIfStatus(existing, expectedStatus);
        } catch (IllegalStateException raced) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        auditLogService.log("channel_submitter_note_updated", "channel", id, user);
        // Cubic R5 P1: returning Channel here serializes a body that has the
        // submitterNote stripped (@JsonProperty WRITE_ONLY). Use 204 No Content to
        // avoid handing the caller a body where the field they just set is null.
        return ResponseEntity.noContent().build();
    }

    /**
     * Submitter-owned: delete a row the caller submitted while it's still PENDING or
     * REQUEST_CHANGES. APPROVED/REJECTED rows must be removed through the admin DELETE.
     */
    @DeleteMapping("/channels/{id}/submission")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Void> deleteOwnChannelSubmission(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel existing = channelRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUid().equals(existing.getSubmittedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!SUBMITTER_OWNED_STATUSES.contains(existing.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // Cubic R3 P1: TOCTOU — re-assert the status inside a transaction so a row that
        // an admin approves mid-flight isn't silently deleted out from under the public
        // cache. Cubic R3 P2: SUBMITTER_OWNED_STATUSES is {PENDING, REQUEST_CHANGES},
        // neither of which is ever served by PublicContentCacheService — so the cache
        // evict here is unnecessary work for every user on every self-delete. Removed.
        try {
            channelRepository.deleteByIdIfStatusIn(id, SUBMITTER_OWNED_STATUSES);
        } catch (IllegalStateException raced) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException vanished) {
            // Cubic R4 P2: row was deleted between findById and the transaction (e.g.
            // admin DELETE racing submitter DELETE). Surface as 404, not 500.
            return ResponseEntity.notFound().build();
        }
        auditLogService.log("channel_submission_deleted", "channel", id, user);
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
        String normalized = status.toUpperCase(java.util.Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            return ResponseEntity.badRequest().build();
        }
        List<Playlist> playlists = playlistRepository.findByStatus(normalized, limit);
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
                Playlist ex = existing.get();
                if ("REQUEST_CHANGES".equals(ex.getStatus()) && user.getUid().equals(ex.getSubmittedBy())) {
                    // Plan E: re-submit of an admin-bounced row. Flip back to PENDING.
                    ex.setStatus("PENDING");
                    ex.setApprovalMetadata(null);
                    ex.setUpdatedAt(com.google.cloud.Timestamp.now());
                    if (playlist.getCategoryIds() != null) ex.setCategoryIds(playlist.getCategoryIds());
                    // Cubic R4 P2: sanitize first; see channel resubmit above.
                    String sanitizedPlaylistNote;
                    try {
                        sanitizedPlaylistNote = sanitizeSubmitterNote(playlist.getSubmitterNote());
                    } catch (IllegalArgumentException tooLong) {
                        return ResponseEntity.badRequest().build();
                    }
                    if (sanitizedPlaylistNote != null) {
                        ex.setSubmitterNote(sanitizedPlaylistNote);
                    }
                    // Cubic R5 P1: see channel resubmit path — optimistic concurrency.
                    try {
                        playlistRepository.saveIfStatus(ex, "REQUEST_CHANGES");
                    } catch (IllegalStateException race) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).build();
                    }
                    auditLogService.log("playlist_resubmitted_after_changes", "playlist", ex.getId(), user);
                    return ResponseEntity.ok(ex);
                }
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        // Clear server-controlled fields to prevent mass assignment
        playlist.setId(null);
        playlist.setCreatedAt(com.google.cloud.Timestamp.now());
        playlist.setApprovalMetadata(null);
        playlist.setValidationStatus(null);
        playlist.setLastValidatedAt(null);
        playlist.setDisplayOrder(null);
        playlist.setSubmittedBy(user.getUid());
        try {
            playlist.setSubmitterNote(sanitizeSubmitterNote(playlist.getSubmitterNote()));
        } catch (IllegalArgumentException tooLong) {
            return ResponseEntity.badRequest().build();
        }

        String playlistStatus = normalizeStatusAndApprovedBy(user, playlist.getStatus(),
                playlist::setStatus, playlist::setApprovedBy);
        if (playlistStatus == null) {
            return ResponseEntity.badRequest().build();
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

    /** Submitter-owned: see {@link #updateChannelSubmitterNote(String, SubmitterNoteUpdateRequest, FirebaseUserDetails)}. */
    @PatchMapping("/playlists/{id}/submitter-note")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Void> updatePlaylistSubmitterNote(
            @PathVariable String id,
            @RequestBody SubmitterNoteUpdateRequest request,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist existing = playlistRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUid().equals(existing.getSubmittedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String expectedStatus = existing.getStatus();
        if (!SUBMITTER_OWNED_STATUSES.contains(expectedStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            existing.setSubmitterNote(sanitizeSubmitterNote(request.getSubmitterNote()));
        } catch (IllegalArgumentException tooLong) {
            return ResponseEntity.badRequest().build();
        }
        existing.setUpdatedAt(com.google.cloud.Timestamp.now());
        // Cubic R3 P1: TOCTOU — saveIfStatus re-asserts the status atomically.
        try {
            playlistRepository.saveIfStatus(existing, expectedStatus);
        } catch (IllegalStateException raced) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        auditLogService.log("playlist_submitter_note_updated", "playlist", id, user);
        // Cubic R5 P1: 204 No Content — see channel endpoint.
        return ResponseEntity.noContent().build();
    }

    /** Submitter-owned: see {@link #deleteOwnChannelSubmission(String, FirebaseUserDetails)}. */
    @DeleteMapping("/playlists/{id}/submission")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Void> deleteOwnPlaylistSubmission(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist existing = playlistRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUid().equals(existing.getSubmittedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!SUBMITTER_OWNED_STATUSES.contains(existing.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // Cubic R3 P1+P2: TOCTOU-safe delete, drop unnecessary public cache evict.
        try {
            playlistRepository.deleteByIdIfStatusIn(id, SUBMITTER_OWNED_STATUSES);
        } catch (IllegalStateException raced) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException vanished) {
            return ResponseEntity.notFound().build();
        }
        auditLogService.log("playlist_submission_deleted", "playlist", id, user);
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
        String normalized = status.toUpperCase(java.util.Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            return ResponseEntity.badRequest().build();
        }
        List<Video> videos = videoRepository.findByStatus(normalized, limit);
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
                Video ex = existing.get();
                if ("REQUEST_CHANGES".equals(ex.getStatus()) && user.getUid().equals(ex.getSubmittedBy())) {
                    // Plan E: re-submit of an admin-bounced row. Flip back to PENDING.
                    ex.setStatus("PENDING");
                    ex.setApprovalMetadata(null);
                    ex.setUpdatedAt(com.google.cloud.Timestamp.now());
                    if (video.getCategoryIds() != null) ex.setCategoryIds(video.getCategoryIds());
                    // Cubic R4 P2: sanitize first; see channel resubmit above.
                    String sanitizedVideoNote;
                    try {
                        sanitizedVideoNote = sanitizeSubmitterNote(video.getSubmitterNote());
                    } catch (IllegalArgumentException tooLong) {
                        return ResponseEntity.badRequest().build();
                    }
                    if (sanitizedVideoNote != null) {
                        ex.setSubmitterNote(sanitizedVideoNote);
                    }
                    // Cubic R5 P1: see channel resubmit path — optimistic concurrency.
                    try {
                        videoRepository.saveIfStatus(ex, "REQUEST_CHANGES");
                    } catch (IllegalStateException race) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).build();
                    }
                    auditLogService.log("video_resubmitted_after_changes", "video", ex.getId(), user);
                    return ResponseEntity.ok(ex);
                }
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        // Clear server-controlled fields to prevent mass assignment
        video.setId(null);
        video.setCreatedAt(com.google.cloud.Timestamp.now());
        video.setApprovalMetadata(null);
        video.setValidationStatus(null);
        video.setLastValidatedAt(null);
        video.setDisplayOrder(null);
        video.setSubmittedBy(user.getUid());
        try {
            video.setSubmitterNote(sanitizeSubmitterNote(video.getSubmitterNote()));
        } catch (IllegalArgumentException tooLong) {
            return ResponseEntity.badRequest().build();
        }

        String videoStatus = normalizeStatusAndApprovedBy(user, video.getStatus(),
                video::setStatus, video::setApprovedBy);
        if (videoStatus == null) {
            return ResponseEntity.badRequest().build();
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

    /** Submitter-owned: see {@link #updateChannelSubmitterNote(String, SubmitterNoteUpdateRequest, FirebaseUserDetails)}. */
    @PatchMapping("/videos/{id}/submitter-note")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Void> updateVideoSubmitterNote(
            @PathVariable String id,
            @RequestBody SubmitterNoteUpdateRequest request,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Video existing = videoRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUid().equals(existing.getSubmittedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String expectedStatus = existing.getStatus();
        if (!SUBMITTER_OWNED_STATUSES.contains(expectedStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            existing.setSubmitterNote(sanitizeSubmitterNote(request.getSubmitterNote()));
        } catch (IllegalArgumentException tooLong) {
            return ResponseEntity.badRequest().build();
        }
        existing.setUpdatedAt(com.google.cloud.Timestamp.now());
        // Cubic R3 P1: TOCTOU — saveIfStatus re-asserts the status atomically.
        try {
            videoRepository.saveIfStatus(existing, expectedStatus);
        } catch (IllegalStateException raced) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        auditLogService.log("video_submitter_note_updated", "video", id, user);
        // Cubic R5 P1: 204 No Content — see channel endpoint.
        return ResponseEntity.noContent().build();
    }

    /** Submitter-owned: see {@link #deleteOwnChannelSubmission(String, FirebaseUserDetails)}. */
    @DeleteMapping("/videos/{id}/submission")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Void> deleteOwnVideoSubmission(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Video existing = videoRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUid().equals(existing.getSubmittedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!SUBMITTER_OWNED_STATUSES.contains(existing.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // Cubic R3 P1+P2: TOCTOU-safe delete, drop unnecessary public cache evict.
        try {
            videoRepository.deleteByIdIfStatusIn(id, SUBMITTER_OWNED_STATUSES);
        } catch (IllegalStateException raced) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException vanished) {
            return ResponseEntity.notFound().build();
        }
        auditLogService.log("video_submission_deleted", "video", id, user);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // BULK-01 (T9) — bulk preview + submit
    // -------------------------------------------------------------------------

    /**
     * BULK-01 (T9) — bulk preview. Validates ≤25 URLs, fans out NewPipe metadata fetches,
     * returns one row per URL with detected type + metadata + status (OK / DUPLICATE / ERROR).
     */
    @PostMapping("/bulk/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<BulkPreviewResponse> bulkPreview(
            @RequestBody @Valid BulkPreviewRequest req) {
        return ResponseEntity.ok(bulkSubmissionService.preview(req));
    }

    /**
     * BULK-01 (T9) — bulk submit. Takes the OK rows from a prior preview + resolved categories,
     * writes Firestore docs via {@link com.albunyaan.tube.service.RegistrySubmissionWriter},
     * returns per-row results.
     */
    @PostMapping("/bulk/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<BulkSubmitResponse> bulkSubmit(
            @RequestBody @Valid BulkSubmitRequest req,
            @AuthenticationPrincipal FirebaseUserDetails user) {
        return ResponseEntity.ok(bulkSubmissionService.submit(req, user.getUid(), user.isAdmin()));
    }
}

