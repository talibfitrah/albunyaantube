package com.albunyaan.tube.util;

import com.albunyaan.tube.integration.BaseIntegrationTest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * BACKEND-AUTH-01 Task 12: Integration tests for UserBackfillMigration.
 *
 * Extends BaseIntegrationTest to get Firestore emulator wiring and
 * automatic collection cleanup before/after each test.
 *
 * system_settings is added to the cleanup list so the CAS lock doc
 * never bleeds between tests.
 */
class UserBackfillMigrationTest extends BaseIntegrationTest {

    @Autowired
    UserBackfillMigration migration;

    @Autowired
    UserRepository repo;

    // No Firebase Auth emulator in integration tests — stub all Auth mutations.
    @MockBean
    FirebaseAuth firebaseAuth;

    @BeforeEach
    void stubFirebaseAuth() throws Exception {
        doNothing().when(firebaseAuth).setCustomUserClaims(anyString(), anyMap());
    }

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

    // ─────────────────────────────────────────────────────────────────────────
    // T1: missing defaults are set
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void backfillSetsMissingDefaults() throws Exception {
        User legacy = new User();
        legacy.setUid("legacy-1");
        legacy.setEmail("l1@t");
        legacy.setRole("moderator");
        legacy.setStatus(null);
        legacy.setCreatedAt(null);
        legacy.setUpdatedAt(null);
        repo.saveRaw(legacy);

        UserBackfillMigration.RunSummary summary = migration.run("test-actor");

        User after = repo.findByUid("legacy-1").orElseThrow();
        assertEquals("active", after.getStatus(), "missing status must default to active");
        assertNotNull(after.getCreatedAt(), "createdAt must be set");
        assertNotNull(after.getUpdatedAt(), "updatedAt must be set");
        assertTrue(summary.updated() >= 1, "updated count must be at least 1");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T2: legacy "inactive" → "blocked" with reason + timestamp
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void inactiveStatusBecomesBlockedWithReason() throws Exception {
        User legacy = new User();
        legacy.setUid("legacy-2");
        legacy.setEmail("l2@t");
        legacy.setRole("moderator");
        legacy.setStatus("inactive");
        legacy.setCreatedAt(Timestamp.now());
        repo.saveRaw(legacy);

        migration.run("test-actor");

        User after = repo.findByUid("legacy-2").orElseThrow();
        assertEquals("blocked", after.getStatus(), "inactive must map to blocked");
        assertEquals("legacy-inactive", after.getBlockReason(), "block reason must be set");
        assertNotNull(after.getBlockedAt(), "blockedAt must be set");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T3: second run is idempotent — already-normalised docs are not touched
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void runningTwiceIsIdempotent() throws Exception {
        User legacy = new User();
        legacy.setUid("legacy-3");
        legacy.setRole("user");
        legacy.setStatus(null);
        repo.saveRaw(legacy);

        migration.run("test-actor");
        Timestamp updatedAfterFirst = repo.findByUid("legacy-3").orElseThrow().getUpdatedAt();

        UserBackfillMigration.RunSummary second = migration.run("test-actor");
        Timestamp updatedAfterSecond = repo.findByUid("legacy-3").orElseThrow().getUpdatedAt();

        assertEquals(updatedAfterFirst, updatedAfterSecond,
            "Idempotent run must not modify already-normalised docs");
        assertEquals(0, second.updated(),
            "Second run must report zero updates when all docs are already normalised");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T4: concurrent run attempt throws while lock is held
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void concurrentRun_throwsMigrationRunning() throws Exception {
        // Pre-claim the CAS lock to simulate a running migration.
        firestore.collection("system_settings").document("migration_user_backfill")
                .set(Map.of("running", true, "claimedBy", "other-host")).get();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> migration.run("test-actor"),
            "Second run must throw when lock is held");
        assertTrue(ex.getMessage().contains("already running"),
            "Exception message should describe the lock conflict");

        // Release the lock so @AfterEach cleanup can delete the collection normally.
        firestore.collection("system_settings").document("migration_user_backfill").delete().get();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F2: migration normalises uppercase / mixed-case roles to lowercase
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void backfillNormalizesUppercaseRoleToLowercase() throws Exception {
        // Pre-F2 admins (e.g. initial admin created with literal "ADMIN") never
        // get healed because the null/blank check is the only role gate.
        User uppercaseAdmin = new User();
        uppercaseAdmin.setUid("legacy-admin");
        uppercaseAdmin.setEmail("legacy-admin@t");
        uppercaseAdmin.setRole("ADMIN");
        uppercaseAdmin.setStatus("active");
        uppercaseAdmin.setCreatedAt(Timestamp.now());
        uppercaseAdmin.setUpdatedAt(Timestamp.now());
        repo.saveRaw(uppercaseAdmin);

        migration.run("test-actor");

        User after = repo.findByUid("legacy-admin").orElseThrow();
        assertEquals("admin", after.getRole(),
                "Uppercase 'ADMIN' must be lowercased so admin-count queries hit it");
    }

    @Test
    void backfillNormalizesMixedCaseRoleToLowercase() throws Exception {
        User mixed = new User();
        mixed.setUid("legacy-mixed");
        mixed.setEmail("mixed@t");
        mixed.setRole("Moderator");
        mixed.setStatus("active");
        mixed.setCreatedAt(Timestamp.now());
        mixed.setUpdatedAt(Timestamp.now());
        repo.saveRaw(mixed);

        migration.run("test-actor");

        assertEquals("moderator", repo.findByUid("legacy-mixed").orElseThrow().getRole());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F2: non-canonical role values are clamped to "user" (privilege reduction)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void backfillClampsNonCanonicalRoleToUser() throws Exception {
        User legacyJunk = new User();
        legacyJunk.setUid("legacy-junk");
        legacyJunk.setEmail("junk@t");
        legacyJunk.setRole("super-admin"); // not in {admin, moderator, user}
        legacyJunk.setStatus("active");
        legacyJunk.setCreatedAt(Timestamp.now());
        legacyJunk.setUpdatedAt(Timestamp.now());
        repo.saveRaw(legacyJunk);

        migration.run("test-actor");

        assertEquals("user", repo.findByUid("legacy-junk").orElseThrow().getRole(),
                "Unknown legacy role values must be clamped to 'user' (privilege reduction)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F3: a Firestore doc with no role field deserialises with role=null
    //     (after F3 ctor fix), then the migration clamps it to "user".
    //     Pre-fix the no-arg ctor defaulted role to "moderator" — a silent
    //     privilege grant. This test must FAIL on the pre-F3 code.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void migrationTreatsMissingRoleFieldAsUser_evenAfterDeserialization() throws Exception {
        // Seed a doc with NO role field (simulating a legacy document predating
        // the role column). Direct-to-Firestore write bypasses the User ctor.
        firestore.collection("users").document("missing-role")
                .set(java.util.Map.of(
                        "email", "no-role@t",
                        "status", "active",
                        "createdAt", Timestamp.now(),
                        "updatedAt", Timestamp.now()
                )).get();

        migration.run("test-actor");

        User after = repo.findByUid("missing-role").orElseThrow();
        assertEquals("user", after.getRole(),
                "Missing role field MUST be normalised to 'user', not silently granted 'moderator'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T5: synchronous summary AuditLog is written on completion
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summaryAuditWritten() throws Exception {
        User legacy = new User();
        legacy.setUid("legacy-summary");
        legacy.setStatus(null);
        legacy.setRole("user");
        repo.saveRaw(legacy);

        UserBackfillMigration.RunSummary summary = migration.run("test-actor");

        assertTrue(summary.updated() >= 1,
            "At least one doc should have been updated");

        QuerySnapshot audits = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_BACKFILL_RUN")
                .whereEqualTo("entityId", "user-backfill")
                .get().get();
        assertTrue(audits.size() >= 1,
            "Summary audit log must be written synchronously after migration run");
    }
}
