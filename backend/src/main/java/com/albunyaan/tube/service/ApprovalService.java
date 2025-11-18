package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.util.CursorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.cloud.Timestamp;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * BACKEND-APPR-01: Approval Service
 *
 * Handles approval workflow for channels and playlists.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final CategoryRepository categoryRepository;
    private final ApprovalRepository approvalRepository;
    private final AuditLogService auditLogService;

    public ApprovalService(ChannelRepository channelRepository,
                          PlaylistRepository playlistRepository,
                          CategoryRepository categoryRepository,
                          ApprovalRepository approvalRepository,
                          AuditLogService auditLogService) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.categoryRepository = categoryRepository;
        this.approvalRepository = approvalRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Get pending approvals with filters and pagination.
     * Uses CursorUtils for opaque, URL-safe cursor tokens.
     *
     * For mixed-type queries (type=null), uses a merge-sort approach with
     * separate cursors for each collection to ensure monotonic pagination.
     */
    public CursorPageDto<PendingApprovalDto> getPendingApprovals(
            String type,
            String category,
            Integer limit,
            String cursor) throws ExecutionException, InterruptedException, TimeoutException {

        int pageSize = (limit != null && limit > 0) ? limit : 20;

        // Single-type queries use simpler logic
        if ("CHANNEL".equalsIgnoreCase(type)) {
            return getPendingChannelsOnly(category, pageSize, cursor);
        } else if ("PLAYLIST".equalsIgnoreCase(type)) {
            return getPendingPlaylistsOnly(category, pageSize, cursor);
        }

        // Mixed-type query: merge results from both collections
        return getPendingMixed(category, pageSize, cursor);
    }

    /**
     * Get pending channels only (single-type query).
     */
    private CursorPageDto<PendingApprovalDto> getPendingChannelsOnly(
            String category, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        ApprovalRepository.PaginatedResult<Channel> result;
        if (category != null && !category.isEmpty()) {
            result = approvalRepository.findPendingChannelsByCategoryWithCursor(category, pageSize, cursor);
        } else {
            result = approvalRepository.findPendingChannelsWithCursor(pageSize, cursor);
        }

        List<PendingApprovalDto> items = new ArrayList<>();
        for (Channel channel : result.getItems()) {
            items.add(channelToApprovalDto(channel));
        }

        String nextCursor = null;
        if (result.hasNext() && !items.isEmpty()) {
            PendingApprovalDto lastItem = items.get(items.size() - 1);
            CursorUtils.CursorData cursorData = new CursorUtils.CursorData(lastItem.getId());
            cursorData.withField("type", "CHANNEL");
            if (lastItem.getSubmittedAt() != null) {
                cursorData.withField("createdAt", lastItem.getSubmittedAt());
            }
            nextCursor = CursorUtils.encode(cursorData);
        }

        CursorPageDto<PendingApprovalDto> response = new CursorPageDto<>();
        response.setData(items);
        response.setPageInfo(new CursorPageDto.PageInfo(nextCursor));
        return response;
    }

    /**
     * Get pending playlists only (single-type query).
     */
    private CursorPageDto<PendingApprovalDto> getPendingPlaylistsOnly(
            String category, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        ApprovalRepository.PaginatedResult<Playlist> result;
        if (category != null && !category.isEmpty()) {
            result = approvalRepository.findPendingPlaylistsByCategoryWithCursor(category, pageSize, cursor);
        } else {
            result = approvalRepository.findPendingPlaylistsWithCursor(pageSize, cursor);
        }

        List<PendingApprovalDto> items = new ArrayList<>();
        for (Playlist playlist : result.getItems()) {
            items.add(playlistToApprovalDto(playlist));
        }

        String nextCursor = null;
        if (result.hasNext() && !items.isEmpty()) {
            PendingApprovalDto lastItem = items.get(items.size() - 1);
            CursorUtils.CursorData cursorData = new CursorUtils.CursorData(lastItem.getId());
            cursorData.withField("type", "PLAYLIST");
            if (lastItem.getSubmittedAt() != null) {
                cursorData.withField("createdAt", lastItem.getSubmittedAt());
            }
            nextCursor = CursorUtils.encode(cursorData);
        }

        CursorPageDto<PendingApprovalDto> response = new CursorPageDto<>();
        response.setData(items);
        response.setPageInfo(new CursorPageDto.PageInfo(nextCursor));
        return response;
    }

    /**
     * Get pending approvals from both channels and playlists (mixed-type query).
     *
     * Uses separate cursors for each collection to ensure monotonic pagination
     * across collections. The cursor encodes positions in both collections.
     */
    private CursorPageDto<PendingApprovalDto> getPendingMixed(
            String category, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Decode separate cursors for each collection
        String channelCursor = null;
        String playlistCursor = null;

        if (cursor != null && !cursor.isEmpty()) {
            try {
                CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
                if (cursorData != null) {
                    channelCursor = cursorData.getFieldAsString("channelCursor");
                    playlistCursor = cursorData.getFieldAsString("playlistCursor");
                }
            } catch (Exception e) {
                log.warn("Invalid cursor format: {}", cursor, e);
                throw new IllegalArgumentException("Invalid cursor format");
            }
        }

        // Fetch pageSize items from each collection (we'll merge and trim)
        ApprovalRepository.PaginatedResult<Channel> channelResult;
        ApprovalRepository.PaginatedResult<Playlist> playlistResult;

        if (category != null && !category.isEmpty()) {
            channelResult = approvalRepository.findPendingChannelsByCategoryWithCursor(category, pageSize, channelCursor);
            playlistResult = approvalRepository.findPendingPlaylistsByCategoryWithCursor(category, pageSize, playlistCursor);
        } else {
            channelResult = approvalRepository.findPendingChannelsWithCursor(pageSize, channelCursor);
            playlistResult = approvalRepository.findPendingPlaylistsWithCursor(pageSize, playlistCursor);
        }

        // Convert to DTOs with original indices for tracking
        List<IndexedDto> channelDtos = new ArrayList<>();
        for (int i = 0; i < channelResult.getItems().size(); i++) {
            Channel channel = channelResult.getItems().get(i);
            channelDtos.add(new IndexedDto(channelToApprovalDto(channel), i));
        }

        List<IndexedDto> playlistDtos = new ArrayList<>();
        for (int i = 0; i < playlistResult.getItems().size(); i++) {
            Playlist playlist = playlistResult.getItems().get(i);
            playlistDtos.add(new IndexedDto(playlistToApprovalDto(playlist), i));
        }

        // Merge-sort by submittedAt (newest first)
        List<PendingApprovalDto> merged = new ArrayList<>();
        int channelUsed = 0;
        int playlistUsed = 0;
        int ci = 0, pi = 0;

        while (merged.size() < pageSize && (ci < channelDtos.size() || pi < playlistDtos.size())) {
            if (ci >= channelDtos.size()) {
                merged.add(playlistDtos.get(pi).dto);
                playlistUsed = playlistDtos.get(pi).index + 1;
                pi++;
            } else if (pi >= playlistDtos.size()) {
                merged.add(channelDtos.get(ci).dto);
                channelUsed = channelDtos.get(ci).index + 1;
                ci++;
            } else {
                // Compare timestamps (newest first), with stable tiebreaker on ID
                Timestamp channelDate = channelDtos.get(ci).dto.getSubmittedAt();
                Timestamp playlistDate = playlistDtos.get(pi).dto.getSubmittedAt();

                boolean takeChannel;
                if (channelDate == null && playlistDate == null) {
                    // Stable tiebreaker: compare by ID
                    String channelId = channelDtos.get(ci).dto.getId();
                    String playlistId = playlistDtos.get(pi).dto.getId();
                    takeChannel = channelId.compareTo(playlistId) <= 0;
                } else if (channelDate == null) {
                    takeChannel = false;
                } else if (playlistDate == null) {
                    takeChannel = true;
                } else {
                    int cmp = channelDate.compareTo(playlistDate);
                    if (cmp == 0) {
                        // Equal timestamps: stable tiebreaker by ID
                        String channelId = channelDtos.get(ci).dto.getId();
                        String playlistId = playlistDtos.get(pi).dto.getId();
                        takeChannel = channelId.compareTo(playlistId) <= 0;
                    } else {
                        takeChannel = cmp > 0; // Newer first
                    }
                }

                if (takeChannel) {
                    merged.add(channelDtos.get(ci).dto);
                    channelUsed = channelDtos.get(ci).index + 1;
                    ci++;
                } else {
                    merged.add(playlistDtos.get(pi).dto);
                    playlistUsed = playlistDtos.get(pi).index + 1;
                    pi++;
                }
            }
        }

        // Determine if there's a next page
        boolean hasMoreChannels = channelResult.hasNext() || ci < channelDtos.size();
        boolean hasMorePlaylists = playlistResult.hasNext() || pi < playlistDtos.size();
        boolean hasNext = hasMoreChannels || hasMorePlaylists;

        // Generate composite cursor encoding positions in both collections
        String nextCursor = null;
        if (hasNext && !merged.isEmpty()) {
            // Find the last used item from each collection to build cursors
            String nextChannelCursor = null;
            String nextPlaylistCursor = null;

            if (channelUsed > 0 && channelUsed <= channelResult.getItems().size()) {
                Channel lastChannel = channelResult.getItems().get(channelUsed - 1);
                CursorUtils.CursorData channelCursorData = new CursorUtils.CursorData(lastChannel.getId());
                if (lastChannel.getCreatedAt() != null) {
                    channelCursorData.withField("createdAt", lastChannel.getCreatedAt());
                }
                nextChannelCursor = CursorUtils.encode(channelCursorData);
            } else if (channelCursor != null) {
                // No channels used this page, keep the previous cursor
                nextChannelCursor = channelCursor;
            }

            if (playlistUsed > 0 && playlistUsed <= playlistResult.getItems().size()) {
                Playlist lastPlaylist = playlistResult.getItems().get(playlistUsed - 1);
                CursorUtils.CursorData playlistCursorData = new CursorUtils.CursorData(lastPlaylist.getId());
                if (lastPlaylist.getCreatedAt() != null) {
                    playlistCursorData.withField("createdAt", lastPlaylist.getCreatedAt());
                }
                nextPlaylistCursor = CursorUtils.encode(playlistCursorData);
            } else if (playlistCursor != null) {
                // No playlists used this page, keep the previous cursor
                nextPlaylistCursor = playlistCursor;
            }

            // Create composite cursor with both positions
            CursorUtils.CursorData compositeCursor = new CursorUtils.CursorData("mixed");
            compositeCursor.withField("type", "MIXED");
            if (nextChannelCursor != null) {
                compositeCursor.withField("channelCursor", nextChannelCursor);
            }
            if (nextPlaylistCursor != null) {
                compositeCursor.withField("playlistCursor", nextPlaylistCursor);
            }
            nextCursor = CursorUtils.encode(compositeCursor);
        }

        CursorPageDto<PendingApprovalDto> response = new CursorPageDto<>();
        response.setData(merged);
        response.setPageInfo(new CursorPageDto.PageInfo(nextCursor));
        return response;
    }

    /**
     * Helper class to track original index during merge-sort.
     */
    private static class IndexedDto {
        final PendingApprovalDto dto;
        final int index;

        IndexedDto(PendingApprovalDto dto, int index) {
            this.dto = dto;
            this.index = index;
        }
    }

    /**
     * Approve a pending item
     */
    public ApprovalResponseDto approve(String id, ApprovalRequestDto request, String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Try to find as channel first
        Optional<Channel> channelOpt = channelRepository.findById(id);
        if (channelOpt.isPresent()) {
            return approveChannel(channelOpt.get(), request, actorUid, actorDisplayName);
        }

        // Try to find as playlist
        Optional<Playlist> playlistOpt = playlistRepository.findById(id);
        if (playlistOpt.isPresent()) {
            return approvePlaylist(playlistOpt.get(), request, actorUid, actorDisplayName);
        }

        throw new IllegalArgumentException("Item not found: " + id);
    }

    /**
     * Reject a pending item
     */
    public ApprovalResponseDto reject(String id, RejectionRequestDto request, String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Try to find as channel first
        Optional<Channel> channelOpt = channelRepository.findById(id);
        if (channelOpt.isPresent()) {
            return rejectChannel(channelOpt.get(), request, actorUid, actorDisplayName);
        }

        // Try to find as playlist
        Optional<Playlist> playlistOpt = playlistRepository.findById(id);
        if (playlistOpt.isPresent()) {
            return rejectPlaylist(playlistOpt.get(), request, actorUid, actorDisplayName);
        }

        throw new IllegalArgumentException("Item not found: " + id);
    }

    // Private helper methods

    private PendingApprovalDto channelToApprovalDto(Channel channel) {
        PendingApprovalDto dto = new PendingApprovalDto();
        dto.setId(channel.getId());
        dto.setType("CHANNEL");
        dto.setEntityId(channel.getId());
        dto.setTitle(channel.getName());
        dto.setSubmittedAt(channel.getCreatedAt());
        dto.setSubmittedBy(channel.getSubmittedBy());

        // Get first category name
        if (channel.getCategoryIds() != null && !channel.getCategoryIds().isEmpty()) {
            try {
                Optional<Category> catOpt = categoryRepository.findById(channel.getCategoryIds().get(0));
                if (catOpt.isPresent()) {
                    dto.setCategory(catOpt.get().getName());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch category for channel {}", channel.getId(), e);
            }
        }

        // Add metadata
        if (channel.getSubscribers() != null) {
            dto.addMetadata("subscriberCount", formatNumber(channel.getSubscribers()));
        }
        if (channel.getVideoCount() != null) {
            dto.addMetadata("videoCount", channel.getVideoCount());
        }

        return dto;
    }

    private PendingApprovalDto playlistToApprovalDto(Playlist playlist) {
        PendingApprovalDto dto = new PendingApprovalDto();
        dto.setId(playlist.getId());
        dto.setType("PLAYLIST");
        dto.setEntityId(playlist.getId());
        dto.setTitle(playlist.getTitle());
        dto.setSubmittedAt(playlist.getCreatedAt());
        dto.setSubmittedBy(playlist.getSubmittedBy());

        // Get first category name
        if (playlist.getCategoryIds() != null && !playlist.getCategoryIds().isEmpty()) {
            try {
                Optional<Category> catOpt = categoryRepository.findById(playlist.getCategoryIds().get(0));
                if (catOpt.isPresent()) {
                    dto.setCategory(catOpt.get().getName());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch category for playlist {}", playlist.getId(), e);
            }
        }

        // Add metadata
        if (playlist.getItemCount() != null) {
            dto.addMetadata("itemCount", playlist.getItemCount());
        }

        return dto;
    }

    private ApprovalResponseDto approveChannel(Channel channel, ApprovalRequestDto request,
                                               String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Update status
        channel.setStatus("APPROVED");
        channel.setApprovedBy(actorUid);
        channel.touch();

        // Apply category override if provided
        if (request.getCategoryOverride() != null) {
            channel.setCategoryIds(List.of(request.getCategoryOverride()));
        }

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        channel.setApprovalMetadata(metadata);

        // Save to Firestore
        channelRepository.save(channel);

        // Create audit log
        auditLogService.logApproval("channel", channel.getId(), actorUid, actorDisplayName, request.getReviewNotes());

        // Return response
        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("APPROVED");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(request.getReviewNotes());

        return response;
    }

    private ApprovalResponseDto approvePlaylist(Playlist playlist, ApprovalRequestDto request,
                                                String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Update status
        playlist.setStatus("APPROVED");
        playlist.setApprovedBy(actorUid);
        playlist.touch();

        // Apply category override if provided
        if (request.getCategoryOverride() != null) {
            playlist.setCategoryIds(List.of(request.getCategoryOverride()));
        }

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        playlist.setApprovalMetadata(metadata);

        // Save to Firestore
        playlistRepository.save(playlist);

        // Create audit log
        auditLogService.logApproval("playlist", playlist.getId(), actorUid, actorDisplayName, request.getReviewNotes());

        // Return response
        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("APPROVED");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(request.getReviewNotes());

        return response;
    }

    private ApprovalResponseDto rejectChannel(Channel channel, RejectionRequestDto request,
                                              String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Update status
        channel.setStatus("REJECTED");
        channel.touch();

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        metadata.setRejectionReason(request.getReason());
        channel.setApprovalMetadata(metadata);

        // Save to Firestore
        channelRepository.save(channel);

        // Create audit log
        Map<String, Object> details = new HashMap<>();
        details.put("reason", request.getReason());
        details.put("notes", request.getReviewNotes());
        auditLogService.logRejection("channel", channel.getId(), actorUid, actorDisplayName, details);

        // Return response
        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("REJECTED");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(request.getReviewNotes());

        return response;
    }

    private ApprovalResponseDto rejectPlaylist(Playlist playlist, RejectionRequestDto request,
                                               String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Update status
        playlist.setStatus("REJECTED");
        playlist.touch();

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        metadata.setRejectionReason(request.getReason());
        playlist.setApprovalMetadata(metadata);

        // Save to Firestore
        playlistRepository.save(playlist);

        // Create audit log
        Map<String, Object> details = new HashMap<>();
        details.put("reason", request.getReason());
        details.put("notes", request.getReviewNotes());
        auditLogService.logRejection("playlist", playlist.getId(), actorUid, actorDisplayName, details);

        // Return response
        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("REJECTED");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(request.getReviewNotes());

        return response;
    }

    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }
}

