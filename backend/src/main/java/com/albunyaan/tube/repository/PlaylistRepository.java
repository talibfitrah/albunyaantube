package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
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
 * FIREBASE-MIGRATE-03: Playlist Repository (Firestore)
 *
 * All Firestore operations use configurable, operation-specific timeouts to prevent
 * indefinite blocking and thread pool exhaustion in case of network issues or Firestore unavailability.
 */
@Repository
public class PlaylistRepository {

    private static final String COLLECTION_NAME = "playlists";
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public PlaylistRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public Playlist save(Playlist playlist) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        playlist.touch();

        if (playlist.getId() == null) {
            DocumentReference docRef = getCollection().document();
            playlist.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(playlist.getId())
                .set(playlist);

        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return playlist;
    }

    public Optional<Playlist> findById(String id) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        DocumentReference docRef = getCollection().document(id);
        Playlist playlist = docRef.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObject(Playlist.class);
        return Optional.ofNullable(playlist);
    }

    public Optional<Playlist> findByYoutubeId(String youtubeId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("youtubeId", youtubeId)
                .limit(1)
                .get();

        List<Playlist> playlists = query.get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObjects(Playlist.class);
        return playlists.isEmpty() ? Optional.empty() : Optional.of(playlists.get(0));
    }

    public List<Playlist> findByStatus(String status) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findByStatus(String status, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find playlists by status ordered by lastValidatedAt ascending (oldest validated first).
     * This prevents validation starvation by ensuring items that haven't been validated
     * recently are prioritized over recently validated items.
     *
     * @param status The approval status to filter by
     * @param limit Maximum number of results
     * @return List of playlists ordered by lastValidatedAt ascending (nulls first in memory sort)
     */
    public List<Playlist> findByStatusOrderByLastValidatedAtAsc(String status, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("lastValidatedAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findByCategoryId(String categoryId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findByCategoryOrderByItemCountDesc(String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findByCategoryOrderByItemCountDesc(String category, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findAllByOrderByItemCountDesc() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findAllByOrderByItemCountDesc(int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> searchByTitle(String query) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Firestore doesn't support full-text search, so we'll use prefix matching
        // For production, consider using Algolia or Elasticsearch
        // Note: Filtering by status removed from query to avoid composite index requirement
        // Status filtering done in PublicContentService layer
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("title")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public void deleteById(String id) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<WriteResult> result = getCollection().document(id).delete();
        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    /**
     * Find all playlists with a safe default limit to prevent unbounded queries.
     * Uses the configured default-max-results to prevent timeout and memory issues.
     *
     * @return List of playlists (limited to default-max-results)
     * @throws ExecutionException if the Firestore operation fails
     * @throws InterruptedException if the thread is interrupted
     * @throws java.util.concurrent.TimeoutException if the operation exceeds the configured bulk query timeout
     */
    public List<Playlist> findAll() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return findAll(timeoutProperties.getDefaultMaxResults());
    }

    /**
     * Find all playlists with a specific limit.
     *
     * @param limit Maximum number of results to return
     * @return List of playlists (up to limit)
     * @throws ExecutionException if the Firestore operation fails
     * @throws InterruptedException if the thread is interrupted
     * @throws java.util.concurrent.TimeoutException if the operation exceeds the configured bulk query timeout
     */
    public List<Playlist> findAll(int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find approved playlists ordered by item count with cursor-based pagination.
     *
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing playlists and next cursor
     */
    public PaginatedResult<Playlist> findApprovedByItemCountDescWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: playlist document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Playlist> playlists = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Playlist playlist = doc.toObject(Playlist.class);
            playlist.setId(doc.getId());
            playlists.add(playlist);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "itemCount");
            }
        }

        return new PaginatedResult<>(playlists, nextCursor, hasNext);
    }

    /**
     * Find approved playlists by category ordered by item count with cursor-based pagination.
     *
     * @param category Category ID to filter by
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing playlists and next cursor
     */
    public PaginatedResult<Playlist> findApprovedByCategoryAndItemCountDescWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: playlist document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Playlist> playlists = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Playlist playlist = doc.toObject(Playlist.class);
            playlist.setId(doc.getId());
            playlists.add(playlist);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "itemCount");
            }
        }

        return new PaginatedResult<>(playlists, nextCursor, hasNext);
    }

    // ==================== Validation Status Methods ====================

    /**
     * Find playlists by validation status.
     *
     * @param status ValidationStatus to filter by
     * @return List of playlists with the specified validation status
     */
    public List<Playlist> findByValidationStatus(ValidationStatus status) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find playlists by validation status with limit.
     *
     * @param status ValidationStatus to filter by
     * @param limit Maximum number of results
     * @return List of playlists with the specified validation status
     */
    public List<Playlist> findByValidationStatus(ValidationStatus status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Count playlists by validation status using server-side aggregation.
     *
     * @param status ValidationStatus to count
     * @return Count of playlists with the specified validation status
     */
    public long countByValidationStatus(ValidationStatus status) throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Find approved playlists that need validation.
     *
     * @param limit Maximum number of results
     * @return List of approved playlists needing validation
     */
    public List<Playlist> findApprovedPlaylistsNeedingValidation(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        // Note: Playlists use lowercase "approved" status, unlike channels/videos which use "APPROVED"
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "approved")
                .orderBy("lastValidatedAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
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
