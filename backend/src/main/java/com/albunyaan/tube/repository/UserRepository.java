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

    public User save(User user) throws ExecutionException, InterruptedException {
        user.touch();

        // Use Firebase UID as document ID
        ApiFuture<WriteResult> result = getCollection()
                .document(user.getUid())
                .set(user);

        result.get();
        return user;
    }

    public Optional<User> findByUid(String uid) throws ExecutionException, InterruptedException {
        DocumentReference docRef = getCollection().document(uid);
        User user = docRef.get().get().toObject(User.class);
        return Optional.ofNullable(user);
    }

    public Optional<User> findByEmail(String email) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("email", email)
                .limit(1)
                .get();

        List<User> users = query.get().toObjects(User.class);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    public List<User> findAll() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(User.class);
    }

    public List<User> findByRole(String role) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("role", role)
                .orderBy("displayName", Query.Direction.ASCENDING)
                .get();

        return query.get().toObjects(User.class);
    }

    public void deleteByUid(String uid) throws ExecutionException, InterruptedException {
        ApiFuture<WriteResult> result = getCollection().document(uid).delete();
        result.get();
    }

    public boolean existsByUid(String uid) throws ExecutionException, InterruptedException {
        DocumentReference docRef = getCollection().document(uid);
        return docRef.get().get().exists();
    }
}

