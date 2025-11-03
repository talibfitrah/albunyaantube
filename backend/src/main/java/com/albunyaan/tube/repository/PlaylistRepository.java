package com.albunyaan.tube.repository;

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

/**
 * FIREBASE-MIGRATE-03: Playlist Repository (Firestore)
 */
@Repository
public class PlaylistRepository {

    private static final String COLLECTION_NAME = "playlists";
    private final Firestore firestore;

    public PlaylistRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public Playlist save(Playlist playlist) throws ExecutionException, InterruptedException {
        playlist.touch();

        if (playlist.getId() == null) {
            DocumentReference docRef = getCollection().document();
            playlist.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(playlist.getId())
                .set(playlist);

        result.get();
        return playlist;
    }

    public Optional<Playlist> findById(String id) throws ExecutionException, InterruptedException {
        DocumentReference docRef = getCollection().document(id);
        Playlist playlist = docRef.get().get().toObject(Playlist.class);
        return Optional.ofNullable(playlist);
    }

    public Optional<Playlist> findByYoutubeId(String youtubeId) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("youtubeId", youtubeId)
                .limit(1)
                .get();

        List<Playlist> playlists = query.get().toObjects(Playlist.class);
        return playlists.isEmpty() ? Optional.empty() : Optional.of(playlists.get(0));
    }

    public List<Playlist> findByStatus(String status) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(Playlist.class);
    }

    public List<Playlist> findByCategoryId(String categoryId) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .get();

        return query.get().toObjects(Playlist.class);
    }

    public List<Playlist> findByCategoryOrderByItemCountDesc(String category) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(Playlist.class);
    }

    public List<Playlist> findAllByOrderByItemCountDesc() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("itemCount", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(Playlist.class);
    }

    public List<Playlist> searchByTitle(String query) throws ExecutionException, InterruptedException {
        // Firestore doesn't support full-text search, so we'll use prefix matching
        // For production, consider using Algolia or Elasticsearch
        // Note: Filtering by status removed from query to avoid composite index requirement
        // Status filtering done in PublicContentService layer
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("title")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .get();

        return querySnapshot.get().toObjects(Playlist.class);
    }

    public void deleteById(String id) throws ExecutionException, InterruptedException {
        ApiFuture<WriteResult> result = getCollection().document(id).delete();
        result.get();
    }

    public List<Playlist> findAll() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();
        return query.get().toObjects(Playlist.class);
    }
}
