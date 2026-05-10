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
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanned").exists())
            .andExpect(jsonPath("$.updated").exists())
            .andExpect(jsonPath("$.skipped").exists())
            .andExpect(jsonPath("$.startedAt").exists())
            .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void migrationEndpoint_concurrent_returns409() throws Exception {
        String adminUid = seedUser("admin2@t", "admin");

        // Pre-claim the CAS lock to simulate a concurrent run.
        firestore.collection("system_settings").document("migration_user_backfill")
            .set(Map.of("running", true, "claimedBy", "other-host")).get();

        stubToken(adminUid, "admin");

        mvc.perform(post("/api/admin/migrations/user-backfill")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MIGRATION_RUNNING"));
    }

    @Test
    void migrationEndpoint_moderator_returns403() throws Exception {
        String modUid = seedUser("mod@t", "moderator");
        stubToken(modUid, "moderator");

        mvc.perform(post("/api/admin/migrations/user-backfill")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isForbidden());
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
