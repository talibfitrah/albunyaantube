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
        SyncPageDto<SubscriptionSyncDto> subs = pullPage(
                uid, SyncRepository.SUBS_COLL, cursors.getSubscriptions(),
                projector::projectSubscription, SyncService::toSubscriptionDto);
        SyncPageDto<PlaylistSyncDto> pls = pullPage(
                uid, SyncRepository.PLAYLISTS_COLL, cursors.getPlaylists(),
                projector::projectPlaylist, SyncService::toPlaylistDto);
        SyncPageDto<FavoriteSyncDto> favs = pullPage(
                uid, SyncRepository.FAVORITES_COLL, cursors.getFavorites(),
                projector::projectFavorite, SyncService::toFavoriteDto);
        return new SyncResponseDto(subs, pls, favs);
    }

    private <T extends SyncRowDto> SyncPageDto<T> pullPage(
            String uid, String coll, long since,
            Function<RawRow, RawRow> project,
            Function<RawRow, T> toDto)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<RawRow> raw = repo.pull(uid, coll, since, SyncRepository.SYNC_PAGE_SIZE);
        List<T> items = new ArrayList<>(raw.size());
        for (RawRow r : raw) items.add(toDto.apply(project.apply(r)));
        Long nextCursor = raw.size() == SyncRepository.SYNC_PAGE_SIZE
                ? raw.get(raw.size() - 1).updatedAt() : null;
        return new SyncPageDto<>(items, nextCursor);
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
}
