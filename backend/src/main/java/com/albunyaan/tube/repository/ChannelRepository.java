package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.AggregateQuery;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import com.albunyaan.tube.util.CursorUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FIREBASE-MIGRATE-03: Channel Repository (Firestore)
 *
 * All Firestore operations use configurable, operation-specific timeouts to prevent
 * indefinite blocking and thread pool exhaustion in case of network issues or Firestore unavailability.
 */
@Repository
public class ChannelRepository {

    private static final String COLLECTION_NAME = "channels";
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public ChannelRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public Channel save(Channel channel) throws ExecutionException, InterruptedException, TimeoutException {
        channel.touch();
        // Ensure derived fields stay in sync with status/exclusions
        channel.setStatus(channel.getStatus());
        channel.setExcludedItems(channel.getExcludedItems());

        if (channel.getId() == null) {
            DocumentReference docRef = getCollection().document();
            channel.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(channel.getId())
                .set(channel);

        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return channel;
    }

    public Optional<Channel> findById(String id) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(id);
        Channel channel = docRef.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObject(Channel.class);
        return Optional.ofNullable(channel);
    }

    public Optional<Channel> findByYoutubeId(String youtubeId) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("youtubeId", youtubeId)
                .limit(1)
                .get();

        List<Channel> channels = query.get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObjects(Channel.class);
        return channels.isEmpty() ? Optional.empty() : Optional.of(channels.get(0));
    }

    public List<Channel> findByStatus(String status) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> findByStatus(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> findByCategoryId(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public void deleteById(String id) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<WriteResult> result = getCollection().document(id).delete();
        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    public List<Channel> findAll() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> findAll(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> findByCategoryOrderBySubscribersDesc(String category) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("subscribers", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> findByCategoryOrderBySubscribersDesc(String category, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("subscribers", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> findAllByOrderBySubscribersDesc() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("subscribers", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> findAllByOrderBySubscribersDesc(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("subscribers", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    public List<Channel> searchByName(String query) throws ExecutionException, InterruptedException, TimeoutException {
        // Firestore doesn't support full-text search, so we'll use prefix matching
        // For production, consider using Algolia or Elasticsearch
        // Note: Filtering by status removed from query to avoid composite index requirement
        // Status filtering done in PublicContentService layer
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("name")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Count all channels using server-side aggregation
     */
    public long countAll() throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection().count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Count channels by status using server-side aggregation
     */
    public long countByStatus(String status) throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection()
                .whereEqualTo("status", status)
                .count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Find approved channels ordered by subscribers with cursor-based pagination.
     *
     * @param limit Number of items to fetch (fetch limit + 1 to detect hasNext)
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing channels and next cursor
     */
    public PaginatedResult<Channel> findApprovedBySubscribersDescWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("subscribers", Query.Direction.DESCENDING)
                .limit(limit + 1); // Fetch one extra to detect hasNext

        // Apply cursor if provided
        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: channel document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Channel> channels = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Channel channel = doc.toObject(Channel.class);
            channel.setId(doc.getId());
            channels.add(channel);

            // Generate cursor from last item
            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "subscribers");
            }
        }

        return new PaginatedResult<>(channels, nextCursor, hasNext);
    }

    /**
     * Find approved channels by category ordered by subscribers with cursor-based pagination.
     *
     * @param category Category ID to filter by
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing channels and next cursor
     */
    public PaginatedResult<Channel> findApprovedByCategoryAndSubscribersDescWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("subscribers", Query.Direction.DESCENDING)
                .limit(limit + 1);

        // Apply cursor if provided
        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: channel document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Channel> channels = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Channel channel = doc.toObject(Channel.class);
            channel.setId(doc.getId());
            channels.add(channel);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "subscribers");
            }
        }

        return new PaginatedResult<>(channels, nextCursor, hasNext);
    }

    /**
     * Generic paginated result wrapper.
     */
    public static class PaginatedResult<T> {
        private final List<T> items;
        private final String nextCursor;
        private final boolean hasNext;

        public PaginatedResult(List<T> items, String nextCursor, boolean hasNext) {
            this.items = items;
            this.nextCursor = nextCursor;
            this.hasNext = hasNext;
        }

        public List<T> getItems() {
            return items;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public boolean hasNext() {
            return hasNext;
        }
    }
}
