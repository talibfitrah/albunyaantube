package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Playlist;
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
}

