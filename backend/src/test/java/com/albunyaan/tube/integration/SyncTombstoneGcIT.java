package com.albunyaan.tube.integration;

import com.albunyaan.tube.scheduler.TombstoneGcScheduler;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan D T12 — TombstoneGcScheduler integration test against Firestore emulator.
 * Verifies that tombstones older than 90 days are purged; recent tombstones
 * and live rows survive.
 */
class SyncTombstoneGcIT extends BaseIntegrationTest {

    @Autowired
    private TombstoneGcScheduler scheduler;

    @Test
    void gcPurgesOnlyOldTombstones() throws Exception {
        String uid = "gc-uid";
        var subs = firestore.collection("users").document(uid).collection("subscriptions");

        // Old tombstone (91d ago) — should be purged
        Map<String, Object> old = new HashMap<>();
        old.put("deleted", true);
        old.put("updatedAt", Timestamp.ofTimeSecondsAndNanos(
                Instant.now().minusSeconds(91L * 86400L).getEpochSecond(), 0));
        subs.document("old-tomb").set(old).get();

        // Recent tombstone (10d ago) — should stay
        Map<String, Object> recent = new HashMap<>();
        recent.put("deleted", true);
        recent.put("updatedAt", Timestamp.ofTimeSecondsAndNanos(
                Instant.now().minusSeconds(10L * 86400L).getEpochSecond(), 0));
        subs.document("recent-tomb").set(recent).get();

        // Live row (deleted=false, old updatedAt) — should stay
        Map<String, Object> live = new HashMap<>();
        live.put("deleted", false);
        live.put("updatedAt", Timestamp.ofTimeSecondsAndNanos(
                Instant.now().minusSeconds(91L * 86400L).getEpochSecond(), 0));
        subs.document("live").set(live).get();

        scheduler.pruneTombstones();

        assertFalse(subs.document("old-tomb").get().get().exists(),
                "old tombstone (91d) must be purged");
        assertTrue(subs.document("recent-tomb").get().get().exists(),
                "recent tombstone (10d) must survive");
        assertTrue(subs.document("live").get().get().exists(),
                "live row must survive regardless of age");
    }
}
