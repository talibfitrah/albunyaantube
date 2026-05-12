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
     * Return up to {@code limit} rows in {@code type} whose {@code updatedAt} is strictly
     * greater than {@code since} (epoch millis), ordered ascending so the caller can advance
     * its cursor by taking the last row's {@code updatedAt}.
     */
    public List<RawRow> pull(String uid, String type, long since, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {
        Timestamp sinceTs = Timestamp.ofTimeSecondsAndNanos(
                since / 1_000L,
                (int) ((since % 1_000L) * 1_000_000L));
        Query q = coll(uid, type)
                .whereGreaterThan("updatedAt", sinceTs)
                .orderBy("updatedAt", Query.Direction.ASCENDING)
                .limit(limit);
        QuerySnapshot snap = q.get().get(timeouts.getBulkQuery(), TimeUnit.SECONDS);
        List<RawRow> out = new ArrayList<>(snap.size());
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            Timestamp ts = d.getTimestamp("updatedAt");
            long updatedAtMillis = ts == null ? 0L : ts.toDate().getTime();
            out.add(new RawRow(d.getId(), d.getData(), updatedAtMillis));
        }
        return out;
    }

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
