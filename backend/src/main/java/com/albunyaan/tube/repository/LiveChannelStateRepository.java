package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.LiveChannelState;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Spec §3: live_channel_state/{UC id}. At most one write per polled channel per cycle (≤ 576/day for 4 channels). */
@Repository
public class LiveChannelStateRepository {

    static final String COLLECTION = "live_channel_state";

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeouts;

    public LiveChannelStateRepository(Firestore firestore, FirestoreTimeoutProperties timeouts) {
        this.firestore = firestore;
        this.timeouts = timeouts;
    }

    public void save(LiveChannelState state) throws ExecutionException, InterruptedException, TimeoutException {
        state.setUpdatedAt(System.currentTimeMillis());
        firestore.collection(COLLECTION).document(state.getChannelId()).set(state)
                .get(timeouts.getWrite(), TimeUnit.SECONDS);
    }

    public List<LiveChannelState> findAll() throws ExecutionException, InterruptedException, TimeoutException {
        return firestore.collection(COLLECTION).get().get(timeouts.getBulkQuery(), TimeUnit.SECONDS)
                .toObjects(LiveChannelState.class);
    }
}
