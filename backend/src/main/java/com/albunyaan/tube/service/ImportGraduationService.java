package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * BACKEND-IMPORT-08: Fan-out approve/reject decisions to per-user Me-list rows.
 *
 * When an admin approves or rejects a previously-unknown imported item, every user
 * who imported it has an AWAITING row in their per-user subcollection
 * (users/{uid}/subscriptions, users/{uid}/playlists, or users/{uid}/favorites).
 *
 * onApproved: flips all AWAITING rows for the given youtubeId to APPROVED.
 * onRejected: tombstones all AWAITING rows (deleted=true) so they vanish from the
 *             user's "awaiting" section.
 *
 * Both operations swallow exceptions — a fan-out failure must never propagate into
 * the admin's approve/reject response (wired in A9).
 *
 * NOTE: The collection-group query requires a composite Firestore index in production.
 * That index is added in task A11. The Firestore emulator does not enforce indexes,
 * so integration tests pass without it.
 */
@Service
public class ImportGraduationService {

    private static final Logger log = LoggerFactory.getLogger(ImportGraduationService.class);

    private final Firestore db;

    public ImportGraduationService(Firestore db) {
        this.db = db;
    }

    /** Approve all AWAITING per-user rows for the given content type and youtubeId. */
    public void onApproved(YouTubeContentType type, String youtubeId) {
        fanOut(type, youtubeId, true);
    }

    /** Reject (tombstone) all AWAITING per-user rows for the given content type and youtubeId. */
    public void onRejected(YouTubeContentType type, String youtubeId) {
        fanOut(type, youtubeId, false);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Subcollection name per content type. */
    private String coll(YouTubeContentType type) {
        return switch (type) {
            case CHANNEL  -> "subscriptions";
            case PLAYLIST -> "playlists";
            case VIDEO    -> "favorites";
            default -> throw new IllegalArgumentException("Unsupported type for fan-out: " + type);
        };
    }

    /**
     * Issue a collection-group query for all AWAITING docs matching the given
     * youtubeId and batch-update them. Batch writes are chunked at 450 documents
     * to stay below Firestore's 500-write-per-batch limit.
     *
     * All exceptions are swallowed: fan-out failure must not break the admin action.
     */
    private void fanOut(YouTubeContentType type, String youtubeId, boolean approve) {
        try {
            var snap = db.collectionGroup(coll(type))
                    .whereEqualTo("youtubeId", youtubeId)
                    .whereEqualTo("approvalStatus", "AWAITING")
                    .get().get();

            long now = System.currentTimeMillis();
            WriteBatch batch = db.batch();
            int n = 0;

            for (var doc : snap.getDocuments()) {
                Map<String, Object> upd = new HashMap<>();
                upd.put("updatedAt", now);
                if (approve) {
                    upd.put("approvalStatus", "APPROVED");
                } else {
                    upd.put("deleted", true);
                }
                batch.update(doc.getReference(), upd);

                if (++n % 450 == 0) {
                    batch.commit().get();
                    batch = db.batch();
                }
            }

            // Commit any remaining writes that didn't fill a full chunk.
            if (n > 0 && n % 450 != 0) {
                batch.commit().get();
            }

        } catch (Exception e) {
            log.error("Import graduation fan-out failed type={} youtubeId={} approve={}",
                    type, youtubeId, approve, e);
            // Never rethrow — fan-out failure must not break the admin's approve/reject.
        }
    }
}
