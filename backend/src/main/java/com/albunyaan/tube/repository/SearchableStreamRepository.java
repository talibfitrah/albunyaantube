package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.SearchableStream;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class SearchableStreamRepository {

    private static final Logger log = LoggerFactory.getLogger(SearchableStreamRepository.class);
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
     * Upsert stream data — merge so sourceKeys are accumulated via arrayUnion.
     * Call addSource() separately to add the sourceKey atomically.
     */
    public void upsert(SearchableStream stream, String sourceKey)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> data = new HashMap<>();
        data.put("title", stream.getTitle());
        data.put("titleNorm", stream.getTitleNorm());
        data.put("thumbnailUrl", stream.getThumbnailUrl());
        data.put("channelId", stream.getChannelId());
        data.put("channelName", stream.getChannelName());
        data.put("streamType", stream.getStreamType());
        data.put("durationSeconds", stream.getDurationSeconds());
        data.put("viewCount", stream.getViewCount());
        data.put("searchTokens", stream.getSearchTokens());
        data.put("sourceKeys", FieldValue.arrayUnion(sourceKey));
        data.put("visible", true);
        data.put("indexedAt", Timestamp.now());
        data.put("lastSeenAt", Timestamp.now());

        getCollection().document(stream.getStreamId())
                .set(data, SetOptions.merge())
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    /**
     * Search streams by a single token. Caller intersects multiple tokens in memory.
     */
    public List<SearchableStream> searchByToken(String token, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {
        return getCollection()
                .whereArrayContains("searchTokens", token)
                .whereEqualTo("visible", true)
                .limit(limit)
                .get()
                .get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS)
                .toObjects(SearchableStream.class);
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
}
