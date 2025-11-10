package com.albunyaan.tube.repository;

import com.albunyaan.tube.model.Video;
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
 * FIREBASE-MIGRATE-03: Video Repository (Firestore)
 */
@Repository
public class VideoRepository {

    private static final String COLLECTION_NAME = "videos";
    private final Firestore firestore;

    public VideoRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public Video save(Video video) throws ExecutionException, InterruptedException {
        video.touch();

        if (video.getId() == null) {
            DocumentReference docRef = getCollection().document();
            video.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(video.getId())
                .set(video);

        result.get();
        return video;
    }

    public Optional<Video> findById(String id) throws ExecutionException, InterruptedException {
        DocumentReference docRef = getCollection().document(id);
        Video video = docRef.get().get().toObject(Video.class);
        return Optional.ofNullable(video);
    }

    public Optional<Video> findByYoutubeId(String youtubeId) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("youtubeId", youtubeId)
                .limit(1)
                .get();

        List<Video> videos = query.get().toObjects(Video.class);
        return videos.isEmpty() ? Optional.empty() : Optional.of(videos.get(0));
    }

    public List<Video> findByStatus(String status) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> findByCategoryId(String categoryId) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> findByCategoryIdWithLimit(String categoryId, int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> findByChannelIdAndStatus(String channelId, String status, int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("channelId", channelId)
                .whereEqualTo("status", status)
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> findByCategoryOrderByUploadedAtDesc(String category) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> findByCategoryOrderByUploadedAtDesc(String category, int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", category)
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> findAllByOrderByUploadedAtDesc() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> findAllByOrderByUploadedAtDesc(int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", "APPROVED")
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(Video.class);
    }

    public List<Video> searchByTitle(String query) throws ExecutionException, InterruptedException {
        // Firestore doesn't support full-text search, so we'll use prefix matching
        // For production, consider using Algolia or Elasticsearch
        // Note: Filtering by status removed from query to avoid composite index requirement
        // Status filtering done in PublicContentService layer
        ApiFuture<QuerySnapshot> querySnapshot = getCollection()
                .orderBy("title")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .get();

        return querySnapshot.get().toObjects(Video.class);
    }

    public void deleteById(String id) throws ExecutionException, InterruptedException {
        ApiFuture<WriteResult> result = getCollection().document(id).delete();
        result.get();
    }

    public List<Video> findAll() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();
        return query.get().toObjects(Video.class);
    }
}

