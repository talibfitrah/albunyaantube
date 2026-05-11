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

    // ─── guard: unblock requires BLOCKED status ────────────────────────────────

    @Test
    void unblockNonBlocked_throws() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("a5@t.com", "admin");
        seedUser("a6@t.com", "admin");
        String targetUid = seedUser("u3@t.com", "moderator");
        // targetUid is ACTIVE, not BLOCKED — unblock must reject it

        assertThrows(IllegalStateException.class,
                () -> authService.unblockUser(targetUid, adminUid));
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
