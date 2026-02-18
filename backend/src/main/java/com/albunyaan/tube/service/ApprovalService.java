package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
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

    private static final java.util.Set<String> VALID_TYPES = java.util.Set.of("CHANNEL", "PLAYLIST", "VIDEO");

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;
    private final ApprovalRepository approvalRepository;
    private final AuditLogService auditLogService;
    private final SortOrderService sortOrderService;

    public ApprovalService(ChannelRepository channelRepository,
                          PlaylistRepository playlistRepository,
                          VideoRepository videoRepository,
                          CategoryRepository categoryRepository,
                          ApprovalRepository approvalRepository,
                          AuditLogService auditLogService,
                          SortOrderService sortOrderService) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
        this.approvalRepository = approvalRepository;
        this.auditLogService = auditLogService;
        this.sortOrderService = sortOrderService;
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
                throw new IllegalArgumentException("Invalid type: " + type + ". Must be one of: CHANNEL, PLAYLIST, VIDEO");
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
            try {
                CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
                if (cursorData != null) {
                    channelCursor = cursorData.getFieldAsString("channelCursor");
                    playlistCursor = cursorData.getFieldAsString("playlistCursor");
                    videoCursor = cursorData.getFieldAsString("videoCursor");
                }
            } catch (Exception e) {
                log.warn("Invalid cursor format: {}", cursor, e);
                throw new IllegalArgumentException("Invalid cursor format");
            }
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
        String normalizedStatus = (status != null) ? status.toUpperCase() : "PENDING";

        // Validate type parameter if provided
        if (type != null && !type.isEmpty()) {
            String normalizedType = type.toUpperCase();
            if (!VALID_TYPES.contains(normalizedType)) {
                throw new IllegalArgumentException("Invalid type: " + type + ". Must be one of: CHANNEL, PLAYLIST, VIDEO");
            }
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
            try {
                CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
                if (cursorData != null) {
                    channelCursor = cursorData.getFieldAsString("channelCursor");
                    playlistCursor = cursorData.getFieldAsString("playlistCursor");
                    videoCursor = cursorData.getFieldAsString("videoCursor");
                }
            } catch (Exception e) {
                log.warn("Invalid cursor format: {}", cursor, e);
                throw new IllegalArgumentException("Invalid cursor format");
            }
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
        if (channel.getYoutubeId() != null) {
            dto.addMetadata("youtubeId", channel.getYoutubeId());
        }
        if (channel.getThumbnailUrl() != null) {
            dto.addMetadata("thumbnailUrl", channel.getThumbnailUrl());
        }
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
        if (playlist.getYoutubeId() != null) {
            dto.addMetadata("youtubeId", playlist.getYoutubeId());
        }
        if (playlist.getThumbnailUrl() != null) {
            dto.addMetadata("thumbnailUrl", playlist.getThumbnailUrl());
        }
        if (playlist.getDescription() != null) {
            dto.addMetadata("description", playlist.getDescription());
        }
        if (playlist.getItemCount() != null) {
            dto.addMetadata("itemCount", playlist.getItemCount());
        }

        return dto;
    }

    private PendingApprovalDto videoToApprovalDto(Video video) {
        PendingApprovalDto dto = new PendingApprovalDto();
        dto.setId(video.getId());
        dto.setType("VIDEO");
        dto.setEntityId(video.getId());
        dto.setTitle(video.getTitle());
        dto.setSubmittedAt(video.getCreatedAt());
        dto.setSubmittedBy(video.getSubmittedBy());

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
        if (video.getYoutubeId() != null) {
            dto.addMetadata("youtubeId", video.getYoutubeId());
        }
        if (video.getThumbnailUrl() != null) {
            dto.addMetadata("thumbnailUrl", video.getThumbnailUrl());
        }
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

        video.setStatus("APPROVED");
        video.setApprovedBy(actorUid);
        video.touch();

        if (request.getCategoryOverride() != null) {
            video.setCategoryIds(List.of(request.getCategoryOverride()));
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

