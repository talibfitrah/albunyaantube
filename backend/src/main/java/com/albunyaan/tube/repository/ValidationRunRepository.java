package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.ValidationRun;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Validation Run Repository (Firestore)
 *
 * All Firestore operations use configurable, operation-specific timeouts to prevent
 * indefinite blocking and thread pool exhaustion in case of network issues or Firestore unavailability.
 */
@Repository
public class ValidationRunRepository {

    private static final String COLLECTION_NAME = "validation_runs";
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public ValidationRunRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public ValidationRun save(ValidationRun validationRun) throws ExecutionException, InterruptedException, TimeoutException {
        if (validationRun.getId() == null) {
            DocumentReference docRef = getCollection().document();
            validationRun.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(validationRun.getId())
                .set(validationRun);

        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return validationRun;
    }

    public Optional<ValidationRun> findById(String id) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<DocumentSnapshot> future = getCollection()
                .document(id)
                .get();

        DocumentSnapshot document = future.get(timeoutProperties.getRead(), TimeUnit.SECONDS);
        if (document.exists()) {
            return Optional.of(document.toObject(ValidationRun.class));
        }
        return Optional.empty();
    }

    /**
     * Find all validation runs ordered by start time.
     * Note: Requires Firestore index on validation_runs collection with startedAt (DESCENDING).
     *
     * @param limit Maximum number of results (1-1000)
     * @return List of validation runs
     * @throws IllegalArgumentException if limit is out of bounds
     */
    public List<ValidationRun> findAll(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Limit must be between 1 and 1000");
        }

        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ValidationRun.class);
    }

    /**
     * Find validation runs by trigger type.
     * Note: Requires composite Firestore index: validation_runs collection with
     * triggerType (ASCENDING) and startedAt (DESCENDING).
     *
     * @param triggerType The trigger type to filter by
     * @param limit Maximum number of results (1-1000)
     * @return List of validation runs
     * @throws IllegalArgumentException if limit is out of bounds
     */
    public List<ValidationRun> findByTriggerType(String triggerType, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Limit must be between 1 and 1000");
        }

        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("triggerType", triggerType)
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ValidationRun.class);
    }

    /**
     * Find validation runs by status.
     * Note: Requires composite Firestore index: validation_runs collection with
     * status (ASCENDING) and startedAt (DESCENDING).
     *
     * @param status The status to filter by
     * @param limit Maximum number of results (1-1000)
     * @return List of validation runs
     * @throws IllegalArgumentException if limit is out of bounds
     */
    public List<ValidationRun> findByStatus(String status, int limit) throws ExecutionException, InterruptedException, TimeoutException {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Limit must be between 1 and 1000");
        }

        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ValidationRun.class);
    }

    /**
     * Find the most recent validation run
     */
    public Optional<ValidationRun> findLatest() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(1)
                .get();

        List<ValidationRun> runs = query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ValidationRun.class);
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(0));
    }

    /**
     * Find the most recent completed validation run.
     * Note: Requires composite Firestore index: validation_runs collection with
     * status (ASCENDING) and startedAt (DESCENDING).
     */
    public Optional<ValidationRun> findLatestCompleted() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", ValidationRun.STATUS_COMPLETED)
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(1)
                .get();

        List<ValidationRun> runs = query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ValidationRun.class);
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(0));
    }
}
