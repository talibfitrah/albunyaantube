package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.User;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.AggregateQuery;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
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
 * FIREBASE-MIGRATE-03: User Repository (Firestore)
 *
 * All Firestore operations use configurable, operation-specific timeouts to prevent
 * indefinite blocking and thread pool exhaustion in case of network issues or Firestore unavailability.
 */
@Repository
public class UserRepository {

    private static final String COLLECTION_NAME = "users";
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public UserRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public User save(User user) throws ExecutionException, InterruptedException, TimeoutException {
        user.touch();

        // Use Firebase UID as document ID
        ApiFuture<WriteResult> result = getCollection()
                .document(user.getUid())
                .set(user);

        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return user;
    }

    public Optional<User> findByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(uid);
        // Single document reads: use shorter timeout (2 seconds)
        User user = docRef.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObject(User.class);
        return Optional.ofNullable(user);
    }

    public Optional<User> findByEmail(String email) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("email", email)
                .limit(1)
                .get();

        List<User> users = query.get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObjects(User.class);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    /**
     * Find all users (returns complete result set).
     * For paginated queries, use {@link #findAll(int, int)} instead.
     */
    public List<User> findAll() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find all users with pagination (paginated query).
     * @param limit Maximum number of users to return
     * @param offset Starting offset for pagination
     */
    public List<User> findAll(int limit, int offset) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .offset(offset)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find users by role (returns complete result set).
     * For paginated queries, use {@link #findByRole(String, int, int)} instead.
     * @param role User role to filter by
     */
    public List<User> findByRole(String role) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("role", role)
                .orderBy("displayName", Query.Direction.ASCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find users by role with pagination (paginated query).
     * @param role User role to filter by
     * @param limit Maximum number of users to return
     * @param offset Starting offset for pagination
     */
    public List<User> findByRole(String role, int limit, int offset) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("role", role)
                .orderBy("displayName", Query.Direction.ASCENDING)
                .limit(limit)
                .offset(offset)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    public void deleteByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<WriteResult> result = getCollection().document(uid).delete();
        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    public boolean existsByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(uid);
        // Single document reads: use shorter timeout (2 seconds)
        return docRef.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS).exists();
    }

    /**
     * Count all users using server-side aggregation
     */
    public long countAll() throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection().count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Count users with ADMIN or MODERATOR role using server-side aggregation
     */
    public long countModerators() throws ExecutionException, InterruptedException, TimeoutException {
        // Note: Firestore doesn't support OR queries, so we need to count separately and sum
        AggregateQuery adminCountQuery = getCollection()
                .whereEqualTo("role", "ADMIN")
                .count();
        AggregateQuery modCountQuery = getCollection()
                .whereEqualTo("role", "MODERATOR")
                .count();

        long adminCount = adminCountQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();
        long modCount = modCountQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();

        return adminCount + modCount;
    }
}

