package com.albunyaan.tube.integration;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * BACKEND-AUTH-01: Integration tests for AuthService.softDeleteUser and recoverUser.
 *
 * Uses real Firestore emulator for transactional correctness.
 * Mocks FirebaseAuth (updateUser, revokeRefreshTokens) — no Auth emulator required.
 */
class AuthServiceSoftDeleteIntegrationTest extends BaseIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    AuditLogRepository auditRepo;

    @MockBean
    FirebaseAuth firebaseAuth;

    // ─── softDeleteUser ────────────────────────────────────────────────────────

    @Test
    void softDelete_marksDeletedAndWritesAudit() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("admin1@t.com", "admin");
        seedUser("admin2@t.com", "admin"); // ensure not-last-admin guard passes
        String targetUid = seedUser("moderator@t.com", "moderator");

        authService.softDeleteUser(targetUid, adminUid, "policy-violation");

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertEquals(UserStatus.DELETED, after.getStatusEnum());
        assertEquals(adminUid, after.getDeletedBy());
        assertEquals("policy-violation", after.getDeleteReason());

        QuerySnapshot audits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_SOFT_DELETED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, audits.size());
    }

    @Test
    void recover_clearsDeletionAndWritesAudit() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("admin3@t.com", "admin");
        seedUser("admin4@t.com", "admin"); // ensure not-last-admin guard passes
        String targetUid = seedUser("moderator2@t.com", "moderator");

        authService.softDeleteUser(targetUid, adminUid, "test");
        authService.recoverUser(targetUid, adminUid);

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isActive());
        assertNull(after.getDeletedBy());

        QuerySnapshot recoveryAudits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_RECOVERED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, recoveryAudits.size());
    }

    @Test
    void softDeleteLastAdmin_throws() throws Exception {
        stubFirebaseAuthMutations();

        String soloAdmin = seedUser("solo@t.com", "admin");
        // No other active admins — last-admin guard should fire

        assertThrows(LastAdminException.class,
                () -> authService.softDeleteUser(soloAdmin, soloAdmin, "test"),
                "Expected LastAdminException from last-admin guard");

        // Verify user is NOT deleted (transaction rolled back)
        User after = userRepository.findByUid(soloAdmin).orElseThrow();
        assertEquals(UserStatus.ACTIVE, after.getStatusEnum());
    }

    @Test
    void softDeleteUnknownUid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> authService.softDeleteUser("nonexistent-uid", "admin", "x"));
    }

    @Test
    void recoverNonDeletedUser_throws() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("admin5@t.com", "admin");
        String activeUid = seedUser("active@t.com", "moderator"); // ACTIVE, not deleted

        assertThrows(IllegalStateException.class,
                () -> authService.recoverUser(activeUid, adminUid),
                "Expected IllegalStateException when recovering a non-DELETED user");

        // User should remain ACTIVE
        User after = userRepository.findByUid(activeUid).orElseThrow();
        assertEquals(UserStatus.ACTIVE, after.getStatusEnum());
    }

    // ── F8 — softDelete refuses already-blocked targets ──────────────────────
    // Pre-F8 path: block → softDelete → recover ended up status="active" with
    // leftover block metadata. We now refuse the softDelete entirely so admins
    // must unblock first.

    @Test
    void softDeleteUser_throwsWhenTargetIsBlocked() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a-f8-1@t.com", "admin");
        seedUser("a-f8-2@t.com", "admin"); // ensure not-last-admin guard passes
        String targetUid = seedUser("victim-f8@t.com", "moderator");

        // First block the target.
        authService.blockUser(targetUid, adminUid, "test-block");
        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isBlocked(),
                "Pre-condition: target must be blocked");

        // Now attempt softDelete — must throw IllegalStateException.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> authService.softDeleteUser(targetUid, adminUid, "policy"),
                "softDeleteUser must throw when target is already BLOCKED");
        assertTrue(ex.getMessage().toLowerCase().contains("unblock"),
                "Error message must mention unblock requirement: " + ex.getMessage());

        // Verify target is still BLOCKED (not transitioned to DELETED).
        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertEquals(UserStatus.BLOCKED, after.getStatusEnum(),
                "Target must remain in BLOCKED status after rejected softDelete");
    }

    // ── F8 — recover clears block metadata (end-to-end) ──────────────────────
    // Belt-and-braces test of the model-level F8 fix. The softDelete-while-
    // blocked path is now blocked by the F8 guard above, so we exercise the
    // model-level clear via a recovered DELETED user that was never blocked,
    // and verify all delete-AND-block metadata is empty after recover.

    @Test
    void recoverUser_clearsBlockMetadata_endToEnd() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a-f8-3@t.com", "admin");
        seedUser("a-f8-4@t.com", "admin");
        String targetUid = seedUser("victim-f8-recover@t.com", "moderator");

        // softDelete then recover.
        authService.softDeleteUser(targetUid, adminUid, "test");
        authService.recoverUser(targetUid, adminUid);

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isActive(), "User must be ACTIVE after recover");
        // Delete metadata cleared.
        assertNull(after.getDeletedAt(), "deletedAt must be null after recover");
        assertNull(after.getDeletedBy(), "deletedBy must be null after recover");
        assertNull(after.getDeleteReason(), "deleteReason must be null after recover");
        // Block metadata also cleared (F8) — never set in this path, but verify
        // the recover never sets them accidentally either.
        assertNull(after.getBlockedAt(), "blockedAt must be null after recover");
        assertNull(after.getBlockedBy(), "blockedBy must be null after recover");
        assertNull(after.getBlockReason(), "blockReason must be null after recover");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Stub the FirebaseAuth mutation calls made by softDeleteUser and recoverUser.
     * These run outside the Firestore transaction (D9) and don't need emulator support.
     */
    private void stubFirebaseAuthMutations() throws Exception {
        when(firebaseAuth.updateUser(any())).thenReturn(null);
        doNothing().when(firebaseAuth).revokeRefreshTokens(any());
    }

    /**
     * Seed an ACTIVE user directly into the Firestore emulator and return their UID.
     */
    private String seedUser(String email, String role) throws Exception {
        String uid = "test-" + email.replace("@", "-at-").replace(".", "-");
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);
        return uid;
    }
}
