package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
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
 * FIREBASE-MIGRATE-03: Video Repository (Firestore)
 *
 * All Firestore operations use configurable, operation-specific timeouts to prevent
 * indefinite blocking and thread pool exhaustion in case of network issues or Firestore unavailability.
 */
@Repository
public class VideoRepository {

    private static final String COLLECTION_NAME = "videos";
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public VideoRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public Video save(Video video) throws ExecutionException, InterruptedException, TimeoutException {
        video.touch();

        if (video.getId() == null) {
            DocumentReference docRef = getCollection().document();
            video.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(video.getId())
                .set(video);

        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return video;
    }

    public Optional<Video> findById(String id) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(id);
        Video video = docRef.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObject(Video.class);
        return Optional.ofNullable(video);
    }

    public Optional<Video> findByYoutubeId(String youtubeId) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("youtubeId", youtubeId)
                .limit(1)
                .get();

        List<Video> videos = query.get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObjects(Video.class);
        return videos.isEmpty() ? Optional.empty() : Optional.of(videos.get(0));
    }

    /**
     * Find videos by status.
     * Note: Results are unordered to avoid requiring a composite Firestore index.
     * Callers requiring ordering should sort results in memory.
     */
    public List<Video> findByStatus(String status) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find videos by status with limit.
     * Note: Results are unordered to avoid requiring a composite Firestore index.
     * Callers requiring ordering should sort results in memory.
     */
    public List<Video> findByStatus(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find videos by status ordered by lastValidatedAt ascending (oldest validated first).
     * This prevents validation starvation by ensuring items that haven't been validated
     * recently are prioritized over recently validated items.
     *
     * Note: Requires Firestore composite index: status (ASC) + lastValidatedAt (ASC)
     * Firestore automatically places null lastValidatedAt values first when ordering ascending.
     *
     * @param status The approval status to filter by
     * @param limit Maximum number of results
     * @return List of videos ordered by lastValidatedAt ascending (nulls first)
     */
    public List<Video> findByStatusOrderByLastValidatedAtAsc(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("lastValidatedAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> findByCategoryId(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> findByCategoryIdWithLimit(String categoryId, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> findByChannelIdAndStatus(String channelId, String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("channelId", channelId)
                .whereEqualTo("status", status)
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> findByCategoryOrderByUploadedAtDesc(String category) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> findByCategoryOrderByUploadedAtDesc(String category, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> findAllByOrderByUploadedAtDesc() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> findAllByOrderByUploadedAtDesc(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public List<Video> searchByTitle(String query) throws ExecutionException, InterruptedException, TimeoutException {
        // Firestore doesn't support full-text search, so we'll use prefix matching
        // For production, consider using Algolia or Elasticsearch
        // Note: Filtering by status removed from query to avoid composite index requirement
        // Status filtering done in PublicContentService layer
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("title")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    public void deleteById(String id) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<WriteResult> result = getCollection().document(id).delete();
        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    /**
     * Find all videos with a safe default limit to prevent unbounded queries.
     * Uses the configured default-max-results to prevent timeout and memory issues.
     *
     * @return List of videos (limited to default-max-results)
     * @throws ExecutionException if the Firestore operation fails
     * @throws InterruptedException if the thread is interrupted
     * @throws TimeoutException if the operation exceeds the configured bulk query timeout
     */
    public List<Video> findAll() throws ExecutionException, InterruptedException, TimeoutException {
        return findAll(timeoutProperties.getDefaultMaxResults());
    }

    /**
     * Find all videos with a specific limit.
     *
     * @param limit Maximum number of results to return
     * @return List of videos (up to limit)
     * @throws ExecutionException if the Firestore operation fails
     * @throws InterruptedException if the thread is interrupted
     * @throws TimeoutException if the operation exceeds the configured bulk query timeout
     */
    public List<Video> findAll(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find approved videos ordered by upload date with cursor-based pagination.
     *
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing videos and next cursor
     */
    public PaginatedResult<Video> findApprovedByUploadedAtDescWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: video document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Video> videos = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Video video = doc.toObject(Video.class);
            video.setId(doc.getId());
            videos.add(video);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "uploadedAt");
            }
        }

        return new PaginatedResult<>(videos, nextCursor, hasNext);
    }

    /**
     * Find approved videos by category ordered by upload date with cursor-based pagination.
     *
     * @param category Category ID to filter by
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing videos and next cursor
     */
    public PaginatedResult<Video> findApprovedByCategoryAndUploadedAtDescWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: video document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Video> videos = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Video video = doc.toObject(Video.class);
            video.setId(doc.getId());
            videos.add(video);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "uploadedAt");
            }
        }

        return new PaginatedResult<>(videos, nextCursor, hasNext);
    }

    // ==================== Validation Status Methods ====================

    /**
     * Find videos by validation status.
     *
     * @param status ValidationStatus to filter by
     * @return List of videos with the specified validation status
     */
    public List<Video> findByValidationStatus(ValidationStatus status) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find videos by validation status with limit.
     *
     * @param status ValidationStatus to filter by
     * @param limit Maximum number of results
     * @return List of videos with the specified validation status
     */
    public List<Video> findByValidationStatus(ValidationStatus status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Count videos by validation status using server-side aggregation.
     *
     * @param status ValidationStatus to count
     * @return Count of videos with the specified validation status
     */
    public long countByValidationStatus(ValidationStatus status) throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
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
