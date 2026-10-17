package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.SearchableStream;
import com.google.cloud.firestore.*;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class SearchableStreamRepository {

    private static final String COLLECTION_NAME = "searchable_streams";

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public SearchableStreamRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    /**
     * One page of visible stream documents, raw, in document-id order, for the search index's startup load
     * (spec §5.1); the index maps each one itself, skipping one it cannot (F4). Equality on one field plus
     * `__name__` order: no composite index needed.
     */
    public List<QueryDocumentSnapshot> findVisiblePage(String afterId, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getCollection().whereEqualTo("visible", true).orderBy(FieldPath.documentId()).limit(limit);
        if (afterId != null) query = query.startAfter(afterId);
        return query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getDocuments();
    }

    /**
     * Find all streams contributed by a given sourceKey — used when a source is rejected.
     */
    public List<SearchableStream> findBySourceKey(String sourceKey, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {
        return getCollection()
                .whereArrayContains("sourceKeys", sourceKey)
                .limit(limit)
                .get()
                .get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS)
                .toObjects(SearchableStream.class);
    }

    /**
     * Remove sourceKey from a stream. Sets visible=false if no source keys remain.
     */
    public void removeSource(String streamId, String sourceKey)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(streamId);
        firestore.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(docRef)
                    .get(timeoutProperties.getRead(), TimeUnit.SECONDS);
            if (!snap.exists()) return null;
            SearchableStream existing = snap.toObject(SearchableStream.class);
            List<String> keys = existing != null
                    ? new ArrayList<>(existing.getSourceKeys())
                    : new ArrayList<>();
            keys.remove(sourceKey);
            Map<String, Object> updates = new HashMap<>();
            updates.put("sourceKeys", keys);
            updates.put("visible", !keys.isEmpty());
            transaction.update(docRef, updates);
            return null;
        }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    /**
     * Mark a stream as invisible regardless of its sourceKeys.
     * Used when an individual video is archived — the stream may still
     * have valid sources from other channels/playlists, but the video
     * itself is archived and must not appear in search.
     */
    public void markInvisible(String streamId)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> updates = new HashMap<>();
        updates.put("visible", false);
        firestore.collection(COLLECTION_NAME)
                .document(streamId)
                .update(updates)
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }
}
