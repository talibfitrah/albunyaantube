package com.albunyaan.tube.repository;

import com.albunyaan.tube.model.User;
import com.google.api.core.ApiFuture;
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
 */
@Repository
public class UserRepository {

    private static final String COLLECTION_NAME = "users";
    private final Firestore firestore;

    public UserRepository(Firestore firestore) {
        this.firestore = firestore;
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

        result.get(5, TimeUnit.SECONDS);
        return user;
    }

    public Optional<User> findByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(uid);
        // Single document reads: use shorter timeout (2 seconds)
        User user = docRef.get().get(2, TimeUnit.SECONDS).toObject(User.class);
        return Optional.ofNullable(user);
    }

    public Optional<User> findByEmail(String email) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("email", email)
                .limit(1)
                .get();

        List<User> users = query.get(5, TimeUnit.SECONDS).toObjects(User.class);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    public List<User> findAll() throws ExecutionException, InterruptedException, TimeoutException {
        // Paginated query to avoid loading large datasets - limit to 100 users
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .get();

        return query.get(5, TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find all users with pagination
     * @param limit Maximum number of users to return (default 100)
     * @param offset Starting offset for pagination
     */
    public List<User> findAll(int limit, int offset) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .offset(offset)
                .get();

        return query.get(5, TimeUnit.SECONDS).toObjects(User.class);
    }

    public List<User> findByRole(String role) throws ExecutionException, InterruptedException, TimeoutException {
        // Paginated query to avoid loading large datasets - limit to 100 users
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("role", role)
                .orderBy("displayName", Query.Direction.ASCENDING)
                .limit(100)
                .get();

        return query.get(5, TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find users by role with pagination
     * @param role User role to filter by
     * @param limit Maximum number of users to return (default 100)
     * @param offset Starting offset for pagination
     */
    public List<User> findByRole(String role, int limit, int offset) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("role", role)
                .orderBy("displayName", Query.Direction.ASCENDING)
                .limit(limit)
                .offset(offset)
                .get();

        return query.get(5, TimeUnit.SECONDS).toObjects(User.class);
    }

    public void deleteByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<WriteResult> result = getCollection().document(uid).delete();
        result.get(5, TimeUnit.SECONDS);
    }

    public boolean existsByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(uid);
        // Single document reads: use shorter timeout (2 seconds)
        return docRef.get().get(2, TimeUnit.SECONDS).exists();
    }

    /**
     * Count all users
     */
    public long countAll() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection().get();
        return query.get(5, TimeUnit.SECONDS).size();
    }

    /**
     * Count users with ADMIN or MODERATOR role
     */
    public long countModerators() throws ExecutionException, InterruptedException, TimeoutException {
        // Note: Firestore doesn't support OR queries, so we need to count separately and sum
        ApiFuture<QuerySnapshot> adminQuery = getCollection()
                .whereEqualTo("role", "ADMIN")
                .get();
        ApiFuture<QuerySnapshot> modQuery = getCollection()
                .whereEqualTo("role", "MODERATOR")
                .get();

        long adminCount = adminQuery.get(5, TimeUnit.SECONDS).size();
        long modCount = modQuery.get(5, TimeUnit.SECONDS).size();

        return adminCount + modCount;
    }
}

