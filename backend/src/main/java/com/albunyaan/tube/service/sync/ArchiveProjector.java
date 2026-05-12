package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Plan D — converts archived live rows into virtual tombstones for sync reads.
 *
 * "Archived" here means ValidationStatus.ARCHIVED OR ValidationStatus.UNAVAILABLE,
 * matching the existing archive-content-leak gating (VideoRepository filtering).
 *
 * Real tombstones (rows where data.deleted == true) pass through untouched —
 * we do not re-stamp them; their server-side updatedAt is canonical.
 *
 * The underlying Firestore documents are NEVER mutated here. Archive recovery
 * (un-archive) is out of scope for Plan D (spec §9 D8).
 */
@Component
public class ArchiveProjector {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public ArchiveProjector(ChannelRepository channels,
                            PlaylistRepository playlists,
                            VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    public RawRow projectSubscription(RawRow row) {
        return projectIf(row, () -> channels.isArchivedById(row.id()));
    }

    public RawRow projectPlaylist(RawRow row) {
        return projectIf(row, () -> playlists.isArchivedById(row.id()));
    }

    public RawRow projectFavorite(RawRow row) {
        return projectIf(row, () -> videos.isArchivedById(row.id()));
    }

    private RawRow projectIf(RawRow row, java.util.function.BooleanSupplier archived) {
        Object deletedFlag = row.data().get("deleted");
        if (Boolean.TRUE.equals(deletedFlag)) return row;        // real tombstone — no double-process
        if (!archived.getAsBoolean()) return row;                // not archived — pass through
        Map<String, Object> tomb = new HashMap<>(row.data());
        tomb.put("deleted", true);
        return new RawRow(row.id(), tomb, row.updatedAt());
    }
}
