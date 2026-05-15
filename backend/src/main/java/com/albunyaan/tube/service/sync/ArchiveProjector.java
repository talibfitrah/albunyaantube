package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ─── Per-row API (back-compat for unit tests) ────────────────────────
    //
    // Each call here triggers one Firestore round-trip via `isArchivedById`.
    // Production callers should use the batch counterparts below instead —
    // a full sync page of 500 rows across all three types would otherwise
    // generate 1,500 sequential reads (cubic R5 P1 N+1 hazard).

    public RawRow projectSubscription(RawRow row) {
        return projectIf(row, channels.isArchivedById(row.id()));
    }

    public RawRow projectPlaylist(RawRow row) {
        return projectIf(row, playlists.isArchivedById(row.id()));
    }

    public RawRow projectFavorite(RawRow row) {
        return projectIf(row, videos.isArchivedById(row.id()));
    }

    // ─── Batch API (used by SyncService for full-page reads) ─────────────
    //
    // Fetches the archive set in one chunked `whereIn` round-trip per page
    // (chunks of 30) instead of one read per row. Maps each row through the
    // same projection logic as the per-row API.

    // Cubic R7 P2 — return immutable lists. Callers (SyncService streaming a
    // page through Jackson) treat the projection result as a value, never as a
    // mutable buffer; surfacing that via the type prevents accidental
    // post-projection writes that would silently desync archive flags.

    public List<RawRow> projectSubscriptions(List<RawRow> rows) {
        if (rows.isEmpty()) return List.of();
        Set<String> archived = channels.archivedIdsAmong(idsOf(rows));
        return projectBatch(rows, archived);
    }

    public List<RawRow> projectPlaylists(List<RawRow> rows) {
        if (rows.isEmpty()) return List.of();
        Set<String> archived = playlists.archivedIdsAmong(idsOf(rows));
        return projectBatch(rows, archived);
    }

    public List<RawRow> projectFavorites(List<RawRow> rows) {
        if (rows.isEmpty()) return List.of();
        Set<String> archived = videos.archivedIdsAmong(idsOf(rows));
        return projectBatch(rows, archived);
    }

    private static List<String> idsOf(List<RawRow> rows) {
        List<String> ids = new ArrayList<>(rows.size());
        for (RawRow r : rows) ids.add(r.id());
        return ids;
    }

    private static List<RawRow> projectBatch(List<RawRow> rows, Set<String> archived) {
        List<RawRow> out = new ArrayList<>(rows.size());
        for (RawRow r : rows) out.add(projectIf(r, archived.contains(r.id())));
        return Collections.unmodifiableList(out);
    }

    private static RawRow projectIf(RawRow row, boolean archived) {
        Object deletedFlag = row.data().get("deleted");
        if (Boolean.TRUE.equals(deletedFlag)) return row;        // real tombstone — no double-process
        if (!archived) return row;                               // not archived — pass through
        Map<String, Object> tomb = new HashMap<>(row.data());
        tomb.put("deleted", true);
        return new RawRow(row.id(), tomb, row.updatedAt());
    }
}
