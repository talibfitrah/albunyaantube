package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.util.CursorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.cloud.Timestamp;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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

    private static final java.util.Set<String> VALID_TYPES = java.util.Set.of("CHANNEL", "PLAYLIST", "VIDEO");
    private static final java.util.Set<String> VALID_STATUSES = java.util.Set.of("PENDING", "APPROVED", "REJECTED", "REQUEST_CHANGES");

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;
    private final ApprovalRepository approvalRepository;
    private final AuditLogService auditLogService;
    private final SortOrderService sortOrderService;
    private final StreamIndexService streamIndexService;
    private final UserRepository userRepository;
    private final ImportGraduationService importGraduationService;

    public ApprovalService(ChannelRepository channelRepository,
                          PlaylistRepository playlistRepository,
                          VideoRepository videoRepository,
                          CategoryRepository categoryRepository,
                          ApprovalRepository approvalRepository,
                          AuditLogService auditLogService,
                          SortOrderService sortOrderService,
                          StreamIndexService streamIndexService,
                          UserRepository userRepository,
                          ImportGraduationService importGraduationService) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
        this.approvalRepository = approvalRepository;
        this.auditLogService = auditLogService;
        this.sortOrderService = sortOrderService;
        this.streamIndexService = streamIndexService;
        this.userRepository = userRepository;
        this.importGraduationService = importGraduationService;
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

        int pageSize = Math.min((limit != null && limit > 0) ? limit : 20, 100);

        // Validate type parameter if provided
        if (type != null && !type.isEmpty()) {
            String normalizedType = type.toUpperCase();
            if (!VALID_TYPES.contains(normalizedType)) {
                String safeType = type.length() > 20 ? type.substring(0, 20) + "..." : type;
                throw new IllegalArgumentException("Invalid type: " + safeType + ". Must be one of: CHANNEL, PLAYLIST, VIDEO");
            }
        }

        // Single-type queries use simpler logic
        if ("CHANNEL".equalsIgnoreCase(type)) {
            return getPendingChannelsOnly(category, pageSize, cursor);
        } else if ("PLAYLIST".equalsIgnoreCase(type)) {
            return getPendingPlaylistsOnly(category, pageSize, cursor);
        } else if ("VIDEO".equalsIgnoreCase(type)) {
            return getPendingVideosOnly(category, pageSize, cursor);
        }

        // Mixed-type query: merge results from all three collections
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
     * Get pending videos only (single-type query).
     */
    private CursorPageDto<PendingApprovalDto> getPendingVideosOnly(
            String category, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        ApprovalRepository.PaginatedResult<Video> result;
        if (category != null && !category.isEmpty()) {
            result = approvalRepository.findPendingVideosByCategoryWithCursor(category, pageSize, cursor);
        } else {
            result = approvalRepository.findPendingVideosWithCursor(pageSize, cursor);
        }

        List<PendingApprovalDto> items = new ArrayList<>();
        for (Video video : result.getItems()) {
            items.add(videoToApprovalDto(video));
        }

        String nextCursor = null;
        if (result.hasNext() && !items.isEmpty()) {
            PendingApprovalDto lastItem = items.get(items.size() - 1);
            CursorUtils.CursorData cursorData = new CursorUtils.CursorData(lastItem.getId());
            cursorData.withField("type", "VIDEO");
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
     * Get pending approvals from all three collections (mixed-type query).
     *
     * Uses separate cursors for each collection to ensure monotonic pagination.
     * The cursor encodes positions in all three collections.
     */
    private CursorPageDto<PendingApprovalDto> getPendingMixed(
            String category, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Decode separate cursors for each collection
        String channelCursor = null;
        String playlistCursor = null;
        String videoCursor = null;

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData;
            try {
                cursorData = CursorUtils.decode(cursor);
            } catch (Exception e) {
                log.warn("Invalid cursor format: {}", cursor, e);
                throw new IllegalArgumentException("Invalid cursor format");
            }
            if (cursorData == null) {
                // CursorUtils.decode degrades malformed input to null. The
                // approvals API requires a valid cursor (it carries composite
                // sub-cursors for channels/playlists/videos), so reject loudly.
                log.warn("Invalid cursor format (could not decode): {}", cursor);
                throw new IllegalArgumentException("Invalid cursor format");
            }
            channelCursor = cursorData.getFieldAsString("channelCursor");
            playlistCursor = cursorData.getFieldAsString("playlistCursor");
            videoCursor = cursorData.getFieldAsString("videoCursor");
        }

        // Fetch pageSize items from each collection (we'll merge and trim)
        ApprovalRepository.PaginatedResult<Channel> channelResult;
        ApprovalRepository.PaginatedResult<Playlist> playlistResult;
        ApprovalRepository.PaginatedResult<Video> videoResult;

        if (category != null && !category.isEmpty()) {
            channelResult = approvalRepository.findPendingChannelsByCategoryWithCursor(category, pageSize, channelCursor);
            playlistResult = approvalRepository.findPendingPlaylistsByCategoryWithCursor(category, pageSize, playlistCursor);
            videoResult = approvalRepository.findPendingVideosByCategoryWithCursor(category, pageSize, videoCursor);
        } else {
            channelResult = approvalRepository.findPendingChannelsWithCursor(pageSize, channelCursor);
            playlistResult = approvalRepository.findPendingPlaylistsWithCursor(pageSize, playlistCursor);
            videoResult = approvalRepository.findPendingVideosWithCursor(pageSize, videoCursor);
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

        List<IndexedDto> videoDtos = new ArrayList<>();
        for (int i = 0; i < videoResult.getItems().size(); i++) {
            Video video = videoResult.getItems().get(i);
            videoDtos.add(new IndexedDto(videoToApprovalDto(video), i));
        }

        // 3-way merge-sort by submittedAt (newest first)
        List<PendingApprovalDto> merged = new ArrayList<>();
        int channelUsed = 0, playlistUsed = 0, videoUsed = 0;
        int ci = 0, pi = 0, vi = 0;

        while (merged.size() < pageSize && (ci < channelDtos.size() || pi < playlistDtos.size() || vi < videoDtos.size())) {
            Timestamp ct = ci < channelDtos.size() ? channelDtos.get(ci).dto.getSubmittedAt() : null;
            Timestamp pt = pi < playlistDtos.size() ? playlistDtos.get(pi).dto.getSubmittedAt() : null;
            Timestamp vt = vi < videoDtos.size() ? videoDtos.get(vi).dto.getSubmittedAt() : null;

            int winner = pickNewest(ct, pt, vt,
                    ci < channelDtos.size() ? channelDtos.get(ci).dto.getId() : null,
                    pi < playlistDtos.size() ? playlistDtos.get(pi).dto.getId() : null,
                    vi < videoDtos.size() ? videoDtos.get(vi).dto.getId() : null);

            if (winner == 0) {
                merged.add(channelDtos.get(ci).dto);
                channelUsed = channelDtos.get(ci).index + 1;
                ci++;
            } else if (winner == 1) {
                merged.add(playlistDtos.get(pi).dto);
                playlistUsed = playlistDtos.get(pi).index + 1;
                pi++;
            } else {
                merged.add(videoDtos.get(vi).dto);
                videoUsed = videoDtos.get(vi).index + 1;
                vi++;
            }
        }

        // Determine if there's a next page
        boolean hasMoreChannels = channelResult.hasNext() || ci < channelDtos.size();
        boolean hasMorePlaylists = playlistResult.hasNext() || pi < playlistDtos.size();
        boolean hasMoreVideos = videoResult.hasNext() || vi < videoDtos.size();
        boolean hasNext = hasMoreChannels || hasMorePlaylists || hasMoreVideos;

        // Generate composite cursor encoding positions in all three collections
        String nextCursor = null;
        if (hasNext && !merged.isEmpty()) {
            String nextChannelCursor = buildSubCursor(channelUsed, channelCursor, channelResult.getItems(),
                    c -> c.getId(), c -> c.getCreatedAt());
            String nextPlaylistCursor = buildSubCursor(playlistUsed, playlistCursor, playlistResult.getItems(),
                    p -> p.getId(), p -> p.getCreatedAt());
            String nextVideoCursor = buildSubCursor(videoUsed, videoCursor, videoResult.getItems(),
                    v -> v.getId(), v -> v.getCreatedAt());

            CursorUtils.CursorData compositeCursor = new CursorUtils.CursorData("mixed");
            compositeCursor.withField("type", "MIXED");
            if (nextChannelCursor != null) compositeCursor.withField("channelCursor", nextChannelCursor);
            if (nextPlaylistCursor != null) compositeCursor.withField("playlistCursor", nextPlaylistCursor);
            if (nextVideoCursor != null) compositeCursor.withField("videoCursor", nextVideoCursor);
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
     * Get submissions by a specific user, filtered by status.
     * Used by moderators to view their own submissions.
     */
    public CursorPageDto<PendingApprovalDto> getMySubmissions(
            String submittedBy,
            String status,
            String type,
            Integer limit,
            String cursor) throws ExecutionException, InterruptedException, TimeoutException {

        int pageSize = Math.min((limit != null && limit > 0) ? limit : 20, 100);
        boolean allStatuses = (status == null || status.isEmpty() || "ALL".equalsIgnoreCase(status));
        String normalizedStatus = allStatuses ? null : status.toUpperCase();
        if (!allStatuses && !VALID_STATUSES.contains(normalizedStatus)) {
            String safe = (status.length() > 20) ? status.substring(0, 20) + "..." : status;
            throw new IllegalArgumentException("Invalid status: " + safe + ". Must be one of: PENDING, APPROVED, REJECTED, REQUEST_CHANGES, ALL");
        }

        // Validate type parameter if provided
        if (type != null && !type.isEmpty()) {
            String normalizedType = type.toUpperCase();
            if (!VALID_TYPES.contains(normalizedType)) {
                String safeType = type.length() > 20 ? type.substring(0, 20) + "..." : type;
                throw new IllegalArgumentException("Invalid type: " + safeType + ". Must be one of: CHANNEL, PLAYLIST, VIDEO");
            }
        }

        if (allStatuses) {
            return getMySubmissionsAllStatuses(submittedBy, type, pageSize);
        }

        if ("CHANNEL".equalsIgnoreCase(type)) {
            return getSubmissionChannelsOnly(submittedBy, normalizedStatus, pageSize, cursor);
        } else if ("PLAYLIST".equalsIgnoreCase(type)) {
            return getSubmissionPlaylistsOnly(submittedBy, normalizedStatus, pageSize, cursor);
        } else if ("VIDEO".equalsIgnoreCase(type)) {
            return getSubmissionVideosOnly(submittedBy, normalizedStatus, pageSize, cursor);
        }

        // Mixed: merge channels, playlists, and videos
        return getSubmissionsMixed(submittedBy, normalizedStatus, pageSize, cursor);
    }

    // When no explicit status is requested (the Android "My Submissions" default),
    // fan out across PENDING / APPROVED / REJECTED / REQUEST_CHANGES so users see
    // every item they ever submitted, not just pending ones. Returns up to pageSize
    // entries sorted by submittedAt desc; no cursor — the My Submissions list is
    // single-page in the client.
    private CursorPageDto<PendingApprovalDto> getMySubmissionsAllStatuses(
            String submittedBy, String type, int pageSize)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<PendingApprovalDto> merged = new ArrayList<>();
        for (String s : List.of("PENDING", "APPROVED", "REJECTED", "REQUEST_CHANGES")) {
            CursorPageDto<PendingApprovalDto> page;
            if ("CHANNEL".equalsIgnoreCase(type)) {
                page = getSubmissionChannelsOnly(submittedBy, s, pageSize, null);
            } else if ("PLAYLIST".equalsIgnoreCase(type)) {
                page = getSubmissionPlaylistsOnly(submittedBy, s, pageSize, null);
            } else if ("VIDEO".equalsIgnoreCase(type)) {
                page = getSubmissionVideosOnly(submittedBy, s, pageSize, null);
            } else {
                page = getSubmissionsMixed(submittedBy, s, pageSize, null);
            }
            merged.addAll(page.getData());
        }
        merged.sort((a, b) -> {
            Timestamp aT = a.getSubmittedAt();
            Timestamp bT = b.getSubmittedAt();
            if (aT == null && bT == null) return 0;
            if (aT == null) return 1;
            if (bT == null) return -1;
            return bT.compareTo(aT);
        });
        if (merged.size() > pageSize) {
            merged = new ArrayList<>(merged.subList(0, pageSize));
        }
        CursorPageDto<PendingApprovalDto> response = new CursorPageDto<>();
        response.setData(merged);
        response.setPageInfo(new CursorPageDto.PageInfo(null));
        return response;
    }

    private CursorPageDto<PendingApprovalDto> getSubmissionChannelsOnly(
            String submittedBy, String status, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        ApprovalRepository.PaginatedResult<Channel> result =
                approvalRepository.findChannelsBySubmitterAndStatus(submittedBy, status, pageSize, cursor);

        List<PendingApprovalDto> items = new ArrayList<>();
        for (Channel channel : result.getItems()) {
            PendingApprovalDto dto = channelToApprovalDto(channel);
            enrichWithStatusFields(dto, channel.getStatus(), channel.getApprovalMetadata());
            items.add(dto);
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

    private CursorPageDto<PendingApprovalDto> getSubmissionPlaylistsOnly(
            String submittedBy, String status, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        ApprovalRepository.PaginatedResult<Playlist> result =
                approvalRepository.findPlaylistsBySubmitterAndStatus(submittedBy, status, pageSize, cursor);

        List<PendingApprovalDto> items = new ArrayList<>();
        for (Playlist playlist : result.getItems()) {
            PendingApprovalDto dto = playlistToApprovalDto(playlist);
            enrichWithStatusFields(dto, playlist.getStatus(), playlist.getApprovalMetadata());
            items.add(dto);
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

    private CursorPageDto<PendingApprovalDto> getSubmissionVideosOnly(
            String submittedBy, String status, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        ApprovalRepository.PaginatedResult<Video> result =
                approvalRepository.findVideosBySubmitterAndStatus(submittedBy, status, pageSize, cursor);

        List<PendingApprovalDto> items = new ArrayList<>();
        for (Video video : result.getItems()) {
            PendingApprovalDto dto = videoToApprovalDto(video);
            enrichWithStatusFields(dto, video.getStatus(), video.getApprovalMetadata());
            items.add(dto);
        }

        String nextCursor = null;
        if (result.hasNext() && !items.isEmpty()) {
            PendingApprovalDto lastItem = items.get(items.size() - 1);
            CursorUtils.CursorData cursorData = new CursorUtils.CursorData(lastItem.getId());
            cursorData.withField("type", "VIDEO");
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

    private CursorPageDto<PendingApprovalDto> getSubmissionsMixed(
            String submittedBy, String status, int pageSize, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        String channelCursor = null;
        String playlistCursor = null;
        String videoCursor = null;

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData;
            try {
                cursorData = CursorUtils.decode(cursor);
            } catch (Exception e) {
                log.warn("Invalid cursor format: {}", cursor, e);
                throw new IllegalArgumentException("Invalid cursor format");
            }
            if (cursorData == null) {
                // CursorUtils.decode degrades malformed input to null; the
                // submissions API needs a real composite cursor, so reject.
                log.warn("Invalid cursor format (could not decode): {}", cursor);
                throw new IllegalArgumentException("Invalid cursor format");
            }
            channelCursor = cursorData.getFieldAsString("channelCursor");
            playlistCursor = cursorData.getFieldAsString("playlistCursor");
            videoCursor = cursorData.getFieldAsString("videoCursor");
        }

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                approvalRepository.findChannelsBySubmitterAndStatus(submittedBy, status, pageSize, channelCursor);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                approvalRepository.findPlaylistsBySubmitterAndStatus(submittedBy, status, pageSize, playlistCursor);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                approvalRepository.findVideosBySubmitterAndStatus(submittedBy, status, pageSize, videoCursor);

        List<IndexedDto> channelDtos = new ArrayList<>();
        for (int i = 0; i < channelResult.getItems().size(); i++) {
            Channel channel = channelResult.getItems().get(i);
            PendingApprovalDto dto = channelToApprovalDto(channel);
            enrichWithStatusFields(dto, channel.getStatus(), channel.getApprovalMetadata());
            channelDtos.add(new IndexedDto(dto, i));
        }

        List<IndexedDto> playlistDtos = new ArrayList<>();
        for (int i = 0; i < playlistResult.getItems().size(); i++) {
            Playlist playlist = playlistResult.getItems().get(i);
            PendingApprovalDto dto = playlistToApprovalDto(playlist);
            enrichWithStatusFields(dto, playlist.getStatus(), playlist.getApprovalMetadata());
            playlistDtos.add(new IndexedDto(dto, i));
        }

        List<IndexedDto> videoDtos = new ArrayList<>();
        for (int i = 0; i < videoResult.getItems().size(); i++) {
            Video video = videoResult.getItems().get(i);
            PendingApprovalDto dto = videoToApprovalDto(video);
            enrichWithStatusFields(dto, video.getStatus(), video.getApprovalMetadata());
            videoDtos.add(new IndexedDto(dto, i));
        }

        // 3-way merge-sort by submittedAt (newest first)
        List<PendingApprovalDto> merged = new ArrayList<>();
        int channelUsed = 0, playlistUsed = 0, videoUsed = 0;
        int ci = 0, pi = 0, vi = 0;

        while (merged.size() < pageSize && (ci < channelDtos.size() || pi < playlistDtos.size() || vi < videoDtos.size())) {
            // Find the newest item among the three heads
            Timestamp ct = ci < channelDtos.size() ? channelDtos.get(ci).dto.getSubmittedAt() : null;
            Timestamp pt = pi < playlistDtos.size() ? playlistDtos.get(pi).dto.getSubmittedAt() : null;
            Timestamp vt = vi < videoDtos.size() ? videoDtos.get(vi).dto.getSubmittedAt() : null;

            int winner = pickNewest(ct, pt, vt,
                    ci < channelDtos.size() ? channelDtos.get(ci).dto.getId() : null,
                    pi < playlistDtos.size() ? playlistDtos.get(pi).dto.getId() : null,
                    vi < videoDtos.size() ? videoDtos.get(vi).dto.getId() : null);

            if (winner == 0) {
                merged.add(channelDtos.get(ci).dto);
                channelUsed = channelDtos.get(ci).index + 1;
                ci++;
            } else if (winner == 1) {
                merged.add(playlistDtos.get(pi).dto);
                playlistUsed = playlistDtos.get(pi).index + 1;
                pi++;
            } else {
                merged.add(videoDtos.get(vi).dto);
                videoUsed = videoDtos.get(vi).index + 1;
                vi++;
            }
        }

        boolean hasMoreChannels = channelResult.hasNext() || ci < channelDtos.size();
        boolean hasMorePlaylists = playlistResult.hasNext() || pi < playlistDtos.size();
        boolean hasMoreVideos = videoResult.hasNext() || vi < videoDtos.size();
        boolean hasNext = hasMoreChannels || hasMorePlaylists || hasMoreVideos;

        String nextCursor = null;
        if (hasNext && !merged.isEmpty()) {
            String nextChannelCursor = buildSubCursor(channelUsed, channelCursor, channelResult.getItems(),
                    c -> c.getId(), c -> c.getCreatedAt());
            String nextPlaylistCursor = buildSubCursor(playlistUsed, playlistCursor, playlistResult.getItems(),
                    p -> p.getId(), p -> p.getCreatedAt());
            String nextVideoCursor = buildSubCursor(videoUsed, videoCursor, videoResult.getItems(),
                    v -> v.getId(), v -> v.getCreatedAt());

            CursorUtils.CursorData compositeCursor = new CursorUtils.CursorData("mixed");
            compositeCursor.withField("type", "MIXED");
            if (nextChannelCursor != null) compositeCursor.withField("channelCursor", nextChannelCursor);
            if (nextPlaylistCursor != null) compositeCursor.withField("playlistCursor", nextPlaylistCursor);
            if (nextVideoCursor != null) compositeCursor.withField("videoCursor", nextVideoCursor);
            nextCursor = CursorUtils.encode(compositeCursor);
        }

        CursorPageDto<PendingApprovalDto> response = new CursorPageDto<>();
        response.setData(merged);
        response.setPageInfo(new CursorPageDto.PageInfo(nextCursor));
        return response;
    }

    /**
     * Pick the newest timestamp among up to 3 candidates. Returns 0, 1, or 2.
     * Null timestamps lose; ties broken by ID comparison.
     *
     * @throws IllegalStateException if all candidate IDs are null (no valid candidates)
     */
    private int pickNewest(Timestamp t0, Timestamp t1, Timestamp t2,
                           String id0, String id1, String id2) {
        int best = -1;
        Timestamp bestT = null;
        String bestId = null;

        Timestamp[] ts = { t0, t1, t2 };
        String[] ids = { id0, id1, id2 };

        for (int i = 0; i < 3; i++) {
            if (ids[i] == null) continue; // no candidate at this index
            if (best == -1) {
                best = i;
                bestT = ts[i];
                bestId = ids[i];
            } else {
                if (isNewer(ts[i], ids[i], bestT, bestId)) {
                    best = i;
                    bestT = ts[i];
                    bestId = ids[i];
                }
            }
        }

        if (best == -1) {
            throw new IllegalStateException("pickNewest called with no valid candidates");
        }
        return best;
    }

    /**
     * Compare two timestamps for the merge-sort: returns true if (a, aId) should come before (b, bId).
     * Uses descending timestamp order (newer first). Ties broken by ascending ID (lexicographic <=).
     * The <= tiebreaker is safe because candidates come from different Firestore collections,
     * so document IDs never collide across the channel/playlist/video triple.
     */
    private boolean isNewer(Timestamp a, String aId, Timestamp b, String bId) {
        if (a == null && b == null) return aId.compareTo(bId) <= 0;
        if (a == null) return false;
        if (b == null) return true;
        int cmp = a.compareTo(b);
        if (cmp == 0) return aId.compareTo(bId) <= 0;
        return cmp > 0; // newer first
    }

    /**
     * Build sub-cursor for a collection in the mixed-type merge.
     */
    private <T> String buildSubCursor(int used, String prevCursor, List<T> items,
                                       java.util.function.Function<T, String> getId,
                                       java.util.function.Function<T, Timestamp> getCreatedAt) {
        if (used > 0 && used <= items.size()) {
            T last = items.get(used - 1);
            CursorUtils.CursorData cd = new CursorUtils.CursorData(getId.apply(last));
            Timestamp createdAt = getCreatedAt.apply(last);
            if (createdAt != null) cd.withField("createdAt", createdAt);
            return CursorUtils.encode(cd);
        }
        return prevCursor;
    }

    /**
     * Enrich a PendingApprovalDto with status and approval metadata fields.
     */
    private void enrichWithStatusFields(PendingApprovalDto dto, String status, ApprovalMetadata metadata) {
        dto.setStatus(status);
        if (metadata != null) {
            dto.setReviewNotes(metadata.getReviewNotes());
            dto.setRejectionReason(metadata.getRejectionReason());
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

        // Try to find as video
        Optional<Video> videoOpt = videoRepository.findById(id);
        if (videoOpt.isPresent()) {
            return approveVideo(videoOpt.get(), request, actorUid, actorDisplayName);
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

        // Try to find as video
        Optional<Video> videoOpt = videoRepository.findById(id);
        if (videoOpt.isPresent()) {
            return rejectVideo(videoOpt.get(), request, actorUid, actorDisplayName);
        }

        throw new IllegalArgumentException("Item not found: " + id);
    }

    /**
     * Request changes on a pending item.
     * Only valid from PENDING status; re-submission via T1 flips REQUEST_CHANGES → PENDING.
     */
    public ApprovalResponseDto requestChanges(String id, String note, String contentType,
                                              String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, TimeoutException {

        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Review note must not be blank for REQUEST_CHANGES");
        }

        // Try to find as channel first
        Optional<Channel> channelOpt = channelRepository.findById(id);
        if (channelOpt.isPresent()) {
            return requestChangesChannel(channelOpt.get(), note, actorUid, actorDisplayName);
        }

        // Try to find as playlist
        Optional<Playlist> playlistOpt = playlistRepository.findById(id);
        if (playlistOpt.isPresent()) {
            return requestChangesPlaylist(playlistOpt.get(), note, actorUid, actorDisplayName);
        }

        // Try to find as video
        Optional<Video> videoOpt = videoRepository.findById(id);
        if (videoOpt.isPresent()) {
            return requestChangesVideo(videoOpt.get(), note, actorUid, actorDisplayName);
        }

        throw new IllegalArgumentException("Item not found: " + id);
    }

    private ApprovalResponseDto requestChangesChannel(Channel channel, String note,
                                                      String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, TimeoutException {

        if (!"PENDING".equals(channel.getStatus())) {
            throw new IllegalStateException(
                    "Cannot request changes on channel " + channel.getId()
                            + ": current status is " + channel.getStatus());
        }

        channel.setStatus("REQUEST_CHANGES");
        channel.touch();

        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, note);
        channel.setApprovalMetadata(metadata);

        channelRepository.saveIfStatus(channel, "PENDING");

        Map<String, Object> details = new HashMap<>();
        details.put("notes", note);
        // Cubic R5 P1: REQUEST_CHANGES is not a rejection — emit the dedicated
        // `*_changes_requested` action so downstream dashboards don't conflate
        // the two.
        auditLogService.logChangesRequested("channel", channel.getId(), actorUid, actorDisplayName, details);

        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("REQUEST_CHANGES");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(note);

        return response;
    }

    private ApprovalResponseDto requestChangesPlaylist(Playlist playlist, String note,
                                                       String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, TimeoutException {

        if (!"PENDING".equals(playlist.getStatus())) {
            throw new IllegalStateException(
                    "Cannot request changes on playlist " + playlist.getId()
                            + ": current status is " + playlist.getStatus());
        }

        playlist.setStatus("REQUEST_CHANGES");
        playlist.touch();

        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, note);
        playlist.setApprovalMetadata(metadata);

        playlistRepository.saveIfStatus(playlist, "PENDING");

        Map<String, Object> details = new HashMap<>();
        details.put("notes", note);
        // Cubic R5 P1: dedicated changes-requested action — see channel path.
        auditLogService.logChangesRequested("playlist", playlist.getId(), actorUid, actorDisplayName, details);

        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("REQUEST_CHANGES");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(note);

        return response;
    }

    private ApprovalResponseDto requestChangesVideo(Video video, String note,
                                                    String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, TimeoutException {

        if (!"PENDING".equals(video.getStatus())) {
            throw new IllegalStateException(
                    "Cannot request changes on video " + video.getId()
                            + ": current status is " + video.getStatus());
        }

        video.setStatus("REQUEST_CHANGES");
        video.touch();

        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, note);
        video.setApprovalMetadata(metadata);

        videoRepository.saveIfStatus(video, "PENDING");

        Map<String, Object> details = new HashMap<>();
        details.put("notes", note);
        // Cubic R5 P1: dedicated changes-requested action — see channel path.
        auditLogService.logChangesRequested("video", video.getId(), actorUid, actorDisplayName, details);

        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("REQUEST_CHANGES");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(note);

        return response;
    }

    // Private helper methods

    private PendingApprovalDto channelToApprovalDto(Channel channel) {
        // Cubic R4 P1 (TOCTOU + perf): enrichIfMissing was called here on every list
        // read. It did a blocking NewPipe scrape + unconditional Firestore save with
        // no transactional status check — admin approvals racing with enrichment would
        // be silently reverted, AND a 100-row admin queue with stale data could time
        // out. Submission-time prefetch (channel.setName(prefetchedName) in submit*)
        // already populates these fields for new rows; legacy rows that pre-date the
        // prefetch can be backfilled by a one-shot migration or a future explicit
        // admin "refresh metadata" action. Read path no longer triggers backfill.
        PendingApprovalDto dto = new PendingApprovalDto();
        dto.setId(channel.getId());
        dto.setType("CHANNEL");
        dto.setEntityId(channel.getId());
        dto.setTitle(channel.getName());
        dto.setThumbnailUrl(channel.getThumbnailUrl());
        dto.setYoutubeId(channel.getYoutubeId());
        dto.setSubmittedAt(channel.getCreatedAt());
        dto.setSubmittedBy(channel.getSubmittedBy());
        dto.setSubmitterNote(channel.getSubmitterNote());
        dto.setSource(channel.getSource());

        // Enrich with submitter details
        if (dto.getSubmittedBy() != null && !dto.getSubmittedBy().isEmpty()) {
            try {
                userRepository.findByUid(dto.getSubmittedBy()).ifPresent(u -> {
                    dto.setSubmittedByDisplayName(u.getDisplayName());
                    dto.setSubmittedByEmail(u.getEmail());
                });
            } catch (Exception e) {
                log.debug("Failed to enrich submittedBy uid={}", dto.getSubmittedBy(), e);
            }
        }

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
        // youtubeId is the top-level DTO field (set above via dto.setYoutubeId);
        // don't duplicate to metadata — same drift risk as thumbnailUrl.
        // thumbnailUrl is set as a top-level DTO field above (dto.setThumbnailUrl).
        // Don't duplicate it into the metadata map — frontend and Android both read
        // the top-level field and a dual-source would drift on partial updates.
        if (channel.getDescription() != null) {
            dto.addMetadata("description", channel.getDescription());
        }
        if (channel.getSubscribers() != null) {
            dto.addMetadata("subscriberCount", formatNumber(channel.getSubscribers()));
        }
        if (channel.getVideoCount() != null) {
            dto.addMetadata("videoCount", channel.getVideoCount());
        }

        return dto;
    }

    private PendingApprovalDto playlistToApprovalDto(Playlist playlist) {
        // Cubic R4 P1: read path no longer triggers backfill. See channelToApprovalDto.
        PendingApprovalDto dto = new PendingApprovalDto();
        dto.setId(playlist.getId());
        dto.setType("PLAYLIST");
        dto.setEntityId(playlist.getId());
        dto.setTitle(playlist.getTitle());
        dto.setThumbnailUrl(playlist.getThumbnailUrl());
        dto.setYoutubeId(playlist.getYoutubeId());
        dto.setSubmittedAt(playlist.getCreatedAt());
        dto.setSubmittedBy(playlist.getSubmittedBy());
        dto.setSubmitterNote(playlist.getSubmitterNote());
        dto.setSource(playlist.getSource());

        // Enrich with submitter details
        if (dto.getSubmittedBy() != null && !dto.getSubmittedBy().isEmpty()) {
            try {
                userRepository.findByUid(dto.getSubmittedBy()).ifPresent(u -> {
                    dto.setSubmittedByDisplayName(u.getDisplayName());
                    dto.setSubmittedByEmail(u.getEmail());
                });
            } catch (Exception e) {
                log.debug("Failed to enrich submittedBy uid={}", dto.getSubmittedBy(), e);
            }
        }

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
        // youtubeId is the top-level DTO field; don't duplicate to metadata.
        // thumbnailUrl is set as a top-level DTO field above; don't duplicate to metadata.
        if (playlist.getDescription() != null) {
            dto.addMetadata("description", playlist.getDescription());
        }
        if (playlist.getItemCount() != null) {
            dto.addMetadata("itemCount", playlist.getItemCount());
        }

        return dto;
    }

    private PendingApprovalDto videoToApprovalDto(Video video) {
        // Cubic R4 P1: read path no longer triggers backfill. See channelToApprovalDto.
        PendingApprovalDto dto = new PendingApprovalDto();
        dto.setId(video.getId());
        dto.setType("VIDEO");
        dto.setEntityId(video.getId());
        dto.setTitle(video.getTitle());
        dto.setThumbnailUrl(video.getThumbnailUrl());
        dto.setYoutubeId(video.getYoutubeId());
        dto.setSubmittedAt(video.getCreatedAt());
        dto.setSubmittedBy(video.getSubmittedBy());
        dto.setSubmitterNote(video.getSubmitterNote());
        dto.setSource(video.getSource());

        // Enrich with submitter details
        if (dto.getSubmittedBy() != null && !dto.getSubmittedBy().isEmpty()) {
            try {
                userRepository.findByUid(dto.getSubmittedBy()).ifPresent(u -> {
                    dto.setSubmittedByDisplayName(u.getDisplayName());
                    dto.setSubmittedByEmail(u.getEmail());
                });
            } catch (Exception e) {
                log.debug("Failed to enrich submittedBy uid={}", dto.getSubmittedBy(), e);
            }
        }

        // Get first category name
        if (video.getCategoryIds() != null && !video.getCategoryIds().isEmpty()) {
            try {
                Optional<Category> catOpt = categoryRepository.findById(video.getCategoryIds().get(0));
                if (catOpt.isPresent()) {
                    dto.setCategory(catOpt.get().getName());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch category for video {}", video.getId(), e);
            }
        }

        // Add metadata
        // youtubeId is the top-level DTO field; don't duplicate to metadata.
        // thumbnailUrl is set as a top-level DTO field above; don't duplicate to metadata.
        if (video.getDescription() != null) {
            dto.addMetadata("description", video.getDescription());
        }
        if (video.getDurationSeconds() != null) {
            dto.addMetadata("durationSeconds", video.getDurationSeconds());
        }
        if (video.getViewCount() != null) {
            dto.addMetadata("viewCount", video.getViewCount());
        }
        if (video.getChannelTitle() != null) {
            dto.addMetadata("channelTitle", video.getChannelTitle());
        }

        return dto;
    }

    private ApprovalResponseDto approveChannel(Channel channel, ApprovalRequestDto request,
                                               String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        if (!"PENDING".equals(channel.getStatus())) {
            throw new IllegalStateException(
                    "Cannot approve channel " + channel.getId() + ": current status is " + channel.getStatus());
        }

        // Require at least one category — either from the item or from the override.
        requireCategoryOrOverride(channel.getCategoryIds(), request);

        // Update status
        channel.setStatus("APPROVED");
        channel.setApprovedBy(actorUid);
        channel.touch();

        // Apply category override if provided
        if (request.getCategoryOverride() != null && !request.getCategoryOverride().isBlank()) {
            channel.setCategoryIds(List.of(request.getCategoryOverride().strip()));
        }

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        channel.setApprovalMetadata(metadata);

        // Save to Firestore (transactional — atomically verifies PENDING status)
        channelRepository.saveIfStatus(channel, "PENDING");

        // Add to category sort order
        if (channel.getCategoryIds() != null) {
            for (String categoryId : channel.getCategoryIds()) {
                try {
                    sortOrderService.addContentToCategory(categoryId, channel.getId(), "channel");
                } catch (Exception e) {
                    log.warn("Failed to add channel {} to sort order for category {}: {}",
                            channel.getId(), categoryId, e.getMessage());
                }
            }
        }

        // Create audit log
        auditLogService.logApproval("channel", channel.getId(), actorUid, actorDisplayName, request.getReviewNotes());

        // Fan-out: flip AWAITING per-user Me-list rows to APPROVED (swallows its own errors)
        try {
            importGraduationService.onApproved(YouTubeContentType.CHANNEL, channel.getYoutubeId());
        } catch (Exception e) {
            log.warn("Graduation fan-out failed on channel approve youtubeId={}: {}", channel.getYoutubeId(), e.getMessage());
        }

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

        if (!"PENDING".equals(playlist.getStatus())) {
            throw new IllegalStateException(
                    "Cannot approve playlist " + playlist.getId() + ": current status is " + playlist.getStatus());
        }

        // Require at least one category — either from the item or from the override.
        requireCategoryOrOverride(playlist.getCategoryIds(), request);

        // Update status
        playlist.setStatus("APPROVED");
        playlist.setApprovedBy(actorUid);
        playlist.touch();

        // Apply category override if provided
        if (request.getCategoryOverride() != null && !request.getCategoryOverride().isBlank()) {
            playlist.setCategoryIds(List.of(request.getCategoryOverride().strip()));
        }

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        playlist.setApprovalMetadata(metadata);

        // Save to Firestore (transactional — atomically verifies PENDING status)
        playlistRepository.saveIfStatus(playlist, "PENDING");

        // Add to category sort order
        if (playlist.getCategoryIds() != null) {
            for (String categoryId : playlist.getCategoryIds()) {
                try {
                    sortOrderService.addContentToCategory(categoryId, playlist.getId(), "playlist");
                } catch (Exception e) {
                    log.warn("Failed to add playlist {} to sort order for category {}: {}",
                            playlist.getId(), categoryId, e.getMessage());
                }
            }
        }

        // Create audit log
        auditLogService.logApproval("playlist", playlist.getId(), actorUid, actorDisplayName, request.getReviewNotes());

        // Fan-out: flip AWAITING per-user Me-list rows to APPROVED (swallows its own errors)
        try {
            importGraduationService.onApproved(YouTubeContentType.PLAYLIST, playlist.getYoutubeId());
        } catch (Exception e) {
            log.warn("Graduation fan-out failed on playlist approve youtubeId={}: {}", playlist.getYoutubeId(), e.getMessage());
        }

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

        if (!"PENDING".equals(channel.getStatus())) {
            throw new IllegalStateException(
                    "Cannot reject channel " + channel.getId() + ": current status is " + channel.getStatus());
        }

        // Update status
        channel.setStatus("REJECTED");
        channel.touch();

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        metadata.setRejectionReason(request.getReason());
        channel.setApprovalMetadata(metadata);

        // Save to Firestore (transactional — atomically verifies PENDING status)
        channelRepository.saveIfStatus(channel, "PENDING");

        // Remove from category sort order
        sortOrderService.removeContentFromAllCategories(channel.getId(), "channel");

        // Create audit log
        Map<String, Object> details = new HashMap<>();
        details.put("reason", request.getReason());
        details.put("notes", request.getReviewNotes());
        auditLogService.logRejection("channel", channel.getId(), actorUid, actorDisplayName, details);

        // Remove search index entries for this channel — fire and forget
        if (channel.getYoutubeId() != null) {
            CompletableFuture.runAsync(() ->
                streamIndexService.removeSource("CHANNEL", channel.getYoutubeId()));
        }

        // Fan-out: tombstone AWAITING per-user Me-list rows (swallows its own errors)
        try {
            importGraduationService.onRejected(YouTubeContentType.CHANNEL, channel.getYoutubeId());
        } catch (Exception e) {
            log.warn("Graduation fan-out failed on channel reject youtubeId={}: {}", channel.getYoutubeId(), e.getMessage());
        }

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

        if (!"PENDING".equals(playlist.getStatus())) {
            throw new IllegalStateException(
                    "Cannot reject playlist " + playlist.getId() + ": current status is " + playlist.getStatus());
        }

        // Update status
        playlist.setStatus("REJECTED");
        playlist.touch();

        // Set approval metadata
        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        metadata.setRejectionReason(request.getReason());
        playlist.setApprovalMetadata(metadata);

        // Save to Firestore (transactional — atomically verifies PENDING status)
        playlistRepository.saveIfStatus(playlist, "PENDING");

        // Remove from category sort order
        sortOrderService.removeContentFromAllCategories(playlist.getId(), "playlist");

        // Create audit log
        Map<String, Object> details = new HashMap<>();
        details.put("reason", request.getReason());
        details.put("notes", request.getReviewNotes());
        auditLogService.logRejection("playlist", playlist.getId(), actorUid, actorDisplayName, details);

        // Remove search index entries for this playlist — fire and forget
        if (playlist.getYoutubeId() != null) {
            CompletableFuture.runAsync(() ->
                streamIndexService.removeSource("PLAYLIST", playlist.getYoutubeId()));
        }

        // Fan-out: tombstone AWAITING per-user Me-list rows (swallows its own errors)
        try {
            importGraduationService.onRejected(YouTubeContentType.PLAYLIST, playlist.getYoutubeId());
        } catch (Exception e) {
            log.warn("Graduation fan-out failed on playlist reject youtubeId={}: {}", playlist.getYoutubeId(), e.getMessage());
        }

        // Return response
        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("REJECTED");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(request.getReviewNotes());

        return response;
    }

    private ApprovalResponseDto approveVideo(Video video, ApprovalRequestDto request,
                                              String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        if (!"PENDING".equals(video.getStatus())) {
            throw new IllegalStateException(
                    "Cannot approve video " + video.getId() + ": current status is " + video.getStatus());
        }

        // Require at least one category — either from the item or from the override.
        requireCategoryOrOverride(video.getCategoryIds(), request);

        video.setStatus("APPROVED");
        video.setApprovedBy(actorUid);
        video.touch();

        if (request.getCategoryOverride() != null && !request.getCategoryOverride().isBlank()) {
            video.setCategoryIds(List.of(request.getCategoryOverride().strip()));
        }

        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        video.setApprovalMetadata(metadata);

        // Save to Firestore (transactional — atomically verifies PENDING status)
        videoRepository.saveIfStatus(video, "PENDING");

        if (video.getCategoryIds() != null) {
            for (String categoryId : video.getCategoryIds()) {
                try {
                    sortOrderService.addContentToCategory(categoryId, video.getId(), "video");
                } catch (Exception e) {
                    log.warn("Failed to add video {} to sort order for category {}: {}",
                            video.getId(), categoryId, e.getMessage());
                }
            }
        }

        auditLogService.logApproval("video", video.getId(), actorUid, actorDisplayName, request.getReviewNotes());

        // Fan-out: flip AWAITING per-user Me-list rows to APPROVED (swallows its own errors)
        try {
            importGraduationService.onApproved(YouTubeContentType.VIDEO, video.getYoutubeId());
        } catch (Exception e) {
            log.warn("Graduation fan-out failed on video approve youtubeId={}: {}", video.getYoutubeId(), e.getMessage());
        }

        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("APPROVED");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(request.getReviewNotes());

        return response;
    }

    private ApprovalResponseDto rejectVideo(Video video, RejectionRequestDto request,
                                             String actorUid, String actorDisplayName)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        if (!"PENDING".equals(video.getStatus())) {
            throw new IllegalStateException(
                    "Cannot reject video " + video.getId() + ": current status is " + video.getStatus());
        }

        video.setStatus("REJECTED");
        video.touch();

        ApprovalMetadata metadata = new ApprovalMetadata(actorUid, actorDisplayName, request.getReviewNotes());
        metadata.setRejectionReason(request.getReason());
        video.setApprovalMetadata(metadata);

        // Save to Firestore (transactional — atomically verifies PENDING status)
        videoRepository.saveIfStatus(video, "PENDING");

        sortOrderService.removeContentFromAllCategories(video.getId(), "video");

        Map<String, Object> details = new HashMap<>();
        details.put("reason", request.getReason());
        details.put("notes", request.getReviewNotes());
        auditLogService.logRejection("video", video.getId(), actorUid, actorDisplayName, details);

        // Fan-out: tombstone AWAITING per-user Me-list rows (swallows its own errors)
        try {
            importGraduationService.onRejected(YouTubeContentType.VIDEO, video.getYoutubeId());
        } catch (Exception e) {
            log.warn("Graduation fan-out failed on video reject youtubeId={}: {}", video.getYoutubeId(), e.getMessage());
        }

        ApprovalResponseDto response = new ApprovalResponseDto();
        response.setStatus("REJECTED");
        response.setReviewedAt(metadata.getReviewedAt());
        response.setReviewedBy(actorUid);
        response.setReviewNotes(request.getReviewNotes());

        return response;
    }

    /**
     * Guard: throw 400 if neither existing categoryIds nor a categoryOverride are present.
     * Called at the start of each approveChannel/approvePlaylist/approveVideo.
     */
    private void requireCategoryOrOverride(List<String> existingCategoryIds, ApprovalRequestDto request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        String override = request.getCategoryOverride();
        boolean overridePresent = override != null && !override.isBlank();
        boolean hasCategories = existingCategoryIds != null && !existingCategoryIds.isEmpty();
        if (!overridePresent && !hasCategories) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Category required to approve this submission");
        }
        // F11: a provided override must reference a real category — otherwise a typo'd or
        // stale id would silently become the content's only category.
        if (overridePresent && categoryRepository.findById(override.strip()).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Category override does not reference an existing category");
        }
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

