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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * Find videos by status ordered by createdAt descending (newest first).
     *
     * Note: Requires Firestore composite index: status (ASC) + createdAt (DESC)
     * Index definition in firestore.indexes.json:
     *   { "collectionGroup": "videos", "queryScope": "COLLECTION",
     *     "fields": [{"fieldPath": "status", "order": "ASCENDING"},
     *                {"fieldPath": "createdAt", "order": "DESCENDING"}] }
     */
    public List<Video> findByStatus(String status) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find videos by status with limit, ordered by createdAt descending (newest first).
     *
     * Note: Requires Firestore composite index: status (ASC) + createdAt (DESC)
     */
    public List<Video> findByStatus(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
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

    /**
     * Find videos by category ID with limit (bounded query).
     * This prevents quota exhaustion for large categories.
     *
     * @param categoryId Category ID to filter by
     * @param limit Maximum number of results
     * @return List of approved videos in the category
     */
    public List<Video> findByCategoryId(String categoryId, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find videos by category ID regardless of status (for status=all).
     * This allows admin Content Library to show pending/rejected items when filtering by category.
     *
     * @param categoryId Category ID to filter by
     * @param limit Maximum number of results
     * @return List of videos in the category (any status)
     */
    public List<Video> findByCategoryIdAllStatus(String categoryId, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find videos by category ID and specific status.
     * This allows admin Content Library to filter by both category and status.
     *
     * @param categoryId Category ID to filter by
     * @param status Status to filter by (APPROVED, PENDING, REJECTED)
     * @param limit Maximum number of results
     * @return List of videos matching both category and status
     */
    public List<Video> findByCategoryIdAndStatus(String categoryId, String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
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

    /**
     * Search videos by title prefix with limit (bounded query).
     * This prevents quota exhaustion for broad search queries.
     *
     * @param query Search prefix
     * @param limit Maximum number of results
     * @return List of matching videos
     */
    public List<Video> searchByTitle(String query, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("title")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .limit(limit)
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Search videos by normalized titleLower field for case-insensitive prefix matching.
     * This is the preferred search method as it supports case-insensitive queries.
     *
     * @param queryLower Lowercase search prefix
     * @param limit Maximum number of results
     * @return List of matching videos
     */
    public List<Video> searchByTitleLower(String queryLower, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("titleLower")
                .startAt(queryLower)
                .endAt(queryLower + "\uf8ff")
                .limit(limit)
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Search videos by keyword using array-contains on keywordsLower.
     * Finds videos where any keyword exactly matches the query (case-insensitive).
     * Complements prefix-based title search for improved recall.
     *
     * @param keywordLower Lowercase keyword to search for
     * @param limit Maximum number of results
     * @return List of matching videos
     */
    public List<Video> searchByKeyword(String keywordLower, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .whereArrayContains("keywordsLower", keywordLower)
                .limit(limit)
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
     * Find all videos ordered by createdAt ascending (oldest first) with limit.
     * Used by admin Content Library when sort=oldest is requested.
     *
     * @param limit Maximum number of results
     * @return List of videos ordered oldest-first
     */
    public List<Video> findAllOrderByCreatedAtAsc(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find videos by status ordered by createdAt ascending (oldest first) with limit.
     * Used by admin Content Library when sort=oldest is requested with status filter.
     *
     * Note: Requires Firestore composite index: status (ASC) + createdAt (ASC)
     * Index definition in firestore.indexes.json:
     *   { "collectionGroup": "videos", "queryScope": "COLLECTION",
     *     "fields": [{"fieldPath": "status", "order": "ASCENDING"},
     *                {"fieldPath": "createdAt", "order": "ASCENDING"}] }
     *
     * @param status Status to filter by
     * @param limit Maximum number of results
     * @return List of videos ordered oldest-first
     */
    public List<Video> findByStatusOrderByCreatedAtAsc(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find approved and available videos ordered by upload date with cursor-based pagination.
     * Over-fetches to account for filtering out UNAVAILABLE/ARCHIVED videos after the query.
     *
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing videos and next cursor
     */
    public PaginatedResult<Video> findApprovedByUploadedAtDescWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Over-fetch by 3x to account for filtering out UNAVAILABLE/ARCHIVED videos
        // This ensures we consistently get enough items after filtering
        int fetchLimit = limit * 3 + 1;
        Query query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(fetchLimit);

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

        // Filter out UNAVAILABLE and ARCHIVED videos after fetching
        List<Video> availableVideos = new ArrayList<>();
        QueryDocumentSnapshot lastIncludedDoc = null;

        for (QueryDocumentSnapshot doc : docs) {
            Video video = doc.toObject(Video.class);
            video.setId(doc.getId());

            // Skip unavailable videos
            ValidationStatus validationStatus = video.getValidationStatus();
            if (validationStatus == ValidationStatus.UNAVAILABLE || validationStatus == ValidationStatus.ARCHIVED) {
                continue;
            }

            availableVideos.add(video);

            // Stop once we have enough (overflow indicates hasNext)
            if (availableVideos.size() > limit) {
                break;
            }

            // Track cursor doc only for items that will be in final result
            lastIncludedDoc = doc;
        }

        boolean hasNext = availableVideos.size() > limit;
        if (hasNext) {
            availableVideos = new ArrayList<>(availableVideos.subList(0, limit));
        }

        String nextCursor = null;
        if (hasNext && lastIncludedDoc != null) {
            nextCursor = CursorUtils.encodeFromSnapshot(lastIncludedDoc, "uploadedAt");
        }

        return new PaginatedResult<>(availableVideos, nextCursor, hasNext);
    }

    /**
     * Find approved and available videos by category ordered by upload date with cursor-based pagination.
     * Over-fetches to account for filtering out UNAVAILABLE/ARCHIVED videos after the query.
     *
     * @param category Category ID to filter by
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing videos and next cursor
     */
    public PaginatedResult<Video> findApprovedByCategoryAndUploadedAtDescWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        // Over-fetch by 3x to account for filtering out UNAVAILABLE/ARCHIVED videos
        int fetchLimit = limit * 3 + 1;
        Query query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(fetchLimit);

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

        // Filter out UNAVAILABLE and ARCHIVED videos after fetching
        List<Video> availableVideos = new ArrayList<>();
        QueryDocumentSnapshot lastIncludedDoc = null;

        for (QueryDocumentSnapshot doc : docs) {
            Video video = doc.toObject(Video.class);
            video.setId(doc.getId());

            // Skip unavailable videos
            ValidationStatus validationStatus = video.getValidationStatus();
            if (validationStatus == ValidationStatus.UNAVAILABLE || validationStatus == ValidationStatus.ARCHIVED) {
                continue;
            }

            availableVideos.add(video);

            // Stop once we have enough (overflow indicates hasNext)
            if (availableVideos.size() > limit) {
                break;
            }

            // Track cursor doc only for items that will be in final result
            lastIncludedDoc = doc;
        }

        boolean hasNext = availableVideos.size() > limit;
        if (hasNext) {
            availableVideos = new ArrayList<>(availableVideos.subList(0, limit));
        }

        String nextCursor = null;
        if (hasNext && lastIncludedDoc != null) {
            nextCursor = CursorUtils.encodeFromSnapshot(lastIncludedDoc, "uploadedAt");
        }

        return new PaginatedResult<>(availableVideos, nextCursor, hasNext);
    }

    // ==================== Display Order Methods ====================

    /**
     * Find approved videos ordered by displayOrder ascending (custom admin order).
     * Videos with null displayOrder are placed first (Firestore default for ASCENDING).
     *
     * Note: Requires Firestore composite index: status (ASC) + displayOrder (ASC)
     * WARNING: This method returns unbounded results. Consider using the overloaded
     * version with a limit parameter for production use.
     *
     * @return List of approved videos ordered by displayOrder
     */
    public List<Video> findApprovedByDisplayOrderAsc() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Find approved videos ordered by displayOrder ascending with limit.
     *
     * Note: Requires Firestore composite index: status (ASC) + displayOrder (ASC)
     *
     * @param limit Maximum number of results
     * @return List of approved videos ordered by displayOrder
     */
    public List<Video> findApprovedByDisplayOrderAsc(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Video.class);
    }

    /**
     * Batch update displayOrder for multiple videos.
     * Used by admin reordering feature.
     *
     * @param orderUpdates Map of video ID to new displayOrder value
     * @throws IllegalArgumentException if orderUpdates contains more than 500 entries (Firestore limit)
     */
    public void batchUpdateDisplayOrder(java.util.Map<String, Integer> orderUpdates) throws ExecutionException, InterruptedException, TimeoutException {
        if (orderUpdates == null || orderUpdates.isEmpty()) {
            return;
        }

        if (orderUpdates.size() > 500) {
            throw new IllegalArgumentException("Cannot update more than 500 videos in a single batch. Received: " + orderUpdates.size());
        }

        var batch = firestore.batch();
        for (var entry : orderUpdates.entrySet()) {
            DocumentReference docRef = getCollection().document(entry.getKey());
            batch.update(docRef, "displayOrder", entry.getValue(), "updatedAt", com.google.cloud.Timestamp.now());
        }
        batch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
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

    /**
     * Batch-fetch videos by their document IDs using Firestore getAll().
     * Returns a map of ID to Video for efficient lookup.
     */
    public Map<String, Video> findAllByIds(List<String> ids)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Video> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) return result;

        List<DocumentReference> refs = new ArrayList<>();
        for (String id : ids) {
            refs.add(getCollection().document(id));
        }

        List<com.google.cloud.firestore.DocumentSnapshot> snapshots =
                firestore.getAll(refs.toArray(new DocumentReference[0]))
                        .get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);

        for (com.google.cloud.firestore.DocumentSnapshot snap : snapshots) {
            if (snap.exists()) {
                Video v = snap.toObject(Video.class);
                if (v != null) {
                    v.setId(snap.getId());
                    result.put(snap.getId(), v);
                }
            }
        }
        return result;
    }
}
