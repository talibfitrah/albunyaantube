package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Plan D — weekly tombstone GC. Sunday 03:00 UTC. Bounded work per type.
 * Deletes `deleted=true AND updatedAt < now - 90d` from every user's
 * subcollection (via Firestore collectionGroup).
 */
@Component
public class TombstoneGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(TombstoneGcScheduler.class);
    private static final long RETENTION_DAYS = 90L;

    private final Firestore firestore;
    private final MeterRegistry meters;
    private final FirestoreTimeoutProperties timeouts;

    public TombstoneGcScheduler(Firestore firestore,
                                MeterRegistry meters,
                                FirestoreTimeoutProperties timeouts) {
        this.firestore = firestore;
        this.meters = meters;
        this.timeouts = timeouts;
    }

    @Scheduled(cron = "0 0 3 * * SUN", zone = "UTC")
    public void pruneTombstones() {
        Instant cutoffInst = Instant.now().minus(Duration.ofDays(RETENTION_DAYS));
        Timestamp cutoff = Timestamp.ofTimeSecondsAndNanos(cutoffInst.getEpochSecond(), cutoffInst.getNano());
        for (String type : List.of("subscriptions", "playlists", "favorites")) {
            int purged = purgeOne(type, cutoff);
            log.info("account.sync.tombstone.gc type={} purged={}", type, purged);
            meters.counter("account.sync.tombstone.gc.purged", "type", type).increment(purged);
        }
    }

    private int purgeOne(String type, Timestamp cutoff) {
        try {
            QuerySnapshot snap = firestore.collectionGroup(type)
                    .whereEqualTo("deleted", true)
                    .whereLessThan("updatedAt", cutoff)
                    .get().get(timeouts.getBulkQuery(), TimeUnit.SECONDS);
            int n = 0;
            for (QueryDocumentSnapshot d : snap.getDocuments()) {
                d.getReference().delete().get(timeouts.getWrite(), TimeUnit.SECONDS);
                n++;
            }
            return n;
        } catch (Exception e) {
            log.error("account.sync.tombstone.gc.error type={}", type, e);
            return 0;
        }
    }
}
