package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteBatch;
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
    /** Firestore WriteBatch hard limit (500 ops per batch). */
    private static final int WRITE_BATCH_SIZE = 500;
    /** Cap per scheduled run so one userbase doesn't pin the scheduler for minutes. */
    private static final int MAX_DELETES_PER_RUN = 20_000;

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
                    .limit(MAX_DELETES_PER_RUN)
                    .get().get(timeouts.getBulkQuery(), TimeUnit.SECONDS);
            int total = 0;
            WriteBatch batch = firestore.batch();
            int inBatch = 0;
            for (QueryDocumentSnapshot d : snap.getDocuments()) {
                // Re-assert deleted=true via updateTime precondition so a concurrent
                // upsert that resurrected the row between snapshot and commit aborts
                // this delete instead of clobbering the live data (cubic R5 P0 GC
                // race). The precondition matches the snapshot's update time; if
                // Firestore has seen a later write, the batch commit fails.
                batch.delete(d.getReference(), com.google.cloud.firestore.Precondition.updatedAt(d.getUpdateTime()));
                inBatch++;
                total++;
                if (inBatch == WRITE_BATCH_SIZE) {
                    try {
                        batch.commit().get(timeouts.getWrite(), TimeUnit.SECONDS);
                    } catch (Exception commitErr) {
                        // Partial-batch resurrection — log + continue with next batch
                        // rather than aborting the whole run.
                        log.warn("tombstone.gc.batch.commit.failed type={} batchSize={} err={}",
                                type, inBatch, commitErr.getMessage());
                    }
                    batch = firestore.batch();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                try {
                    batch.commit().get(timeouts.getWrite(), TimeUnit.SECONDS);
                } catch (Exception commitErr) {
                    log.warn("tombstone.gc.batch.commit.failed type={} batchSize={} err={}",
                            type, inBatch, commitErr.getMessage());
                }
            }
            return total;
        } catch (Exception e) {
            log.error("account.sync.tombstone.gc.error type={}", type, e);
            return 0;
        }
    }
}
