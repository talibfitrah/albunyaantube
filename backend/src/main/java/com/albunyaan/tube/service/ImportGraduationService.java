package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * BACKEND-IMPORT-08: Fan-out approve/reject decisions to per-user Me-list rows.
 *
 * When an admin approves or rejects a previously-unknown imported item, every user
 * who imported it has an AWAITING row in their per-user subcollection
 * (users/{uid}/subscriptions, users/{uid}/playlists, or users/{uid}/favorites).
 *
 * onApproved: flips all AWAITING rows for the given youtubeId to APPROVED.
 * onRejected: tombstones every row for the id (deleted=true) so the content leaves the user's
 *             lists — including rows that had already been approved, since content can be
 *             rejected after the fact and would otherwise linger in their app forever.
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

    /** Firestore's cap on values in a {@code whereIn} filter. */
    private static final int WHERE_IN_LIMIT = 30;

    /**
     * Per-call deadline. The bulk fan-out runs on the admin's request thread, so an unbounded
     * wait on a stalled Firestore call would hang the request rather than degrade it.
     */
    private static final long FAN_OUT_TIMEOUT_SECONDS = 30L;

    /** Rows fetched and written per pass. Firestore's per-batch write limit is 500. */
    private static final int ROWS_PER_PASS = 450;

    /** Writes per Firestore batch. */
    private static final int BATCH_WRITE_LIMIT = 450;

    /** Ceiling on the whole fan-out, not just one call — it runs on the admin's request thread. */
    private static final long FAN_OUT_TOTAL_BUDGET_SECONDS = 60L;

    private final Firestore db;

    public ImportGraduationService(Firestore db) {
        this.db = db;
    }

    /** Approve all AWAITING per-user rows for the given content type and youtubeId. */
    public void onApproved(YouTubeContentType type, String youtubeId) {
        fanOut(type, youtubeId, true);
    }

    /**
     * Finding 3: personal approval. Approves every AWAITING per-user row for the id
     * (exactly like {@link #onApproved}) but RETURNS the set of Firebase UIDs whose rows
     * were flipped, so the caller can persist them as the registry item's personalGrants.
     * The per-user sync derive then keeps these users APPROVED while a later importer of
     * the same id (not in the set) stays AWAITING — so the item never leaks publicly.
     */
    public Set<String> onApprovedPersonal(YouTubeContentType type, String youtubeId) {
        return fanOut(type, youtubeId, true);
    }

    /**
     * Read-only: the Firebase UIDs of every user with an AWAITING per-user row for this content
     * type + youtubeId. NO writes — it does not flip any row.
     *
     * Used by the personal-approval path to compute the grant list BEFORE the registry CAS write,
     * so status + visibility + grants land in one atomic write. (Doing the grant write second,
     * after the CAS, let a crash/transient error strand the item APPROVED+PERSONAL with null
     * grants — which the PENDING status guard then made unrecoverable on retry.) The row-flip
     * side effect is applied separately by {@link #onApprovedPersonal} once the CAS has won.
     */
    public Set<String> awaitingUids(YouTubeContentType type, String youtubeId) {
        Set<String> uids = new HashSet<>();
        if (youtubeId == null || youtubeId.isBlank()) {
            return uids;
        }
        try {
            var snap = db.collectionGroup(coll(type))
                    .whereEqualTo("youtubeId", youtubeId)
                    .whereEqualTo("approvalStatus", "AWAITING")
                    .get().get();
            for (var doc : snap.getDocuments()) {
                // Path is users/{uid}/{coll}/{docId}; the doc's grandparent is the user doc.
                var userRef = doc.getReference().getParent().getParent();
                if (userRef != null) {
                    uids.add(userRef.getId());
                }
            }
        } catch (Exception e) {
            log.error("awaitingUids query failed type={} youtubeId={}", type, youtubeId, e);
            // Caller (collectPersonalGrants) still grants the submitter on an empty/failed read.
        }
        return uids;
    }

    /** Reject: tombstone every per-user row for this content, whatever state it had reached. */
    public void onRejected(YouTubeContentType type, String youtubeId) {
        fanOut(type, youtubeId, false);
    }

    /** Approve every AWAITING per-user row for any of {@code youtubeIds}. See {@link #fanOutAll}. */
    public void onApprovedAll(YouTubeContentType type, Set<String> youtubeIds) {
        fanOutAll(type, youtubeIds, true);
    }

    /** Tombstone every per-user row for any of {@code youtubeIds}, whatever state. See {@link #fanOutAll}. */
    public void onRejectedAll(YouTubeContentType type, Set<String> youtubeIds) {
        fanOutAll(type, youtubeIds, false);
    }

    /**
     * Bulk form of {@link #fanOut}: one collection-group query per chunk of ids rather than one
     * per id.
     *
     * <p>A 500-item bulk action would otherwise issue 500 sequential collection-group queries and
     * write batches inside the admin's request, after the status change itself had already
     * committed — long enough to time out, so a fan-out that was merely slow would surface as a
     * failed bulk action that had in fact succeeded. Chunked at Firestore's 30-value {@code whereIn}
     * limit, that becomes 17 queries, which is fast enough to stay on the request thread where a
     * failure is at least visible.
     *
     * <p>Exceptions are swallowed per chunk, as in {@link #fanOut}: fan-out failure must never
     * break the admin action.
     */
    private void fanOutAll(YouTubeContentType type, Set<String> youtubeIds, boolean approve) {
        List<String> ids = youtubeIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        long deadline = deadline();

        if (!approve) {
            // Reject is walked per id. It has no status filter to narrow on, so it needs the
            // document-cursor walk in drainReject to cover everyone deterministically — a
            // whereIn query with a bare limit would tombstone an arbitrary subset and leave the
            // rest holding rejected content forever. Slower, and correct.
            for (String id : ids) {
                if (System.nanoTime() > deadline) {
                    log.warn("Bulk reject fan-out ran out of budget for type={} — {} ids not cleared",
                            type, ids.size());
                    return;
                }
                drainReject(type, id);
            }
            return;
        }

        // Approve chunks at Firestore's whereIn limit: 500 items become 17 queries rather than
        // 500, which is what keeps the fan-out on the admin's request thread where a failure is
        // at least visible. Safe to chunk because the AWAITING filter makes each pass consume
        // its own matches.
        for (int start = 0; start < ids.size(); start += WHERE_IN_LIMIT) {
            if (System.nanoTime() > deadline) {
                log.warn("Bulk approve fan-out ran out of budget for type={} after {} of {} ids "
                        + "— the rest keep their AWAITING rows until the next decision", type, start, ids.size());
                return;
            }
            List<String> chunk = ids.subList(start, Math.min(start + WHERE_IN_LIMIT, ids.size()));
            try {
                while (System.nanoTime() < deadline) {
                    var snap = db.collectionGroup(coll(type))
                            .whereIn("youtubeId", chunk)
                            .whereEqualTo("approvalStatus", "AWAITING")
                            .limit(ROWS_PER_PASS)
                            .get().get(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (snap.isEmpty()) {
                        break;
                    }
                    commitPage(snap.getDocuments(), true, null);
                    if (snap.size() < ROWS_PER_PASS) {
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("Bulk graduation fan-out failed type={} ids={} approve=true",
                        type, chunk.size(), e);
            }
        }
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
    private Set<String> fanOut(YouTubeContentType type, String youtubeId, boolean approve) {
        // A blank/null youtubeId would issue whereEqualTo("youtubeId", null) — a wasted
        // collection-group query that matches nothing useful. Organic registry items can
        // lack a youtubeId; skip rather than scan. Mirrors ContentApprovalGate's blank guard.
        if (youtubeId == null || youtubeId.isBlank()) {
            return new HashSet<>();
        }
        return approve ? drainApprove(type, youtubeId) : drainReject(type, youtubeId);
    }

    /**
     * Approve one id: flip the rows of everyone still waiting.
     *
     * <p>Self-consuming — the update clears the very filter the query selects on, so a full page
     * means there is more and the loop ends when nothing matches. Paged rather than fetched whole
     * so a widely-held item cannot pull an unbounded result set onto the request thread.
     *
     * @return the uids whose rows were flipped, which the personal-approval path persists as the
     *         item's grantees
     */
    private Set<String> drainApprove(YouTubeContentType type, String youtubeId) {
        Set<String> affectedUids = new HashSet<>();
        long deadline = deadline();
        try {
            while (System.nanoTime() < deadline) {
                var snap = db.collectionGroup(coll(type))
                        .whereEqualTo("youtubeId", youtubeId)
                        .whereEqualTo("approvalStatus", "AWAITING")
                        .limit(ROWS_PER_PASS)
                        .get().get(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (snap.isEmpty()) {
                    return affectedUids;
                }
                commitPage(snap.getDocuments(), true, affectedUids);
                if (snap.size() < ROWS_PER_PASS) {
                    return affectedUids;
                }
            }
            log.warn("Approve fan-out ran out of budget for type={} youtubeId={}", type, youtubeId);
        } catch (Exception e) {
            // Never rethrow — fan-out failure must not break the admin's decision.
            log.error("Import graduation fan-out failed type={} youtubeId={} approve=true", type, youtubeId, e);
        }
        return affectedUids;
    }

    /**
     * Reject one id: tombstone every copy, whatever state it had reached.
     *
     * <p>Walked with a document cursor, not a repeated filter. Setting {@code deleted} does not
     * change what the query selects on, so a self-consuming loop would never end; and a bare
     * {@code limit} with no ordering would tombstone an arbitrary subset, leaving some people
     * holding rejected content forever while others lost it.
     *
     * <p>One equality plus document-id ordering, which Firestore serves without a composite index.
     * An index that must be deployed before this works is a silent failure waiting to happen, and
     * silent failure here is the original bug.
     */
    private Set<String> drainReject(YouTubeContentType type, String youtubeId) {
        long deadline = deadline();
        try {
            QueryDocumentSnapshot last = null;
            while (System.nanoTime() < deadline) {
                Query query = db.collectionGroup(coll(type))
                        .whereEqualTo("youtubeId", youtubeId)
                        .orderBy(FieldPath.documentId());
                if (last != null) {
                    query = query.startAfter(last);
                }
                var snap = query.limit(ROWS_PER_PASS)
                        .get().get(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (snap.isEmpty()) {
                    return new HashSet<>();
                }
                commitPage(snap.getDocuments(), false, null);
                if (snap.size() < ROWS_PER_PASS) {
                    return new HashSet<>();
                }
                last = snap.getDocuments().get(snap.size() - 1);
            }
            log.warn("Reject fan-out ran out of budget for type={} youtubeId={} — some copies "
                    + "were not cleared", type, youtubeId);
        } catch (Exception e) {
            log.error("Import graduation fan-out failed type={} youtubeId={} approve=false", type, youtubeId, e);
        }
        return new HashSet<>();
    }

    /** Write one page of updates, chunked at Firestore's per-batch write limit. */
    private void commitPage(List<QueryDocumentSnapshot> docs, boolean approve, Set<String> uidSink)
            throws Exception {
        WriteBatch batch = db.batch();
        int n = 0;
        for (QueryDocumentSnapshot doc : docs) {
            Map<String, Object> upd = new HashMap<>();
            // Must be a Firestore Timestamp (not numeric millis): the sync delta-pull orders by
            // updatedAt and reads it via getTimestamp(). A raw long sorts in the wrong type-band,
            // so the cursor never re-pulls the row, and getTimestamp() throws on a numeric field.
            upd.put("updatedAt", FieldValue.serverTimestamp());
            if (approve) {
                upd.put("approvalStatus", "APPROVED");
            } else {
                upd.put("deleted", true);
            }
            batch.update(doc.getReference(), upd);

            if (uidSink != null) {
                // Path is users/{uid}/{coll}/{docId}; the doc's grandparent is the user doc, whose
                // id is the owning uid. Recorded as its update is staged, so a later failed commit
                // can leave this a superset — benign for the personal-grant caller, whose per-user
                // derive re-applies APPROVED from the persisted grants (never a leak).
                var userRef = doc.getReference().getParent().getParent();
                if (userRef != null) {
                    uidSink.add(userRef.getId());
                }
            }
            if (++n % BATCH_WRITE_LIMIT == 0) {
                batch.commit().get(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                batch = db.batch();
            }
        }
        if (n % BATCH_WRITE_LIMIT != 0) {
            batch.commit().get(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(FAN_OUT_TOTAL_BUDGET_SECONDS);
    }
}
