package com.albunyaan.tube.integration;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.SyncRepository;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google Play policy 13327111 — self-serve account deletion must actually
 * erase the user's data, not just flip a status flag. These tests run against
 * the Firestore emulator so the assertions are about what is REALLY in the
 * store after the purge, not about what a mocked writer was asked to write.
 *
 * <p>Firebase Auth is mocked (no Auth emulator required); the Firestore side
 * — tombstone, subcollections, download_events, personalGrants — is real.
 */
class SelfDeleteAccountIT extends BaseIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    AuditLogRepository auditRepo;

    @MockBean
    FirebaseAuth firebaseAuth;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{
                "categories", "channels", "playlists", "videos",
                "users", "audit_logs", "download_events"
        };
    }

    // ── The whole point: the data is actually gone ──────────────────────────

    @Test
    void deleteAccountPermanently_purgesLibrary_tombstonesUser_andHardDeletesAuth() throws Exception {
        String uid = seedUser("purge-me@t.com", "user");
        String otherUid = seedUser("bystander@t.com", "user");

        seedSyncRow(uid, SyncRepository.FAVORITES_COLL, "fav-1");
        seedSyncRow(uid, SyncRepository.SUBS_COLL, "sub-1");
        seedSyncRow(uid, SyncRepository.SUBS_COLL, "sub-2");
        seedSyncRow(uid, SyncRepository.PLAYLISTS_COLL, "pl-1");
        seedSyncRow(otherUid, SyncRepository.FAVORITES_COLL, "fav-other");

        seedDownloadEvent(uid, "vid-a");
        seedDownloadEvent(uid, "vid-b");
        seedDownloadEvent(otherUid, "vid-c");

        seedGrantedChannel("ch-1", List.of(uid, otherUid));
        seedGrantedVideo("vid-1", List.of(uid));

        authService.deleteAccountPermanently(uid);

        // (1) Tombstone: uid + role kept, every PII field cleared, status flipped.
        DocumentSnapshot doc = firestore.collection("users").document(uid).get().get();
        assertTrue(doc.exists(), "Tombstone doc must survive so submittedBy references don't dangle");
        assertEquals(uid, doc.getString("uid"));
        assertEquals("user", doc.getString("role"));
        assertEquals("deleted", doc.getString("status"));
        assertEquals("user-requested", doc.getString("deleteReason"));
        assertNotNull(doc.get("deletedAt"), "deletedAt must be stamped");
        assertNull(doc.get("email"), "email must be erased from the tombstone");
        assertNull(doc.get("displayName"), "displayName must be erased from the tombstone");
        assertNull(doc.get("dateOfBirth"), "dateOfBirth must be erased from the tombstone");
        assertNull(doc.get("phoneNumber"), "phoneNumber must be erased from the tombstone");
        // Behavioural metadata is personal data once it hangs off an identifier:
        // "when this person last opened the app" / "when they signed up" / "which
        // admin created them" all describe the human, so the tombstone must not
        // keep them either.
        assertNull(doc.get("lastLoginAt"), "lastLoginAt must be erased from the tombstone");
        assertNull(doc.get("profileCompletedAt"), "profileCompletedAt must be erased from the tombstone");
        assertNull(doc.get("createdBy"), "createdBy must be erased from the tombstone");

        // (2) Every sync subcollection is EMPTY — not tombstoned, gone.
        for (String coll : List.of(SyncRepository.SUBS_COLL,
                                   SyncRepository.PLAYLISTS_COLL,
                                   SyncRepository.FAVORITES_COLL)) {
            assertEquals(0, countSyncRows(uid, coll),
                    "users/" + uid + "/" + coll + " must be empty after deletion");
        }
        assertEquals(1, countSyncRows(otherUid, SyncRepository.FAVORITES_COLL),
                "Another user's library must be untouched");

        // (3) download_events rows for this uid are gone; others survive.
        assertEquals(0, countDownloadEvents(uid));
        assertEquals(1, countDownloadEvents(otherUid), "Another user's download rows must survive");

        // (4) personalGrants no longer names the uid; co-grantees survive.
        assertEquals(List.of(otherUid), grantsOf("channels", "ch-1"));
        assertEquals(List.of(), grantsOf("videos", "vid-1"));

        // (5) Firebase Auth record hard-deleted, tokens revoked first.
        InOrder order = inOrder(firebaseAuth);
        order.verify(firebaseAuth).revokeRefreshTokens(uid);
        order.verify(firebaseAuth).deleteUser(uid);

        // (6) Audit trail retained (disclosed on the privacy page).
        assertEquals(1, auditCount("USER_SELF_DELETED", uid));
    }

    @Test
    void deleteAccountPermanently_isIdempotent_onSecondCall() throws Exception {
        String uid = seedUser("twice@t.com", "user");

        authService.deleteAccountPermanently(uid);
        Timestamp firstDeletedAt = (Timestamp) firestore.collection("users")
                .document(uid).get().get().get("deletedAt");
        assertNotNull(firstDeletedAt);

        Thread.sleep(5);
        assertDoesNotThrow(() -> authService.deleteAccountPermanently(uid),
                "A retry against an already-deleted account must succeed, not throw");

        DocumentSnapshot doc = firestore.collection("users").document(uid).get().get();
        assertEquals(firstDeletedAt, doc.get("deletedAt"),
                "Idempotent retry must not restamp deletedAt");
        assertEquals(1, auditCount("USER_SELF_DELETED", uid),
                "Idempotent retry must not write a second audit row");
    }

    @Test
    void deleteAccountPermanently_lastActiveAdmin_throwsLastAdmin_andLeavesAccountIntact() throws Exception {
        String soloAdmin = seedUser("solo-admin@t.com", "admin");

        assertThrows(LastAdminException.class,
                () -> authService.deleteAccountPermanently(soloAdmin));

        User after = userRepository.findByUidUncached(soloAdmin).orElseThrow();
        assertEquals(UserStatus.ACTIVE, after.getStatusEnum(),
                "The refused delete must roll back — the last admin stays active");
        assertEquals("solo-admin@t.com", after.getEmail(), "PII must NOT be cleared on a refused delete");
        verify(firebaseAuth, never()).deleteUser(anyString());
    }

    @Test
    void deleteAccountPermanently_nonLastAdmin_isAllowed() throws Exception {
        String adminA = seedUser("admin-a@t.com", "admin");
        seedUser("admin-b@t.com", "admin");

        assertDoesNotThrow(() -> authService.deleteAccountPermanently(adminA));

        assertEquals("deleted",
                firestore.collection("users").document(adminA).get().get().getString("status"));
        verify(firebaseAuth).deleteUser(adminA);
    }

    // ── The public pages must be anonymously reachable ─────────────────────
    // LegalPagesControllerTest runs with addFilters=false, so only this test —
    // which goes through the REAL Spring Security chain — proves permitAll.

    @Test
    void publicLegalPages_areReachableAnonymously() throws Exception {
        for (String path : List.of("/delete-account", "/privacy", "/terms", "/licenses")) {
            mvc.perform(get(path))
                    .andExpect(status().isOk());
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private String seedUser(String email, String role) throws Exception {
        String uid = "sd-" + email.replace("@", "-at-").replace(".", "-");
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setDisplayName("Display " + email);
        u.setPhoneNumber("+31612345678");
        u.setDateOfBirth(Timestamp.ofTimeSecondsAndNanos(946684800L, 0)); // 2000-01-01
        u.setRole(role);
        u.setStatusEnum(UserStatus.ACTIVE);
        // Seeded non-null on purpose: without these the "must be erased"
        // assertions would pass vacuously against a never-populated field.
        u.setLastLoginAt(Timestamp.now());
        u.setProfileCompletedAt(Timestamp.now());
        u.setCreatedBy("seeding-admin-uid");
        userRepository.save(u);
        return uid;
    }

    private void seedSyncRow(String uid, String type, String id) throws Exception {
        firestore.collection("users").document(uid).collection(type).document(id)
                .set(Map.of("youtubeId", id, "deleted", false, "updatedAt", Timestamp.now()))
                .get();
    }

    private int countSyncRows(String uid, String type) throws Exception {
        return firestore.collection("users").document(uid).collection(type).get().get().size();
    }

    private void seedDownloadEvent(String uid, String videoId) throws Exception {
        firestore.collection("download_events")
                .add(Map.of("userId", uid, "videoId", videoId,
                            "eventType", "started", "timestamp", Timestamp.now()))
                .get();
    }

    private int countDownloadEvents(String uid) throws Exception {
        return firestore.collection("download_events").whereEqualTo("userId", uid).get().get().size();
    }

    private void seedGrantedChannel(String id, List<String> grants) throws Exception {
        firestore.collection("channels").document(id)
                .set(Map.of("name", "Ch " + id, "visibility", "PERSONAL", "personalGrants", grants))
                .get();
    }

    private void seedGrantedVideo(String id, List<String> grants) throws Exception {
        firestore.collection("videos").document(id)
                .set(Map.of("title", "Vid " + id, "visibility", "PERSONAL", "personalGrants", grants))
                .get();
    }

    @SuppressWarnings("unchecked")
    private List<String> grantsOf(String coll, String id) throws Exception {
        Object raw = firestore.collection(coll).document(id).get().get().get("personalGrants");
        return raw == null ? List.of() : (List<String>) raw;
    }

    private int auditCount(String action, String entityId) throws Exception {
        QuerySnapshot s = auditRepo.auditLogsCollection()
                .whereEqualTo("action", action)
                .whereEqualTo("entityId", entityId)
                .get().get();
        return s.size();
    }
}
