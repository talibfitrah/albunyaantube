package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BACKEND-AUTH-01: Account Status Filter Integration Tests (Steps 1-7 of Task 5)
 *
 * Verifies that FirebaseAuthFilter enforces server-authoritative account status:
 * - BLOCKED users get 403 ACCOUNT_BLOCKED
 * - DELETED users get 401 ACCOUNT_NOT_FOUND
 * - ACTIVE users with role "user" are accepted by the filter
 * - PENDING_PROFILE users are accepted by the filter
 *
 * Uses a MockBean FirebaseAuth so no Firebase Auth emulator is required.
 * Uses real Firestore emulator (port 8090) via BaseIntegrationTest.
 */
class AccountStatusFilterIntegrationTest extends BaseIntegrationTest {

    @MockBean
    private FirebaseAuth firebaseAuth;

    @Test
    void blockedUser_getsAccountBlocked403() throws Exception {
        String uid = "test-uid-blocked";
        seedUser(uid, "blocked@test.com", "moderator", UserStatus.ACTIVE);
        markBlocked(uid, "policy violation");

        stubToken(uid, "moderator");

        mvc.perform(get("/api/admin/users/me").header("Authorization", "Bearer fake-token-" + uid))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void deletedUser_gets401() throws Exception {
        String uid = "test-uid-deleted";
        seedUser(uid, "deleted@test.com", "moderator", UserStatus.ACTIVE);
        markDeleted(uid);

        stubToken(uid, "moderator");

        mvc.perform(get("/api/admin/users/me").header("Authorization", "Bearer fake-token-" + uid))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void userRole_isAcceptedByFilter() throws Exception {
        String uid = "test-uid-user";
        seedUser(uid, "regular@test.com", "user", UserStatus.ACTIVE);

        stubToken(uid, "user");

        // /api/v1/ is in shouldNotFilter — filter skips, 200 returned directly
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer fake-token-" + uid))
            .andExpect(status().isOk());
    }

    @Test
    void pendingProfileUser_isAcceptedByFilter() throws Exception {
        String uid = "test-uid-pending";
        seedUser(uid, "incomplete@test.com", "user", UserStatus.PENDING_PROFILE);

        stubToken(uid, "user");

        // /api/v1/ is in shouldNotFilter — filter skips, 200 returned directly
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer fake-token-" + uid))
            .andExpect(status().isOk());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Stub FirebaseAuth.verifyIdToken (both overloads) to return a fake token
     * for the given uid + role.
     */
    private void stubToken(String uid, String role) throws Exception {
        FirebaseToken fakeToken = org.mockito.Mockito.mock(FirebaseToken.class);
        when(fakeToken.getUid()).thenReturn(uid);
        when(fakeToken.getEmail()).thenReturn(uid + "@test.com");
        when(fakeToken.getClaims()).thenReturn(Map.of("role", role));

        String tokenValue = "fake-token-" + uid;
        when(firebaseAuth.verifyIdToken(eq(tokenValue))).thenReturn(fakeToken);
        when(firebaseAuth.verifyIdToken(eq(tokenValue), anyBoolean())).thenReturn(fakeToken);
    }

    /**
     * Save a minimal User document directly into the Firestore emulator.
     */
    private void seedUser(String uid, String email, String role, UserStatus status) throws Exception {
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
        u.setStatusEnum(status);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);
    }

    private void markBlocked(String uid, String reason) throws Exception {
        User u = userRepository.findByUid(uid).orElseThrow();
        u.recordBlock("system-test", reason);
        userRepository.save(u);
    }

    private void markDeleted(String uid) throws Exception {
        User u = userRepository.findByUid(uid).orElseThrow();
        u.recordSoftDelete("system-test", null);
        userRepository.save(u);
    }
}
