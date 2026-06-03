package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.sync.*;
import com.albunyaan.tube.repository.SyncRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import com.albunyaan.tube.service.ContentApprovalGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.UnaryOperator;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /**
     * Cubic R-final5 P1 — tombstone retention window for the slow-client gap.
     *
     * <p>TombstoneGcScheduler GCs tombstones older than 90 days. A client
     * paginating with {@code since=T_old} that has been offline long enough
     * for a tombstone at {@code T_old+1} to be GC'd will never see that
     * delete — the client's local view stays out of sync with no signal.
     *
     * <p>Mitigation today is observational: we WARN when a pull arrives with
     * {@code since} older than {@code now - 90d} so operators have a signal
     * that a client is in the danger zone. A protocol-level fix (server
     * returns RESYNC_REQUIRED, client clears cursor and pulls from 0) is
     * deferred — it requires a coordinated Android client update to handle
     * the new error, otherwise enabling the reject would brick existing
     * 91+-day-offline clients.
     */
    static final long OFFLINE_RESYNC_THRESHOLD_DAYS = 90L;

    private final SyncRepository repo;
    private final ArchiveProjector projector;
    private final ContentApprovalGate approvalGate;

    public SyncService(SyncRepository repo, ArchiveProjector projector, ContentApprovalGate approvalGate) {
        this.repo = repo;
        this.projector = projector;
        this.approvalGate = approvalGate;
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
        // Cubic R-final5 P1 — observe slow-client cursor that crosses the
        // tombstone GC horizon. See OFFLINE_RESYNC_THRESHOLD_DAYS docstring.
        if (since > 0) {
            long thresholdMs = Instant.now()
                    .minus(Duration.ofDays(OFFLINE_RESYNC_THRESHOLD_DAYS))
                    .toEpochMilli();
            if (since < thresholdMs) {
                log.warn("Sync pull from stale cursor: uid={} coll={} since={} (>{}d behind). "
                        + "Tombstones in (since, now-{}d) may have been GC'd; client view "
                        + "could miss deletions. Track for RESYNC_REQUIRED protocol upgrade.",
                        uid, coll, since, OFFLINE_RESYNC_THRESHOLD_DAYS, OFFLINE_RESYNC_THRESHOLD_DAYS);
            }
        }
        List<RawRow> raw = repo.pull(uid, coll, since, lastDocId, SyncRepository.SYNC_PAGE_SIZE);
        List<RawRow> projected = projectBatch.apply(raw);
        List<T> items = new ArrayList<>(projected.size());
        for (RawRow r : projected) items.add(toDto.apply(r));
        // SYNC-TAIL-01 (Cubic R7 P1) — mint a cursor for every non-empty
        // page, full or partial. Pre-fix only full pages (size == PAGE_SIZE)
        // got a cursor; combined with R5 P0 client-side cursor change, a
        // partial-tail page returned null → client never advanced past it
        // → under continuous writes the cursor stalled. `nextCursor == null`
        // now means "iterator empty" only.
        //
        // Cursor advancement still uses the underlying raw row's
        // (updatedAt, id), not the projected (virtual-tombstone) row, so
        // virtual-tombstone stamping never pulls the cursor backwards.
        Long nextCursor = null;
        String nextCursorId = null;
        if (!raw.isEmpty()) {
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
        // A10 — default "APPROVED" for docs written before this field existed
        d.setApprovalStatus((String) m.getOrDefault("approvalStatus", "APPROVED"));
        d.setSource((String) m.get("source"));
        d.setImportedAt(nullableLongOf(m.get("importedAt")));
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
        // A10 — default "APPROVED" for docs written before this field existed
        d.setApprovalStatus((String) m.getOrDefault("approvalStatus", "APPROVED"));
        d.setSource((String) m.get("source"));
        d.setImportedAt(nullableLongOf(m.get("importedAt")));
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
        // A10 — default "APPROVED" for docs written before this field existed
        d.setApprovalStatus((String) m.getOrDefault("approvalStatus", "APPROVED"));
        d.setSource((String) m.get("source"));
        d.setImportedAt(nullableLongOf(m.get("importedAt")));
        return d;
    }

    private static long longOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }
    private static Long nullableLongOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        return null;
    }
    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    /**
     * F3 — derive the stored approvalStatus from the server-side registry status.
     * Only content the registry ACTIVELY gates is withheld: a PENDING row (e.g. an
     * imported item the /resolve endpoint just queued for review) → AWAITING. REJECTED
     * is tombstoned by the callers. Everything else is APPROVED — including ids ABSENT
     * from the registry, because such content is already playable under the 404 fail-open
     * availability gate (e.g. a video favorited from an approved channel/playlist feed
     * that has no standalone Video doc). Marking those AWAITING would strand organic
     * favorites/subscriptions in a queue no admin can clear, without adding any gate the
     * fail-open design doesn't already concede. The client-supplied value is never trusted.
     */
    private static String deriveApprovalStatus(String registryStatus) {
        // Actively-gated states the admin hasn't cleared → AWAITING. This mirrors
        // PublicContentService, which 404s BOTH PENDING and REQUEST_CHANGES (an admin
        // sent the item back for revision — not yet approved/playable). Mapping
        // REQUEST_CHANGES to APPROVED would mislabel it as live in the user's Me feed
        // while the availability gate still 404s playback. REJECTED never reaches here
        // (callers tombstone it first). Everything else — APPROVED, or an id ABSENT from
        // the registry — is APPROVED so organic favorites/subscriptions aren't stranded.
        if ("PENDING".equalsIgnoreCase(registryStatus)
                || "REQUEST_CHANGES".equalsIgnoreCase(registryStatus)) {
            return "AWAITING";
        }
        return "APPROVED";
    }

    // ── Write path ───────────────────────────────────────────────────────────

    // SYNC-ECHO-01 (Cubic R7 P1) — every write-path response is projected
    // through ArchiveProjector so a client writing a row backing an archived
    // parent learns the row is virtually tombstoned immediately, instead of
    // discovering it on the next pull cycle. The Firestore document is
    // unchanged (writes are not archive-gated); only the wire echo carries
    // the virtual deleted=true flag. ArchiveProjector projection of a
    // tombstone row is a null op (deleted=true already), so the tombstone
    // paths below are symmetric without semantic change.

    public SubscriptionSyncDto upsertSubscription(String uid, String id, PutSubscriptionRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        // F3: approvalStatus is server-authoritative — derived from the content registry,
        // never trusted from the client. An admin-rejected row is tombstoned, not resurrected.
        String regStatus = approvalGate.statusOf(YouTubeContentType.CHANNEL, id);
        if ("REJECTED".equalsIgnoreCase(regStatus)) {
            return toSubscriptionDto(projector.projectSubscription(
                    repo.tombstone(uid, SyncRepository.SUBS_COLL, id)));
        }
        Map<String, Object> body = new java.util.HashMap<>();
        // F1: persist the youtubeId (== the {id} path var, server-authoritative) so
        // ImportGraduationService's collection-group fan-out — whereEqualTo("youtubeId", …)
        // — can match this row when an admin approves/rejects the content.
        body.put("youtubeId", id);
        body.put("channelUrl", req.getChannelUrl());
        body.put("name", req.getName());
        body.put("avatarUrl", req.getAvatarUrl());
        body.put("subscribedAt", req.getSubscribedAt());
        body.put("approvalStatus", deriveApprovalStatus(regStatus));
        body.put("source", req.getSource());
        body.put("importedAt", req.getImportedAt());
        return toSubscriptionDto(projector.projectSubscription(
                repo.upsert(uid, SyncRepository.SUBS_COLL, id, body)));
    }

    public SubscriptionSyncDto tombstoneSubscription(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toSubscriptionDto(projector.projectSubscription(
                repo.tombstone(uid, SyncRepository.SUBS_COLL, id)));
    }

    public PlaylistSyncDto upsertPlaylist(String uid, String id, PutPlaylistRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        // F3: see upsertSubscription — approvalStatus is server-derived, REJECTED is tombstoned.
        String regStatus = approvalGate.statusOf(YouTubeContentType.PLAYLIST, id);
        if ("REJECTED".equalsIgnoreCase(regStatus)) {
            return toPlaylistDto(projector.projectPlaylist(
                    repo.tombstone(uid, SyncRepository.PLAYLISTS_COLL, id)));
        }
        Map<String, Object> body = new java.util.HashMap<>();
        // F1: persist the youtubeId (== the {id} path var) — see upsertSubscription.
        body.put("youtubeId", id);
        body.put("playlistUrl", req.getPlaylistUrl());
        body.put("name", req.getName());
        body.put("thumbnailUrl", req.getThumbnailUrl());
        body.put("uploaderName", req.getUploaderName());
        body.put("savedAt", req.getSavedAt());
        body.put("approvalStatus", deriveApprovalStatus(regStatus));
        body.put("source", req.getSource());
        body.put("importedAt", req.getImportedAt());
        return toPlaylistDto(projector.projectPlaylist(
                repo.upsert(uid, SyncRepository.PLAYLISTS_COLL, id, body)));
    }

    public PlaylistSyncDto tombstonePlaylist(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toPlaylistDto(projector.projectPlaylist(
                repo.tombstone(uid, SyncRepository.PLAYLISTS_COLL, id)));
    }

    public FavoriteSyncDto upsertFavorite(String uid, String id, PutFavoriteRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        // F3: see upsertSubscription — approvalStatus is server-derived, REJECTED is tombstoned.
        String regStatus = approvalGate.statusOf(YouTubeContentType.VIDEO, id);
        if ("REJECTED".equalsIgnoreCase(regStatus)) {
            return toFavoriteDto(projector.projectFavorite(
                    repo.tombstone(uid, SyncRepository.FAVORITES_COLL, id)));
        }
        Map<String, Object> body = new java.util.HashMap<>();
        // F1: persist the youtubeId (== the {id} path var) — see upsertSubscription.
        body.put("youtubeId", id);
        body.put("title", req.getTitle());
        body.put("channelName", req.getChannelName());
        body.put("thumbnailUrl", req.getThumbnailUrl());
        body.put("durationSeconds", req.getDurationSeconds());
        body.put("addedAt", req.getAddedAt());
        body.put("approvalStatus", deriveApprovalStatus(regStatus));
        body.put("source", req.getSource());
        body.put("importedAt", req.getImportedAt());
        return toFavoriteDto(projector.projectFavorite(
                repo.upsert(uid, SyncRepository.FAVORITES_COLL, id, body)));
    }

    public FavoriteSyncDto tombstoneFavorite(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toFavoriteDto(projector.projectFavorite(
                repo.tombstone(uid, SyncRepository.FAVORITES_COLL, id)));
    }
}
