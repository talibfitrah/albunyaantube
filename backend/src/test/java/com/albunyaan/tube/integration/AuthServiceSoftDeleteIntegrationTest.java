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

import java.util.concurrent.ExecutionException;

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

        assertThrows(ExecutionException.class,
                () -> authService.softDeleteUser(soloAdmin, soloAdmin, "test"),
                "Expected ExecutionException wrapping LastAdminException");

        // Verify user is NOT deleted (transaction rolled back)
        User after = userRepository.findByUid(soloAdmin).orElseThrow();
        assertEquals(UserStatus.ACTIVE, after.getStatusEnum());
    }

    @Test
    void softDeleteUnknownUid_throws() {
        assertThrows(Exception.class,
                () -> authService.softDeleteUser("nonexistent-uid", "admin", "x"));
    }

    @Test
    void recoverNonDeletedUser_throws() throws Exception {
        stubFirebaseAuthMutations();

        String adminUid = seedUser("admin5@t.com", "admin");
        String activeUid = seedUser("active@t.com", "moderator"); // ACTIVE, not deleted

        assertThrows(Exception.class,
                () -> authService.recoverUser(activeUid, adminUid),
                "Expected exception when recovering a non-DELETED user");

        // User should remain ACTIVE
        User after = userRepository.findByUid(activeUid).orElseThrow();
        assertEquals(UserStatus.ACTIVE, after.getStatusEnum());
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
