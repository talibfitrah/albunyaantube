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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PlaylistRepository.class);
    private static final String COLLECTION_NAME = "playlists";

    /**
     * Standard approval status value used across all content types (channels, playlists, videos).
     * This must match the value set by approval controllers and seeders.
     */
    private static final String STATUS_APPROVED = "APPROVED";

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
     * Note: Requires Firestore composite index: status (ASC) + lastValidatedAt (ASC)
     * Firestore automatically places null lastValidatedAt values first when ordering ascending.
     *
     * @param status The approval status to filter by
     * @param limit Maximum number of results
     * @return List of playlists ordered by lastValidatedAt ascending (nulls first)
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
                .whereEqualTo("status", STATUS_APPROVED)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find playlists by category ID with limit (bounded query).
     * This prevents quota exhaustion for large categories.
     *
     * @param categoryId Category ID to filter by
     * @param limit Maximum number of results
     * @return List of approved playlists in the category
     */
    public List<Playlist> findByCategoryId(String categoryId, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", STATUS_APPROVED)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find playlists by category ID regardless of status (for status=all).
     * This allows admin Content Library to show pending/rejected items when filtering by category.
     *
     * @param categoryId Category ID to filter by
     * @param limit Maximum number of results
     * @return List of playlists in the category (any status)
     */
    public List<Playlist> findByCategoryIdAllStatus(String categoryId, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find playlists by category ID and specific status.
     * This allows admin Content Library to filter by both category and status.
     *
     * @param categoryId Category ID to filter by
     * @param status Status to filter by (APPROVED, PENDING, REJECTED)
     * @param limit Maximum number of results
     * @return List of playlists matching both category and status
     */
    public List<Playlist> findByCategoryIdAndStatus(String categoryId, String status, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findByCategoryOrderByItemCountDesc(String category) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", STATUS_APPROVED)
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findByCategoryOrderByItemCountDesc(String category, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", STATUS_APPROVED)
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findAllByOrderByItemCountDesc() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", STATUS_APPROVED)
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    public List<Playlist> findAllByOrderByItemCountDesc(int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", STATUS_APPROVED)
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

    /**
     * Search playlists by title prefix with limit (bounded query).
     * This prevents quota exhaustion for broad search queries.
     *
     * @param query Search prefix
     * @param limit Maximum number of results
     * @return List of matching playlists
     */
    public List<Playlist> searchByTitle(String query, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("title")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .limit(limit)
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Search playlists by normalized titleLower field for case-insensitive prefix matching.
     * This is the preferred search method as it supports case-insensitive queries.
     *
     * @param queryLower Lowercase search prefix
     * @param limit Maximum number of results
     * @return List of matching playlists
     */
    public List<Playlist> searchByTitleLower(String queryLower, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("titleLower")
                .startAt(queryLower)
                .endAt(queryLower + "\uf8ff")
                .limit(limit)
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Search playlists by keyword using array-contains on keywordsLower.
     * Finds playlists where any keyword exactly matches the query (case-insensitive).
     * Complements prefix-based title search for improved recall.
     *
     * @param keywordLower Lowercase keyword to search for
     * @param limit Maximum number of results
     * @return List of matching playlists
     */
    public List<Playlist> searchByKeyword(String keywordLower, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .whereArrayContains("keywordsLower", keywordLower)
                .limit(limit)
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
     * Find all playlists ordered by createdAt ascending (oldest first) with limit.
     * Used by admin Content Library when sort=oldest is requested.
     *
     * @param limit Maximum number of results
     * @return List of playlists ordered oldest-first
     */
    public List<Playlist> findAllOrderByCreatedAtAsc(int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find playlists by status ordered by createdAt ascending (oldest first) with limit.
     * Used by admin Content Library when sort=oldest is requested with status filter.
     *
     * <p>Requires Firestore composite index: status (ASC) + createdAt (ASC)</p>
     *
     * @param status Status to filter by
     * @param limit Maximum number of results
     * @return List of playlists ordered oldest-first
     */
    public List<Playlist> findByStatusOrderByCreatedAtAsc(String status, int limit) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.ASCENDING)
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
                .whereEqualTo("status", STATUS_APPROVED)
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
                .whereEqualTo("status", STATUS_APPROVED)
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

    // ==================== Display Order Methods ====================

    /**
     * Find approved playlists ordered by displayOrder ascending (custom admin order).
     * Playlists with null displayOrder are placed first (Firestore default for ASCENDING).
     *
     * <p>Requires Firestore composite index: status (ASC) + displayOrder (ASC)</p>
     *
     * @return List of approved playlists ordered by displayOrder
     */
    public List<Playlist> findApprovedByDisplayOrderAsc() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", STATUS_APPROVED)
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Find approved playlists ordered by displayOrder ascending with limit.
     *
     * <p>Requires Firestore composite index: status (ASC) + displayOrder (ASC)</p>
     *
     * @param limit Maximum number of results
     * @return List of approved playlists ordered by displayOrder
     */
    public List<Playlist> findApprovedByDisplayOrderAsc(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", STATUS_APPROVED)
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Batch update displayOrder for multiple playlists.
     * Used by admin reordering feature.
     *
     * @param orderUpdates Map of playlist ID to new displayOrder value
     * @throws IllegalArgumentException if orderUpdates contains more than 500 entries (Firestore limit)
     */
    public void batchUpdateDisplayOrder(java.util.Map<String, Integer> orderUpdates) throws ExecutionException, InterruptedException, TimeoutException {
        if (orderUpdates == null || orderUpdates.isEmpty()) {
            return;
        }

        if (orderUpdates.size() > 500) {
            throw new IllegalArgumentException("Cannot update more than 500 playlists in a single batch. Received: " + orderUpdates.size());
        }

        // Validate all documents exist before updating (using batched read)
        List<DocumentReference> docRefs = new ArrayList<>();
        for (String playlistId : orderUpdates.keySet()) {
            docRefs.add(getCollection().document(playlistId));
        }
        
        List<com.google.cloud.firestore.DocumentSnapshot> snapshots = 
            firestore.getAll(docRefs.toArray(new DocumentReference[0]))
                .get(timeoutProperties.getRead(), TimeUnit.SECONDS);
        
        for (int i = 0; i < snapshots.size(); i++) {
            if (!snapshots.get(i).exists()) {
                throw new IllegalArgumentException("Playlist with ID '" + docRefs.get(i).getId() + "' does not exist");
            }
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
     * <p>Requires Firestore composite index: status (ASC) + lastValidatedAt (ASC)</p>
     *
     * @param limit Maximum number of results
     * @return List of approved playlists needing validation
     */
    public List<Playlist> findApprovedPlaylistsNeedingValidation(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", STATUS_APPROVED)
                .orderBy("lastValidatedAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class);
    }

    /**
     * Maximum number of playlists to return with exclusions to prevent quota exhaustion.
     */
    private static final int MAX_EXCLUSIONS_PLAYLISTS = 1000;

    /**
     * Find all playlists that have exclusions (excludedVideoCount > 0).
     * Uses the excludedVideoCount field for efficient inequality queries.
     *
     * <p><b>Legacy data:</b> Documents missing the excludedVideoCount field will not appear.
     * Use {@code backfill-exclusion-counts} profile to populate missing fields.
     *
     * @return List of playlists with at least one excluded video (max 1000)
     */
    public List<Playlist> findAllWithExclusions() throws ExecutionException, InterruptedException, TimeoutException {
        return findAllWithExclusions(MAX_EXCLUSIONS_PLAYLISTS);
    }

    /**
     * Find playlists with exclusions, with explicit limit.
     * Uses the excludedVideoCount field for efficient inequality queries.
     *
     * <p><b>Fallback:</b> Triggers on FAILED_PRECONDITION or errors containing "index"
     * (missing Firestore index). Empty results are legitimate and do NOT trigger fallback.
     *
     * <p><b>Legacy data:</b> Documents missing the excludedVideoCount field will not appear.
     * Use {@code backfill-exclusion-counts} profile to populate missing fields.
     *
     * @param limit Maximum number of results
     * @return List of playlists with at least one excluded video
     */
    public List<Playlist> findAllWithExclusions(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Playlist> result;

        try {
            // Primary query: use excludedVideoCount field (efficient, no full scan)
            ApiFuture<QuerySnapshot> query = getCollection()
                    .whereGreaterThan("excludedVideoCount", 0)
                    .orderBy("excludedVideoCount", Query.Direction.DESCENDING)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get();

            result = new ArrayList<>(query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Playlist.class));
        } catch (ExecutionException e) {
            // Check if the cause is a FAILED_PRECONDITION (missing index) or other query error
            String message = e.getMessage();
            if (message != null && (message.contains("FAILED_PRECONDITION") || message.contains("index"))) {
                log.warn("Playlist exclusions query failed due to missing index, falling back to legacy scan: {}", message);
                result = findAllWithExclusionsLegacyScan(limit);
            } else {
                // Re-throw other execution exceptions
                throw e;
            }
        }

        return result;
    }

    /**
     * Legacy fallback: scan playlists to find those with exclusions.
     * Only invoked when the primary query fails with FAILED_PRECONDITION (missing Firestore
     * composite index on excludedVideoCount). NOT invoked on empty results.
     * Uses batched scanning with a safety limit (max 5,000 docs) to prevent quota exhaustion.
     *
     * @param limit Maximum number of playlists to return
     */
    private List<Playlist> findAllWithExclusionsLegacyScan(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Playlist> result = new ArrayList<>();
        int batchSize = 500;
        int maxIterations = 10; // Safety limit: 10 * 500 = 5,000 max documents scanned
        int iterations = 0;
        QueryDocumentSnapshot lastDoc = null;

        while (iterations < maxIterations && result.size() < limit) {
            iterations++;
            Query query = getCollection()
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(batchSize);

            if (lastDoc != null) {
                query = query.startAfter(lastDoc);
            }

            QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
            List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

            if (docs.isEmpty()) {
                break;
            }

            for (QueryDocumentSnapshot doc : docs) {
                Playlist playlist = doc.toObject(Playlist.class);
                playlist.setId(doc.getId());
                List<String> excludedVideoIds = playlist.getExcludedVideoIds();
                if (excludedVideoIds != null && !excludedVideoIds.isEmpty()) {
                    result.add(playlist);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }

            if (docs.size() < batchSize) {
                break; // No more documents
            }

            lastDoc = docs.get(docs.size() - 1);
        }

        return result;
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
