package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.dto.sync.*;
import com.albunyaan.tube.repository.SyncRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.UnaryOperator;

@Service
public class SyncService {

    private final SyncRepository repo;
    private final ArchiveProjector projector;

    public SyncService(SyncRepository repo, ArchiveProjector projector) {
        this.repo = repo;
        this.projector = projector;
    }

    public SyncResponseDto pull(String uid, SyncCursors cursors)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Cubic R5 P1: batch archive-lookup per page.
        //
        // The previous shape (`project::projectX` per-row mapping) hit Firestore
        // once per row inside ArchiveProjector.projectIf → up to 1,500 sequential
        // reads on a full page across all three types. The batch helpers below
        // do one chunked `whereIn` round-trip per type (chunks of 30) which is
        // a 50× reduction worst-case.
        SyncPageDto<SubscriptionSyncDto> subs = pullPage(
                uid, SyncRepository.SUBS_COLL,
                cursors.getSubscriptions(), cursors.getSubscriptionsId(),
                projector::projectSubscriptions, SyncService::toSubscriptionDto);
        SyncPageDto<PlaylistSyncDto> pls = pullPage(
                uid, SyncRepository.PLAYLISTS_COLL,
                cursors.getPlaylists(), cursors.getPlaylistsId(),
                projector::projectPlaylists, SyncService::toPlaylistDto);
        SyncPageDto<FavoriteSyncDto> favs = pullPage(
                uid, SyncRepository.FAVORITES_COLL,
                cursors.getFavorites(), cursors.getFavoritesId(),
                projector::projectFavorites, SyncService::toFavoriteDto);
        return new SyncResponseDto(subs, pls, favs);
    }

    private <T extends SyncRowDto> SyncPageDto<T> pullPage(
            String uid, String coll, long since, String lastDocId,
            UnaryOperator<List<RawRow>> projectBatch,
            Function<RawRow, T> toDto)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<RawRow> raw = repo.pull(uid, coll, since, lastDocId, SyncRepository.SYNC_PAGE_SIZE);
        List<RawRow> projected = projectBatch.apply(raw);
        List<T> items = new ArrayList<>(projected.size());
        for (RawRow r : projected) items.add(toDto.apply(r));
        Long nextCursor = null;
        String nextCursorId = null;
        if (raw.size() == SyncRepository.SYNC_PAGE_SIZE) {
            // Cursor advancement uses the underlying raw row's (updatedAt, id),
            // not the projected (virtual-tombstone) row, so that virtual-tombstone
            // stamping never pulls the cursor backwards.
            RawRow last = raw.get(raw.size() - 1);
            nextCursor = last.updatedAt();
            nextCursorId = last.id();
        }
        return new SyncPageDto<>(items, nextCursor, nextCursorId);
    }

    // ── Row → DTO converters ─────────────────────────────────────────────

    private static SubscriptionSyncDto toSubscriptionDto(RawRow r) {
        SubscriptionSyncDto d = new SubscriptionSyncDto();
        Map<String, Object> m = r.data();
        d.setEntityId(r.id());
        d.setDeleted(Boolean.TRUE.equals(m.get("deleted")));
        d.setUpdatedAt(r.updatedAt());
        d.setChannelUrl((String) m.getOrDefault("channelUrl", ""));
        d.setName((String) m.getOrDefault("name", ""));
        d.setAvatarUrl((String) m.get("avatarUrl"));
        d.setSubscribedAt(longOf(m.get("subscribedAt")));
        return d;
    }

    private static PlaylistSyncDto toPlaylistDto(RawRow r) {
        PlaylistSyncDto d = new PlaylistSyncDto();
        Map<String, Object> m = r.data();
        d.setEntityId(r.id());
        d.setDeleted(Boolean.TRUE.equals(m.get("deleted")));
        d.setUpdatedAt(r.updatedAt());
        d.setPlaylistUrl((String) m.getOrDefault("playlistUrl", ""));
        d.setName((String) m.getOrDefault("name", ""));
        d.setThumbnailUrl((String) m.get("thumbnailUrl"));
        d.setUploaderName((String) m.get("uploaderName"));
        d.setSavedAt(longOf(m.get("savedAt")));
        return d;
    }

    private static FavoriteSyncDto toFavoriteDto(RawRow r) {
        FavoriteSyncDto d = new FavoriteSyncDto();
        Map<String, Object> m = r.data();
        d.setEntityId(r.id());
        d.setDeleted(Boolean.TRUE.equals(m.get("deleted")));
        d.setUpdatedAt(r.updatedAt());
        d.setTitle((String) m.getOrDefault("title", ""));
        d.setChannelName((String) m.getOrDefault("channelName", ""));
        d.setThumbnailUrl((String) m.get("thumbnailUrl"));
        d.setDurationSeconds(intOf(m.get("durationSeconds")));
        d.setAddedAt(longOf(m.get("addedAt")));
        return d;
    }

    private static long longOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }
    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    // ── Write path ───────────────────────────────────────────────────────────

    public SubscriptionSyncDto upsertSubscription(String uid, String id, PutSubscriptionRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("channelUrl", req.getChannelUrl());
        body.put("name", req.getName());
        body.put("avatarUrl", req.getAvatarUrl());
        body.put("subscribedAt", req.getSubscribedAt());
        return toSubscriptionDto(repo.upsert(uid, SyncRepository.SUBS_COLL, id, body));
    }

    public SubscriptionSyncDto tombstoneSubscription(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toSubscriptionDto(repo.tombstone(uid, SyncRepository.SUBS_COLL, id));
    }

    public PlaylistSyncDto upsertPlaylist(String uid, String id, PutPlaylistRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("playlistUrl", req.getPlaylistUrl());
        body.put("name", req.getName());
        body.put("thumbnailUrl", req.getThumbnailUrl());
        body.put("uploaderName", req.getUploaderName());
        body.put("savedAt", req.getSavedAt());
        return toPlaylistDto(repo.upsert(uid, SyncRepository.PLAYLISTS_COLL, id, body));
    }

    public PlaylistSyncDto tombstonePlaylist(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toPlaylistDto(repo.tombstone(uid, SyncRepository.PLAYLISTS_COLL, id));
    }

    public FavoriteSyncDto upsertFavorite(String uid, String id, PutFavoriteRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", req.getTitle());
        body.put("channelName", req.getChannelName());
        body.put("thumbnailUrl", req.getThumbnailUrl());
        body.put("durationSeconds", req.getDurationSeconds());
        body.put("addedAt", req.getAddedAt());
        return toFavoriteDto(repo.upsert(uid, SyncRepository.FAVORITES_COLL, id, body));
    }

    public FavoriteSyncDto tombstoneFavorite(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toFavoriteDto(repo.tombstone(uid, SyncRepository.FAVORITES_COLL, id));
    }
}
