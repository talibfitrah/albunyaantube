package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BACKEND-AUTH-01 Task 10 Step 1c: MigrationController integration tests.
 *
 * Feature flag is enabled for all tests in this class via @TestPropertySource.
 * The system_settings collection (holds the CAS lock) is included in teardown.
 */
@TestPropertySource(properties = "app.migrations.user-backfill.enabled=true")
class MigrationControllerIntegrationTest extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{
                "categories",
                "channels",
                "playlists",
                "videos",
                "users",
                "audit_logs",
                "system_settings"
        };
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void migrationEndpoint_admin_runsAndReturnsSummary() throws Exception {
        String adminUid = seedUser("admin@t", "admin");

        // Seed one legacy user with null status so the migration has something to update.
        User legacy = new User();
        legacy.setUid("legacy-mig-1");
        legacy.setStatus(null);
        legacy.setRole("moderator");
        userRepository.saveRaw(legacy);

        stubToken(adminUid, "admin");

        mvc.perform(post("/api/admin/migrations/user-backfill")
                .header("Authorization", "Bearer fake-token")
                .header("X-Confirm-Migration", "run-user-backfill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanned").exists())
            .andExpect(jsonPath("$.updated").exists())
            .andExpect(jsonPath("$.skipped").exists())
            // F18: claimWriteFailures must surface in the response body so
            // operators can detect phase-2 claim writes that failed due to
            // orphaned Firestore docs. Healthy data path expects 0.
            .andExpect(jsonPath("$.claimWriteFailures").value(0))
            .andExpect(jsonPath("$.startedAt").exists())
            .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void migrationEndpoint_concurrent_returns409() throws Exception {
        String adminUid = seedUser("admin2@t", "admin");

        // Pre-claim the CAS lock to simulate a concurrent run. F14 (stale-lock
        // recovery) requires a recent startedAt — without it the lock is
        // treated as stale and reclaimed, defeating the point of this test.
        firestore.collection("system_settings").document("migration_user_backfill")
            .set(Map.of(
                "running", true,
                "claimedBy", "other-host",
                "startedAt", Timestamp.now()
            )).get();

        stubToken(adminUid, "admin");

        mvc.perform(post("/api/admin/migrations/user-backfill")
                .header("Authorization", "Bearer fake-token")
                .header("X-Confirm-Migration", "run-user-backfill"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MIGRATION_RUNNING"));
    }

    @Test
    void migrationEndpoint_moderator_returns403() throws Exception {
        String modUid = seedUser("mod@t", "moderator");
        stubToken(modUid, "moderator");

        mvc.perform(post("/api/admin/migrations/user-backfill")
                .header("Authorization", "Bearer fake-token")
                .header("X-Confirm-Migration", "run-user-backfill"))
            .andExpect(status().isForbidden());
    }

    @Test
    void migrationEndpoint_missingConfirmHeader_returns428() throws Exception {
        // Cubic R7 P2 — admin role alone is insufficient; the destructive
        // backfill requires an explicit-intent header so a generic POST
        // replay or stray click cannot trigger it.
        String adminUid = seedUser("admin-noconfirm@t", "admin");
        stubToken(adminUid, "admin");

        mvc.perform(post("/api/admin/migrations/user-backfill")
                .header("Authorization", "Bearer fake-token"))
            // No X-Confirm-Migration header.
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.code").value("MIGRATION_CONFIRM_REQUIRED"));
    }

    @Test
    void migrationEndpoint_wrongConfirmValue_returns428() throws Exception {
        String adminUid = seedUser("admin-wrongconfirm@t", "admin");
        stubToken(adminUid, "admin");

        mvc.perform(post("/api/admin/migrations/user-backfill")
                .header("Authorization", "Bearer fake-token")
                .header("X-Confirm-Migration", "yes"))   // wrong magic
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.code").value("MIGRATION_CONFIRM_REQUIRED"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Save a minimal active User document and return its UID.
     * Mirrors the pattern in UserControllerLifecycleIntegrationTest.
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
     * Stub FirebaseAuth.verifyIdToken (both overloads) to return a fake token
     * carrying the given uid and role claim.
     *
     * Also stubs firebaseAuth.getUser (used by AuthService.setUserRoleClaim, F7)
     * to return a UserRecord with null claims so the migration's phase-2
     * claim-merge loop doesn't NPE.
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

        // F7: AuthService.setUserRoleClaim reads existing claims before merging.
        com.google.firebase.auth.UserRecord rec =
                Mockito.mock(com.google.firebase.auth.UserRecord.class);
        Mockito.when(rec.getCustomClaims()).thenReturn(null);
        Mockito.when(firebaseAuth.getUser(anyString())).thenReturn(rec);
    }
}
