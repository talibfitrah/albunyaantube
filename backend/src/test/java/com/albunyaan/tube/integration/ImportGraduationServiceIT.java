package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.service.ImportGraduationService;
import com.google.cloud.firestore.DocumentSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BACKEND-IMPORT-08: Integration test for ImportGraduationService.
 *
 * Verifies that onApproved flips all AWAITING rows for a given youtubeId to APPROVED,
 * and onRejected tombstones them (deleted=true), while leaving unrelated APPROVED docs
 * (the control) untouched. Uses the Firestore emulator at localhost:8090.
 */
class ImportGraduationServiceIT extends BaseIntegrationTest {

    @Autowired
    private ImportGraduationService graduationService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Seed a subscriptions doc for the given user and youtubeId with the given status. */
    private String seedSubscription(String uid, String youtubeId, String approvalStatus)
            throws Exception {
        Map<String, Object> data = Map.of(
                "youtubeId", youtubeId,
                "approvalStatus", approvalStatus,
                "deleted", false,
                "updatedAt", 1000L
        );
        var ref = firestore
                .collection("users").document(uid)
                .collection("subscriptions").document();
        ref.set(data).get(5, TimeUnit.SECONDS);
        return ref.getId();
    }

    /** Read a subscriptions doc by uid + docId. */
    private DocumentSnapshot readSubscription(String uid, String docId) throws Exception {
        return firestore
                .collection("users").document(uid)
                .collection("subscriptions").document(docId)
                .get().get(5, TimeUnit.SECONDS);
    }

    // ── Test 1: onApproved flips all AWAITING rows; control untouched ─────────

    @Test
    void onApproved_flipsAwaitingDocsAndLeavesControlUntouched() throws Exception {
        // Seed two users, each with AWAITING for UC9
        String docA = seedSubscription("user-a", "UC9", "AWAITING");
        String docB = seedSubscription("user-b", "UC9", "AWAITING");
        // Control: user-a with a different id, already APPROVED
        String controlDoc = seedSubscription("user-a", "UC-OTHER", "APPROVED");

        long before = System.currentTimeMillis();

        graduationService.onApproved(YouTubeContentType.CHANNEL, "UC9");

        // Both AWAITING docs must now be APPROVED with bumped updatedAt
        DocumentSnapshot snapA = readSubscription("user-a", docA);
        assertThat(snapA.getString("approvalStatus")).isEqualTo("APPROVED");
        // F2: fan-out now writes a Firestore Timestamp (not numeric millis) so the sync
        // delta-pull can read it via getTimestamp() and page on it.
        assertThat(snapA.getTimestamp("updatedAt")).isNotNull();
        assertThat(snapA.getTimestamp("updatedAt").toDate().getTime()).isGreaterThanOrEqualTo(before);

        DocumentSnapshot snapB = readSubscription("user-b", docB);
        assertThat(snapB.getString("approvalStatus")).isEqualTo("APPROVED");
        assertThat(snapB.getTimestamp("updatedAt")).isNotNull();
        assertThat(snapB.getTimestamp("updatedAt").toDate().getTime()).isGreaterThanOrEqualTo(before);

        // Control doc: approvalStatus still APPROVED, updatedAt still 1000L (not bumped)
        DocumentSnapshot ctrl = readSubscription("user-a", controlDoc);
        assertThat(ctrl.getString("approvalStatus")).isEqualTo("APPROVED");
        assertThat(ctrl.getLong("updatedAt")).isEqualTo(1000L);
    }

    // ── Test 2: onRejected tombstones AWAITING rows; control untouched ────────

    @Test
    void onRejected_tombstonesAwaitingDocsAndLeavesControlUntouched() throws Exception {
        // Seed fresh AWAITING docs for two users
        String docA = seedSubscription("user-a", "UC9", "AWAITING");
        String docB = seedSubscription("user-b", "UC9", "AWAITING");
        // Control: APPROVED for a different id
        String controlDoc = seedSubscription("user-a", "UC-OTHER", "APPROVED");

        long before = System.currentTimeMillis();

        graduationService.onRejected(YouTubeContentType.CHANNEL, "UC9");

        // Both AWAITING docs must now be tombstoned
        DocumentSnapshot snapA = readSubscription("user-a", docA);
        assertThat(snapA.getBoolean("deleted")).isTrue();
        // F2: fan-out now writes a Firestore Timestamp (not numeric millis) so the sync
        // delta-pull can read it via getTimestamp() and page on it.
        assertThat(snapA.getTimestamp("updatedAt")).isNotNull();
        assertThat(snapA.getTimestamp("updatedAt").toDate().getTime()).isGreaterThanOrEqualTo(before);

        DocumentSnapshot snapB = readSubscription("user-b", docB);
        assertThat(snapB.getBoolean("deleted")).isTrue();
        assertThat(snapB.getTimestamp("updatedAt")).isNotNull();
        assertThat(snapB.getTimestamp("updatedAt").toDate().getTime()).isGreaterThanOrEqualTo(before);

        // Control doc: untouched
        DocumentSnapshot ctrl = readSubscription("user-a", controlDoc);
        assertThat(ctrl.getBoolean("deleted")).isFalse();
        assertThat(ctrl.getLong("updatedAt")).isEqualTo(1000L);
    }

    // ── Test 3: rejecting reaches rows that had already been approved ─────────

    @Test
    void onRejected_alsoClearsRowsThatHadAlreadyBeenApproved() throws Exception {
        // Content can be rejected after it was approved. A row that had already graduated would
        // otherwise sit in that person's list forever — the admin pulled the content, and their
        // app would go on offering it.
        String approvedDoc = seedSubscription("user-a", "UC-LATER", "APPROVED");
        String awaitingDoc = seedSubscription("user-b", "UC-LATER", "AWAITING");
        String controlDoc = seedSubscription("user-a", "UC-UNRELATED", "APPROVED");

        graduationService.onRejected(YouTubeContentType.CHANNEL, "UC-LATER");

        assertThat(readSubscription("user-a", approvedDoc).getBoolean("deleted")).isTrue();
        assertThat(readSubscription("user-b", awaitingDoc).getBoolean("deleted")).isTrue();
        // Still scoped to the rejected content only.
        assertThat(readSubscription("user-a", controlDoc).getBoolean("deleted")).isFalse();
    }

    @Test
    void onApproved_leavesAlreadyApprovedRowsAlone() throws Exception {
        // Approving must stay narrow: the personal-grant path derives its grantee list from
        // exactly the rows it flips, so reaching further would grant content to people it was
        // never approved for.
        String approvedDoc = seedSubscription("user-a", "UC-NARROW", "APPROVED");

        graduationService.onApproved(YouTubeContentType.CHANNEL, "UC-NARROW");

        assertThat(readSubscription("user-a", approvedDoc).getLong("updatedAt")).isEqualTo(1000L);
    }
}
