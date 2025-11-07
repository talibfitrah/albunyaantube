package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * BACKEND-APPR-01: Approval Service
 *
 * Handles approval workflow for channels and playlists.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final Firestore firestore;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final CategoryRepository categoryRepository;
    private final AuditLogService auditLogService;

    public ApprovalService(Firestore firestore,
                          ChannelRepository channelRepository,
                          PlaylistRepository playlistRepository,
                          CategoryRepository categoryRepository,
                          AuditLogService auditLogService) {
        this.firestore = firestore;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.categoryRepository = categoryRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Get pending approvals with filters and pagination
     */
    public CursorPageDto<PendingApprovalDto> getPendingApprovals(
            String type,
            String category,
            Integer limit,
            String cursor) throws ExecutionException, InterruptedException {

        List<PendingApprovalDto> items = new ArrayList<>();
        String nextCursor = null;
        int pageSize = (limit != null && limit > 0) ? limit : 20;

        // Query channels if type is CHANNEL or null
        if (type == null || "CHANNEL".equalsIgnoreCase(type)) {
            List<Channel> channels = queryPendingChannels(category, pageSize, cursor);
            for (Channel channel : channels) {
                PendingApprovalDto dto = channelToApprovalDto(channel);
                items.add(dto);
            }
        }

        // Query playlists if type is PLAYLIST or null
        if (type == null || "PLAYLIST".equalsIgnoreCase(type)) {
            List<Playlist> playlists = queryPendingPlaylists(category, pageSize, cursor);
            for (Playlist playlist : playlists) {
                PendingApprovalDto dto = playlistToApprovalDto(playlist);
                items.add(dto);
            }
        }

        // Sort by submittedAt (newest first)
        items.sort((a, b) -> {
            if (a.getSubmittedAt() == null || b.getSubmittedAt() == null) return 0;
            return b.getSubmittedAt().compareTo(a.getSubmittedAt());
        });

        // Apply limit
        if (items.size() > pageSize) {
            items = items.subList(0, pageSize);
            // Use last item's ID as cursor
            nextCursor = items.get(items.size() - 1).getId();
        }

        CursorPageDto<PendingApprovalDto> result = new CursorPageDto<>();
        result.setData(items);
        result.setPageInfo(new CursorPageDto.PageInfo(nextCursor));

        return result;
    }

    /**
     * Approve a pending item
     */
    public ApprovalResponseDto approve(String id, ApprovalRequestDto request, String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException {

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
            throws ExecutionException, InterruptedException {

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

    private List<Channel> queryPendingChannels(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException {
        Query query = firestore.collection("channels")
                .whereEqualTo("status", "PENDING")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit);

        if (cursor != null) {
            // In a real implementation, we'd use startAfter with the document snapshot
            // For simplicity, we'll skip this for now
        }

        List<Channel> channels = new ArrayList<>();
        var snapshot = query.get().get();
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            Channel channel = doc.toObject(Channel.class);
            channel.setId(doc.getId());

            // Filter by category if specified
            if (category != null) {
                if (channel.getCategoryIds() != null && channel.getCategoryIds().contains(category)) {
                    channels.add(channel);
                }
            } else {
                channels.add(channel);
            }
        }

        return channels;
    }

    private List<Playlist> queryPendingPlaylists(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException {
        Query query = firestore.collection("playlists")
                .whereEqualTo("status", "PENDING")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit);

        List<Playlist> playlists = new ArrayList<>();
        var snapshot = query.get().get();
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            Playlist playlist = doc.toObject(Playlist.class);
            playlist.setId(doc.getId());

            // Filter by category if specified
            if (category != null) {
                if (playlist.getCategoryIds() != null && playlist.getCategoryIds().contains(category)) {
                    playlists.add(playlist);
                }
            } else {
                playlists.add(playlist);
            }
        }

        return playlists;
    }

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
            throws ExecutionException, InterruptedException {

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
            throws ExecutionException, InterruptedException {

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
            throws ExecutionException, InterruptedException {

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
            throws ExecutionException, InterruptedException {

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

