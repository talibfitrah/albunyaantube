package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Repository for per-category content sort order.
 *
 * Collection: category_content_order
 * Composite index required: categoryId ASC, position ASC
 */
@Repository
public class CategoryContentOrderRepository {

    private static final Logger log = LoggerFactory.getLogger(CategoryContentOrderRepository.class);
    private static final String COLLECTION_NAME = "category_content_order";
    private static final int FIRESTORE_BATCH_LIMIT = 500;

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public CategoryContentOrderRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    /**
     * Find all order entries for a category, sorted by position ASC.
     */
    public List<CategoryContentOrder> findByCategoryIdOrderByPosition(String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("categoryId", categoryId)
                .orderBy("position", Query.Direction.ASCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS)
                .toObjects(CategoryContentOrder.class);
    }

    /**
     * Find a single entry by its deterministic ID.
     */
    public Optional<CategoryContentOrder> findById(String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentSnapshot doc = getCollection().document(id)
                .get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
        return doc.exists() ? Optional.ofNullable(doc.toObject(CategoryContentOrder.class)) : Optional.empty();
    }

    /**
     * Save a single entry (create or update).
     */
    public CategoryContentOrder save(CategoryContentOrder order)
            throws ExecutionException, InterruptedException, TimeoutException {
        order.touch();
        if (order.getId() == null) {
            order.setId(CategoryContentOrder.generateId(
                    order.getCategoryId(), order.getContentType(), order.getContentId()));
        }
        getCollection().document(order.getId()).set(order)
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return order;
    }

    /**
     * Batch save multiple entries.
     * Each chunk of up to 500 items is committed atomically, but chunks are independent.
     * If the total exceeds 500, a failure in a later chunk leaves earlier chunks committed.
     */
    public void batchSave(List<CategoryContentOrder> orders)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (orders.isEmpty()) return;

        for (int i = 0; i < orders.size(); i += FIRESTORE_BATCH_LIMIT) {
            int end = Math.min(i + FIRESTORE_BATCH_LIMIT, orders.size());
            List<CategoryContentOrder> chunk = orders.subList(i, end);

            WriteBatch batch = firestore.batch();
            for (CategoryContentOrder order : chunk) {
                order.touch();
                if (order.getId() == null) {
                    order.setId(CategoryContentOrder.generateId(
                            order.getCategoryId(), order.getContentType(), order.getContentId()));
                }
                batch.set(getCollection().document(order.getId()), order);
            }
            batch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            log.debug("Batch saved {} category_content_order entries (chunk {}/{})",
                    chunk.size(), (i / FIRESTORE_BATCH_LIMIT) + 1,
                    (int) Math.ceil((double) orders.size() / FIRESTORE_BATCH_LIMIT));
        }
    }

    /**
     * Delete a single entry by ID.
     */
    public void deleteById(String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        getCollection().document(id).delete()
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    /**
     * Delete all entries for a category (used when a category is deleted).
     */
    public void deleteByCategoryId(String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<CategoryContentOrder> entries = findByCategoryIdOrderByPosition(categoryId);
        if (entries.isEmpty()) return;

        batchDelete(entries);
        log.info("Deleted {} sort order entries for category {}", entries.size(), categoryId);
    }

    /**
     * Find all entries for a specific content item across all categories.
     * Used to identify affected categories before deletion for renumbering.
     */
    public List<CategoryContentOrder> findByContentIdAndType(String contentId, String contentType)
            throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("contentId", contentId)
                .whereEqualTo("contentType", contentType)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS)
                .toObjects(CategoryContentOrder.class);
    }

    /**
     * Delete all entries for a specific content item across all categories.
     * Used when content is deleted entirely.
     */
    public void deleteByContentIdAndType(String contentId, String contentType)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<CategoryContentOrder> entries = findByContentIdAndType(contentId, contentType);

        if (entries.isEmpty()) return;

        batchDelete(entries);
        log.info("Deleted {} sort order entries for {} {}", entries.size(), contentType, contentId);
    }

    /**
     * Count entries in a category.
     */
    public long countByCategoryId(String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuerySnapshot snapshot = getCollection()
                .whereEqualTo("categoryId", categoryId)
                .count()
                .get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Count entries grouped by categoryId in a single query.
     * Fetches all entries and counts locally to avoid N+1 individual count queries.
     *
     * WARNING: This loads the entire collection into memory. Acceptable for small to moderate
     * collections but may need a bounded approach if the collection grows very large.
     */
    public Map<String, Long> countAllGroupedByCategoryId()
            throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection().get();
        List<CategoryContentOrder> all = query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS)
                .toObjects(CategoryContentOrder.class);

        Map<String, Long> counts = new HashMap<>();
        for (CategoryContentOrder entry : all) {
            counts.merge(entry.getCategoryId(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Batch delete a list of entries, respecting Firestore batch limits.
     */
    private void batchDelete(List<CategoryContentOrder> entries)
            throws ExecutionException, InterruptedException, TimeoutException {
        for (int i = 0; i < entries.size(); i += FIRESTORE_BATCH_LIMIT) {
            int end = Math.min(i + FIRESTORE_BATCH_LIMIT, entries.size());
            List<CategoryContentOrder> chunk = entries.subList(i, end);

            WriteBatch batch = firestore.batch();
            for (CategoryContentOrder entry : chunk) {
                batch.delete(getCollection().document(entry.getId()));
            }
            batch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        }
    }
}
