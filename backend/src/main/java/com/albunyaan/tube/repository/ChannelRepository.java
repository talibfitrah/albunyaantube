package com.albunyaan.tube.repository;

import com.albunyaan.tube.model.Channel;
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
 * FIREBASE-MIGRATE-03: Channel Repository (Firestore)
 */
@Repository
public class ChannelRepository {

    private static final String COLLECTION_NAME = "channels";
    private final Firestore firestore;

    public ChannelRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public Channel save(Channel channel) throws ExecutionException, InterruptedException {
        channel.touch();

        if (channel.getId() == null) {
            DocumentReference docRef = getCollection().document();
            channel.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(channel.getId())
                .set(channel);

        result.get();
        return channel;
    }

    public Optional<Channel> findById(String id) throws ExecutionException, InterruptedException {
        DocumentReference docRef = getCollection().document(id);
        Channel channel = docRef.get().get().toObject(Channel.class);
        return Optional.ofNullable(channel);
    }

    public Optional<Channel> findByYoutubeId(String youtubeId) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("youtubeId", youtubeId)
                .limit(1)
                .get();

        List<Channel> channels = query.get().toObjects(Channel.class);
        return channels.isEmpty() ? Optional.empty() : Optional.of(channels.get(0));
    }

    public List<Channel> findByStatus(String status) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get().toObjects(Channel.class);
    }

    public List<Channel> findByCategoryId(String categoryId) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereArrayContains("categoryIds", categoryId)
                .whereEqualTo("status", "approved")
                .get();

        return query.get().toObjects(Channel.class);
    }

    public void deleteById(String id) throws ExecutionException, InterruptedException {
        ApiFuture<WriteResult> result = getCollection().document(id).delete();
        result.get();
    }
}
