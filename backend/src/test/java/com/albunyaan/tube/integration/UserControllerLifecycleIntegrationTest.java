package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BACKEND-AUTH-01 Task 10: UserController lifecycle integration tests.
 *
 * Covers the HTTP-layer e2e flow through @PreAuthorize and @ControllerAdvice for
 * block/unblock, self-block guard, role-based access, soft-delete/recover, and the
 * includeDeleted query-param filter.
 *
 * Uses MockBean FirebaseAuth (no Auth Emulator) + real Firestore emulator via BaseIntegrationTest.
 */
class UserControllerLifecycleIntegrationTest extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void admin_canBlockAndUnblockUser() throws Exception {
        String adminUid = seedUser("admin@t", "admin");
        seedUser("a2@t", "admin"); // ensure not last admin
        String targetUid = seedUser("victim@t", "moderator");
        stubToken(adminUid, "admin");

        mvc.perform(post("/api/admin/users/" + targetUid + "/block")
                .header("Authorization", "Bearer fake-token")
                .contentType("application/json")
                .content("{\"reason\":\"spam\"}"))
            .andExpect(status().isNoContent());

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isBlocked(), "User should be BLOCKED after block call");
        assertEquals("spam", after.getBlockReason());

        mvc.perform(post("/api/admin/users/" + targetUid + "/unblock")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isNoContent());

        User reactivated = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(reactivated.isActive(), "User should be ACTIVE after unblock call");
    }

    @Test
    void admin_cannotBlockSelf_returns409() throws Exception {
        String adminUid = seedUser("solo-admin@t", "admin");
        stubToken(adminUid, "admin");

        mvc.perform(post("/api/admin/users/" + adminUid + "/block")
                .header("Authorization", "Bearer fake-token")
                .contentType("application/json")
                .content("{\"reason\":\"oops\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void moderator_cannotCallAdminEndpoints_returns403() throws Exception {
        String adminUid = seedUser("a@t", "admin");
        String modUid = seedUser("m@t", "moderator");
        stubToken(modUid, "moderator");

        mvc.perform(post("/api/admin/users/" + adminUid + "/block")
                .header("Authorization", "Bearer fake-token")
                .contentType("application/json")
                .content("{\"reason\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void admin_canSoftDeleteAndRecover() throws Exception {
        String adminUid = seedUser("a2@t", "admin");
        seedUser("a3@t", "admin"); // ensure not last
        String targetUid = seedUser("u@t", "moderator");
        stubToken(adminUid, "admin");

        mvc.perform(delete("/api/admin/users/" + targetUid + "?reason=test")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isNoContent());

        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isDeleted(),
                "User should be DELETED after soft-delete call");

        mvc.perform(post("/api/admin/users/" + targetUid + "/recover")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isNoContent());

        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isActive(),
                "User should be ACTIVE after recover call");
    }

    @Test
    void listUsers_excludesDeletedByDefault() throws Exception {
        String adminUid = seedUser("a@t", "admin");
        seedUser("a2@t", "admin"); // ensure not last
        String liveUid = seedUser("live@t", "moderator");
        String deadUid = seedUser("dead@t", "moderator");
        stubToken(adminUid, "admin");

        // Soft-delete the dead user via the endpoint
        mvc.perform(delete("/api/admin/users/" + deadUid + "?reason=test")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isNoContent());

        // Default (no param): deleted user must be excluded
        mvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.uid=='" + deadUid + "')]").doesNotExist())
            .andExpect(jsonPath("$[?(@.uid=='" + liveUid + "')]").exists());

        // Explicit opt-in: deleted user must be included
        mvc.perform(get("/api/admin/users?includeDeleted=true")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.uid=='" + deadUid + "')]").exists());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Save a minimal User document directly to the Firestore emulator and return its UID.
     * UID is deterministic-ish from email + nanosecond to avoid collisions across test runs.
     */
    private String seedUser(String email, String role) throws Exception {
        String uid = "test-" + Math.abs(email.hashCode()) + "-" + System.nanoTime();
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role.toLowerCase());
        u.setStatus("active");
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
        return uid;
    }

    /**
     * Stub FirebaseAuth.verifyIdToken (both overloads) to return a fake token carrying
     * the given uid and role claim. Matches any bearer string so all requests in a test
     * share the same stub without leaking state across tests.
     */
    private void stubToken(String uid, String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getUid()).thenReturn(uid);
        Mockito.when(token.getEmail()).thenReturn(uid + "@test");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }
}
