package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class SyncRepository {

    public static final int    SYNC_PAGE_SIZE = 500;
    public static final String SUBS_COLL      = "subscriptions";
    public static final String PLAYLISTS_COLL = "playlists";
    public static final String FAVORITES_COLL = "favorites";

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeouts;

    public SyncRepository(Firestore firestore, FirestoreTimeoutProperties timeouts) {
        this.firestore = firestore;
        this.timeouts  = timeouts;
    }

    /** Internal representation: doc-id, body map, server updatedAt in epoch millis. */
    public record RawRow(String id, Map<String, Object> data, long updatedAt) {}

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CollectionReference coll(String uid, String type) {
        return firestore.collection("users").document(uid).collection(type);
    }

    // -------------------------------------------------------------------------
    // Pull (cursor-based read)
    // -------------------------------------------------------------------------

    /**
     * Return up to {@code limit} rows in {@code type} ordered ascending by
     * {@code (updatedAt, __name__)} so the caller can advance its compound
     * cursor by taking the last row's {@code (updatedAt, docId)} pair.
     *
     * <p>When {@code lastDocId} is provided the query uses
     * {@code startAfter(sinceTs, lastDocId)}, which correctly skips past the
     * specific tied row even when multiple rows share the same {@code updatedAt}
     * millisecond — previously the legacy {@code whereGreaterThan("updatedAt", since)}
     * dropped every same-ms row on page boundaries (cubic R3/R4 P1 data-loss
     * surface). Legacy callers that pass only {@code since} still fall through
     * to the strict-greater-than path until they migrate.
     */
    public List<RawRow> pull(String uid, String type, long since, String lastDocId, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Clamp negative since to 0 so Timestamp.ofTimeSecondsAndNanos can't
        // throw on the negative nanos arg produced by a legacy `-1` cursor
        // (cubic R5 P2).
        long sinceClamped = Math.max(since, 0L);
        Query q = coll(uid, type)
                .orderBy("updatedAt", Query.Direction.ASCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        if (sinceClamped > 0L) {
            Timestamp sinceTs = Timestamp.ofTimeSecondsAndNanos(
                    sinceClamped / 1_000L,
                    (int) ((sinceClamped % 1_000L) * 1_000_000L));
            if (lastDocId != null && !lastDocId.isBlank()) {
                q = q.startAfter(sinceTs, lastDocId);
            } else {
                q = q.whereGreaterThan("updatedAt", sinceTs);
            }
        }
        QuerySnapshot snap = q.limit(limit).get().get(timeouts.getBulkQuery(), TimeUnit.SECONDS);
        List<RawRow> out = new ArrayList<>(snap.size());
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            Timestamp ts = d.getTimestamp("updatedAt");
            long updatedAtMillis = ts == null ? 0L : ts.toDate().getTime();
            out.add(new RawRow(d.getId(), d.getData(), updatedAtMillis));
        }
        return out;
    }

    // The legacy 4-arg pull(uid, type, since, limit) overload was removed
    // (cubic R5 P0): it called the strict-greater-than branch which silently
    // dropped same-ms ties — exactly the bug the compound cursor closes.
    // All callers must thread the compound cursor through.

    // -------------------------------------------------------------------------
    // Upsert (create or merge)
    // -------------------------------------------------------------------------

    /**
     * Merge {@code payload} into {@code users/{uid}/{type}/{id}}, forcing
     * {@code deleted=false} and a server-side {@code updatedAt}.  Returns the
     * persisted row with its resolved timestamp.
     */
    public RawRow upsert(String uid, String type, String id, Map<String, Object> payload)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference ref = coll(uid, type).document(id);
        Map<String, Object> body = new HashMap<>(payload);
        body.put("deleted",   false);
        body.put("updatedAt", FieldValue.serverTimestamp());
        ref.set(body, SetOptions.merge()).get(timeouts.getWrite(), TimeUnit.SECONDS);
        DocumentSnapshot stored = ref.get().get(timeouts.getRead(), TimeUnit.SECONDS);
        Timestamp ts = stored.getTimestamp("updatedAt");
        return new RawRow(id, stored.getData(), ts == null ? 0L : ts.toDate().getTime());
    }

    // -------------------------------------------------------------------------
    // Tombstone (soft-delete)
    // -------------------------------------------------------------------------

    /**
     * Soft-delete {@code users/{uid}/{type}/{id}} by merging {@code deleted=true}
     * and a fresh server-side {@code updatedAt}.  Returns the persisted row.
     */
    public RawRow tombstone(String uid, String type, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference ref = coll(uid, type).document(id);
        Map<String, Object> body = new HashMap<>();
        body.put("deleted",   true);
        body.put("updatedAt", FieldValue.serverTimestamp());
        ref.set(body, SetOptions.merge()).get(timeouts.getWrite(), TimeUnit.SECONDS);
        DocumentSnapshot stored = ref.get().get(timeouts.getRead(), TimeUnit.SECONDS);
        Timestamp ts = stored.getTimestamp("updatedAt");
        return new RawRow(id, stored.getData(), ts == null ? 0L : ts.toDate().getTime());
    }
}
