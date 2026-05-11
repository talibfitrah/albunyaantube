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
 * BACKEND-AUTH-01: Integration tests for AuthService.blockUser and unblockUser.
 *
 * Uses real Firestore emulator for transactional correctness.
 * Mocks FirebaseAuth (updateUser, revokeRefreshTokens) — no Auth emulator required.
 */
class AuthServiceBlockIntegrationTest extends BaseIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    AuditLogRepository auditRepo;

    @MockBean
    FirebaseAuth firebaseAuth;

    // ─── blockUser ─────────────────────────────────────────────────────────────

    @Test
    void block_marksBlockedAndDisablesAuth() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a@t.com", "admin");
        seedUser("a2@t.com", "admin"); // ensure not last admin
        String targetUid = seedUser("u@t.com", "moderator");

        authService.blockUser(targetUid, adminUid, "spam");

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isBlocked());
        assertEquals("spam", after.getBlockReason());
        assertEquals(adminUid, after.getBlockedBy());

        QuerySnapshot audits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_BLOCKED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, audits.size());
    }

    // ─── unblockUser ───────────────────────────────────────────────────────────

    @Test
    void unblock_marksActive() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a3@t.com", "admin");
        seedUser("a4@t.com", "admin");
        String targetUid = seedUser("u2@t.com", "moderator");

        authService.blockUser(targetUid, adminUid, "test");
        authService.unblockUser(targetUid, adminUid);

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isActive());
        assertEquals(UserStatus.ACTIVE, after.getStatusEnum());
    }

    // ─── guard: last-admin ─────────────────────────────────────────────────────

    @Test
    void blockLastAdmin_throws() throws Exception {
        stubFirebaseAuthMutations();

        String soloAdmin = seedUser("solo@t.com", "admin");

        assertThrows(LastAdminException.class,
                () -> authService.blockUser(soloAdmin, soloAdmin, "test"));
    }

    // ─── guard: unblock rejects DELETED but no-ops on ACTIVE ───────────────────

    @Test
    void unblockDeleted_throws() throws Exception {
        // After F13, unblock on ACTIVE is a no-op (covered by
        // unblockUser_isIdempotent_onAlreadyActiveTarget); but unblock on a
        // DELETED user must still throw — the recover path is the only legit
        // way out of DELETED.
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a5@t.com", "admin");
        seedUser("a6@t.com", "admin");
        String targetUid = seedUser("u3@t.com", "moderator");
        authService.softDeleteUser(targetUid, adminUid, "test");

        assertThrows(IllegalStateException.class,
                () -> authService.unblockUser(targetUid, adminUid),
                "unblock on a DELETED user must still throw — must use recover");
    }

    // ── F13 — blockUser is idempotent on already-BLOCKED target ─────────────
    // Pre-F13 a retry after a partial failure (tx commits, FB Auth fails,
    // admin retries) overwrote blockedAt/blockReason and wrote a SECOND
    // USER_BLOCKED audit row. Now the second call is a no-op.

    @Test
    void blockUser_isIdempotent_onAlreadyBlockedTarget() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a-f13-1@t.com", "admin");
        seedUser("a-f13-2@t.com", "admin");
        String targetUid = seedUser("victim-f13@t.com", "moderator");

        // First block.
        authService.blockUser(targetUid, adminUid, "first-reason");
        User after1 = userRepository.findByUid(targetUid).orElseThrow();
        Timestamp firstBlockedAt = after1.getBlockedAt();
        assertNotNull(firstBlockedAt, "First block must record blockedAt");

        // Second block — must be a no-op. Sleep ~5ms so Timestamp.now() would
        // produce a different value if recordBlock fired again.
        Thread.sleep(5);
        authService.blockUser(targetUid, adminUid, "second-reason");

        User after2 = userRepository.findByUid(targetUid).orElseThrow();
        assertEquals(firstBlockedAt, after2.getBlockedAt(),
                "Idempotent block: blockedAt timestamp must not change");
        assertEquals("first-reason", after2.getBlockReason(),
                "Idempotent block: original blockReason must be preserved");

        // Audit row count must still be 1, not 2.
        QuerySnapshot audits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_BLOCKED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, audits.size(),
                "Idempotent block: only ONE USER_BLOCKED audit row must exist");
    }

    // ── F13 — unblockUser is idempotent on already-ACTIVE target ────────────

    @Test
    void unblockUser_isIdempotent_onAlreadyActiveTarget() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a-f13-3@t.com", "admin");
        seedUser("a-f13-4@t.com", "admin");
        String targetUid = seedUser("user-f13-unblock@t.com", "moderator");

        // Target starts ACTIVE — unblock must be a silent no-op.
        // Pre-F13 this threw IllegalStateException("User is not in BLOCKED status").
        authService.unblockUser(targetUid, adminUid);

        // No audit row should be written.
        QuerySnapshot audits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_UNBLOCKED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(0, audits.size(),
                "Idempotent unblock on ACTIVE target must write NO audit row");

        // Target still ACTIVE.
        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isActive());
    }

    // ── F12 — blockUser refuses DELETED targets ─────────────────────────────
    // Pre-F12 path: softDelete → block (audit: USER_BLOCKED) → unblock
    // (audit: USER_UNBLOCKED) → ends up status="active" with NO recover audit.

    @Test
    void blockUser_throwsWhenTargetIsDeleted() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a-f12-1@t.com", "admin");
        seedUser("a-f12-2@t.com", "admin");
        String targetUid = seedUser("victim-f12@t.com", "moderator");

        // Soft-delete first.
        authService.softDeleteUser(targetUid, adminUid, "policy");
        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isDeleted(),
                "Pre-condition: target must be DELETED");

        // Attempt to block — must throw IllegalStateException.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> authService.blockUser(targetUid, adminUid, "test-block"),
                "blockUser must throw when target is DELETED");
        assertTrue(ex.getMessage().toLowerCase().contains("recover"),
                "Error message must mention recover path: " + ex.getMessage());

        // Verify target is still DELETED.
        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertEquals(UserStatus.DELETED, after.getStatusEnum());
    }

    // ── F12 — updateUserRoleAsActor refuses DELETED targets ─────────────────
    // Same audit-evasion shape: deleted users could have their role mutated
    // without ever transitioning back to ACTIVE through the recover path.

    @Test
    void updateUserRoleAsActor_throwsWhenTargetIsDeleted() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a-f12-3@t.com", "admin");
        seedUser("a-f12-4@t.com", "admin");
        String targetUid = seedUser("role-victim-f12@t.com", "moderator");

        authService.softDeleteUser(targetUid, adminUid, "policy");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> authService.updateUserRoleAsActor(targetUid, "admin", adminUid),
                "updateUserRoleAsActor must throw when target is DELETED");
        assertTrue(ex.getMessage().toLowerCase().contains("recover"),
                "Error message must mention recover path: " + ex.getMessage());

        // Role and status unchanged
        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertEquals(UserStatus.DELETED, after.getStatusEnum());
        assertEquals("moderator", after.getRole());
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stub the FirebaseAuth mutation calls made by blockUser and unblockUser.
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
