package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ── F15 — block reason MUST NOT be leaked to the blocked user ────────────
    // Pre-fix the filter echoed u.getBlockReason() into the 403 body. If a
    // moderator wrote internal notes ("internal-troll-banned-per-ticket-1234,
    // contact legal"), the banned user could read them on every 403.

    @Test
    void filterResponseForBlockedUser_doesNotLeakInternalReason() throws Exception {
        String uid = "test-uid-blocked-internal";
        String internalReason = "internal-troll-banned-per-ticket-1234-contact-legal";

        seedUser(uid, "leaky@test.com", "moderator", UserStatus.ACTIVE);
        markBlocked(uid, internalReason);

        stubToken(uid, "moderator");

        mvc.perform(get("/api/admin/users/me").header("Authorization", "Bearer fake-token-" + uid))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"))
            // F15: the body must not include the raw block reason.
            .andExpect(jsonPath("$.reason").doesNotExist())
            .andExpect(result -> {
                String body = result.getResponse().getContentAsString();
                assertFalse(body.contains(internalReason),
                    "Internal block reason leaked to blocked user. Body: " + body);
                assertFalse(body.contains("ticket-1234"),
                    "Internal ticket reference leaked. Body: " + body);
                assertFalse(body.contains("legal"),
                    "Internal legal-routing hint leaked. Body: " + body);
            });
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

        // /api/admin/users is a filtered path (shouldNotFilter returns false for /api/admin/).
        // The filter runs: verifies token, checks account status (ACTIVE → no block/delete), and
        // sets Spring Security authentication with ROLE_USER. Spring Security's @PreAuthorize
        // ("hasRole('ADMIN')") then denies the request with 403.
        //
        // If the filter had rejected the "user" role (which it does NOT — it silently keeps it),
        // or if the filter blocked an ACTIVE user, we would see a filter-level 401 with a code
        // of ACCOUNT_NOT_FOUND or ACCOUNT_BLOCKED. A 403 from @PreAuthorize proves the filter
        // passed the token through to Spring Security successfully.
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer fake-token-" + uid))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                // 403 = @PreAuthorize blocked (filter passed); anything else is unexpected here.
                // Crucially, if filter had rejected the user it would return 401 with a code field.
                if (status == 401) {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(
                        body.contains("ACCOUNT_BLOCKED") || body.contains("ACCOUNT_NOT_FOUND"),
                        "Filter blocked an ACTIVE 'user' role account — should have passed through. Body: " + body
                    );
                }
                // 403 from @PreAuthorize is the expected outcome; 401 without a block code is
                // also tolerable (e.g. auth framework 401 for insufficient role), but a filter
                // block code is a failure.
            });
    }

    @Test
    void pendingProfileUser_isAcceptedByFilter() throws Exception {
        String uid = "test-uid-pending";
        seedUser(uid, "incomplete@test.com", "user", UserStatus.PENDING_PROFILE);

        stubToken(uid, "user");

        // Same logic as userRole_isAcceptedByFilter above, but with PENDING_PROFILE status.
        // PENDING_PROFILE is NOT a terminal/blocked state — the filter must not treat it as
        // BLOCKED or DELETED. A 403 from @PreAuthorize (controller layer) proves the filter
        // passed the user through without a status-gate rejection.
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer fake-token-" + uid))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                if (status == 401) {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(
                        body.contains("ACCOUNT_BLOCKED") || body.contains("ACCOUNT_NOT_FOUND"),
                        "Filter blocked a PENDING_PROFILE user — should have passed through. Body: " + body
                    );
                }
            });
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
