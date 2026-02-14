package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
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
 * FIREBASE-MIGRATE-03: Channel Repository (Firestore)
 *
 * All Firestore operations use configurable, operation-specific timeouts to prevent
 * indefinite blocking and thread pool exhaustion in case of network issues or Firestore unavailability.
 */
@Repository
public class ChannelRepository {

    private static final Logger log = LoggerFactory.getLogger(ChannelRepository.class);

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

    /**
     * Find channels by status, ordered by creation date descending (newest first).
     * This method is used for UI display where newest items should appear first.
     *
     * For validation purposes, use findByStatusOrderByLastValidatedAtAsc() instead to prevent
     * validation starvation.
     */
    public List<Channel> findByStatus(String status) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find channels by status with limit, ordered by creation date descending (newest first).
     * This method is used for UI display where newest items should appear first.
     *
     * For validation purposes, use findByStatusOrderByLastValidatedAtAsc() instead to prevent
     * validation starvation.
     */
    public List<Channel> findByStatus(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find channels by status ordered by lastValidatedAt ascending (oldest validated first).
     * This prevents validation starvation by ensuring items that haven't been validated
     * recently are prioritized over recently validated items.
     *
     * Note: Requires Firestore composite index: status (ASC) + lastValidatedAt (ASC)
     * Firestore automatically places null lastValidatedAt values first when ordering ascending.
     *
     * @param status The approval status to filter by
     * @param limit Maximum number of results
     * @return List of channels ordered by lastValidatedAt ascending (nulls first)
     */
    public List<Channel> findByStatusOrderByLastValidatedAtAsc(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("lastValidatedAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find approved channels by category ID with deterministic ordering.
     * NOTE: This unbounded method is retained for backwards compatibility but
     * callers should prefer findByCategoryId(categoryId, limit) for quota safety.
     *
     * @param categoryId Category ID to filter by
     * @return List of approved channels in the category, ordered by createdAt descending
     */
    public List<Channel> findByCategoryId(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find channels by category ID with limit (bounded query).
     * This prevents quota exhaustion for large categories.
     *
     * @param categoryId Category ID to filter by
     * @param limit Maximum number of results
     * @return List of approved channels in the category
     */
    public List<Channel> findByCategoryId(String categoryId, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find channels by category ID regardless of status (for status=all).
     * This allows admin Content Library to show pending/rejected items when filtering by category.
     *
     * @param categoryId Category ID to filter by
     * @param limit Maximum number of results
     * @return List of channels in the category (any status)
     */
    public List<Channel> findByCategoryIdAllStatus(String categoryId, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find channels by category ID and specific status.
     * This allows admin Content Library to filter by both category and status.
     *
     * @param categoryId Category ID to filter by
     * @param status Status to filter by (APPROVED, PENDING, REJECTED)
     * @param limit Maximum number of results
     * @return List of channels matching both category and status
     */
    public List<Channel> findByCategoryIdAndStatus(String categoryId, String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
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

    /**
     * Find all channels ordered by createdAt ascending (oldest first) with limit.
     * Used by admin Content Library when sort=oldest is requested.
     *
     * @param limit Maximum number of results
     * @return List of channels ordered oldest-first
     */
    public List<Channel> findAllOrderByCreatedAtAsc(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find channels by status ordered by createdAt ascending (oldest first) with limit.
     * Used by admin Content Library when sort=oldest is requested with status filter.
     *
     * @param status Status to filter by
     * @param limit Maximum number of results
     * @return List of channels ordered oldest-first
     */
    public List<Channel> findByStatusOrderByCreatedAtAsc(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.ASCENDING)
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
     * Search channels by name prefix with limit (bounded query).
     * This prevents quota exhaustion for broad search queries.
     *
     * @param query Search prefix
     * @param limit Maximum number of results
     * @return List of matching channels
     */
    public List<Channel> searchByName(String query, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("name")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .limit(limit)
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Search channels by normalized nameLower field for case-insensitive prefix matching.
     * This is the preferred search method as it supports case-insensitive queries.
     *
     * @param queryLower Lowercase search prefix
     * @param limit Maximum number of results
     * @return List of matching channels
     */
    public List<Channel> searchByNameLower(String queryLower, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("nameLower")
                .startAt(queryLower)
                .endAt(queryLower + "\uf8ff")
                .limit(limit)
                .get();

        return querySnapshot.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Search channels by keyword using array-contains on keywordsLower.
     * Finds channels where any keyword exactly matches the query (case-insensitive).
     * Complements prefix-based name search for improved recall.
     *
     * @param keywordLower Lowercase keyword to search for
     * @param limit Maximum number of results
     * @return List of matching channels
     */
    public List<Channel> searchByKeyword(String keywordLower, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .whereArrayContains("keywordsLower", keywordLower)
                .limit(limit)
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

    // ==================== Display Order Methods ====================

    /**
     * Find approved channels ordered by displayOrder ascending (custom admin order).
     * Channels with null displayOrder are placed first (Firestore default for ASCENDING).
     *
     * @return List of approved channels ordered by displayOrder
     */
    public List<Channel> findApprovedByDisplayOrderAsc() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find approved channels ordered by displayOrder ascending with limit.
     *
     * @param limit Maximum number of results
     * @return List of approved channels ordered by displayOrder
     */
    public List<Channel> findApprovedByDisplayOrderAsc(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Batch update displayOrder for multiple channels.
     * Used by admin reordering feature.
     *
     * @param orderUpdates Map of channel ID to new displayOrder value
     * @throws IllegalArgumentException if orderUpdates contains more than 500 entries (Firestore limit)
     */
    public void batchUpdateDisplayOrder(java.util.Map<String, Integer> orderUpdates) throws ExecutionException, InterruptedException, TimeoutException {
        if (orderUpdates == null || orderUpdates.isEmpty()) {
            return;
        }

        if (orderUpdates.size() > 500) {
            throw new IllegalArgumentException("Cannot update more than 500 channels in a single batch. Received: " + orderUpdates.size());
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
     * Find channels by validation status.
     *
     * @param status ValidationStatus to filter by
     * @return List of channels with the specified validation status
     */
    public List<Channel> findByValidationStatus(ValidationStatus status) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Find channels by validation status with limit.
     *
     * @param status ValidationStatus to filter by
     * @param limit Maximum number of results
     * @return List of channels with the specified validation status
     */
    public List<Channel> findByValidationStatus(ValidationStatus status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Count channels by validation status using server-side aggregation.
     *
     * @param status ValidationStatus to count
     * @return Count of channels with the specified validation status
     */
    public long countByValidationStatus(ValidationStatus status) throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection()
                .whereEqualTo("validationStatus", status.name())
                .count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Find approved channels that need validation (not yet validated or validated more than 24 hours ago).
     * Note: Firestore doesn't support OR queries on different fields, so we fetch approved channels
     * and filter in memory. For large datasets, consider using a scheduled job approach.
     *
     * @param limit Maximum number of results
     * @return List of approved channels needing validation
     */
    public List<Channel> findApprovedChannelsNeedingValidation(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        // Get approved channels, filter those needing validation in service layer
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("lastValidatedAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class);
    }

    /**
     * Maximum number of channels to return with exclusions to prevent quota exhaustion.
     */
    private static final int MAX_EXCLUSIONS_CHANNELS = 500;

    /**
     * Find all channels that have exclusions (totalExcludedCount > 0).
     * Uses the totalExcludedCount field for efficient queries.
     *
     * <p><b>Fallback behavior:</b> Falls back to legacy scan on FAILED_PRECONDITION
     * or errors containing "index" (missing Firestore index). Empty results are
     * legitimate and do NOT trigger fallback.
     *
     * <p><b>Legacy data requirement:</b> Documents created before the totalExcludedCount
     * field was introduced will be invisible to this query. Run the backfill utility
     * ({@code --spring.profiles.active=backfill-exclusion-counts}) to populate missing
     * count fields before relying on this query for completeness.
     *
     * <p>Requires Firestore composite index on excludedItems.totalExcludedCount.
     * Limited to MAX_EXCLUSIONS_CHANNELS to prevent quota exhaustion.
     *
     * @return List of channels with at least one exclusion (max 500)
     */
    public List<Channel> findAllWithExclusions() throws ExecutionException, InterruptedException, TimeoutException {
        return findAllWithExclusions(MAX_EXCLUSIONS_CHANNELS);
    }

    /**
     * Find channels with exclusions, with explicit limit.
     * Uses the totalExcludedCount field for efficient inequality queries.
     *
     * <p><b>Fallback:</b> Triggers on FAILED_PRECONDITION or errors containing "index"
     * (missing Firestore index). Empty results are legitimate and do NOT trigger fallback.
     *
     * <p><b>Legacy data:</b> Documents missing the totalExcludedCount field will not appear.
     * Use {@code backfill-exclusion-counts} profile to populate missing fields.
     *
     * @param limit Maximum number of results
     * @return List of channels with at least one exclusion
     */
    public List<Channel> findAllWithExclusions(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Channel> result;

        try {
            // Primary query: use totalExcludedCount field (efficient, no full scan)
            ApiFuture<QuerySnapshot> query = getCollection()
                    .whereGreaterThan("excludedItems.totalExcludedCount", 0)
                    .orderBy("excludedItems.totalExcludedCount", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get();

            result = new ArrayList<>(query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(Channel.class));
        } catch (ExecutionException e) {
            // Only fallback on FAILED_PRECONDITION (missing index), not on empty results
            String message = e.getMessage();
            if (message != null && (message.contains("FAILED_PRECONDITION") || message.contains("index"))) {
                log.warn("Channel exclusions query failed due to missing index, falling back to legacy scan: {}", message);
                result = findAllWithExclusionsLegacyScan(limit);
            } else {
                // Re-throw other execution exceptions
                throw e;
            }
        }

        return result;
    }

    /**
     * Legacy fallback: scan channels to find those with exclusions.
     * Only invoked when the primary query fails with FAILED_PRECONDITION (missing Firestore
     * composite index on excludedItems.totalExcludedCount). NOT invoked on empty results.
     * Uses batched scanning with a safety limit (max 5,000 docs) to prevent quota exhaustion.
     *
     * @param limit Maximum number of channels to return
     */
    private List<Channel> findAllWithExclusionsLegacyScan(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Channel> result = new ArrayList<>();
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
                Channel channel = doc.toObject(Channel.class);
                channel.setId(doc.getId());
                Channel.ExcludedItems excludedItems = channel.getExcludedItems();
                if (excludedItems != null && excludedItems.getTotalExcludedCount() > 0) {
                    result.add(channel);
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
     * Count channels by category using server-side aggregation.
     * This is efficient as it doesn't load any documents.
     *
     * @param categoryId Category ID to count
     * @return Count of channels in the category
     */
    public long countByCategoryId(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Count channels by category and status using server-side aggregation.
     * This is efficient as it doesn't load any documents.
     *
     * @param categoryId Category ID to filter by
     * @param status Status to filter by (APPROVED, PENDING, REJECTED)
     * @return Count of channels matching both category and status
     */
    public long countByCategoryIdAndStatus(String categoryId, String status) throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", status)
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
