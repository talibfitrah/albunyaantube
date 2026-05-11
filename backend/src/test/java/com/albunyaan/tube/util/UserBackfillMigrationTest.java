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
        // F7: AuthService.setUserRoleClaim reads existing claims via getUser
        // before merging. Stub a UserRecord with null claims so the merge call
        // doesn't NPE in the migration's phase-2 loop.
        com.google.firebase.auth.UserRecord rec =
                org.mockito.Mockito.mock(com.google.firebase.auth.UserRecord.class);
        org.mockito.Mockito.when(rec.getCustomClaims()).thenReturn(null);
        org.mockito.Mockito.when(firebaseAuth.getUser(anyString())).thenReturn(rec);

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
        // Pre-claim the CAS lock to simulate a running migration. Use a recent
        // startedAt so the F14 stale-lock check considers the lock fresh.
        firestore.collection("system_settings").document("migration_user_backfill")
                .set(Map.of(
                        "running", true,
                        "claimedBy", "other-host",
                        "startedAt", Timestamp.now()
                )).get();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> migration.run("test-actor"),
            "Second run must throw when lock is held");
        assertTrue(ex.getMessage().contains("already running"),
            "Exception message should describe the lock conflict");

        // Release the lock so @AfterEach cleanup can delete the collection normally.
        firestore.collection("system_settings").document("migration_user_backfill").delete().get();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F14: stale lock recovery — a lock held longer than STALE_LOCK_MS is
    //      treated as a crashed run and reclaimed automatically.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void migration_reclaimsStaleLock_afterStaleThreshold() throws Exception {
        // Seed a lock with a startedAt one hour ago — well past STALE_LOCK_MS
        // (30 min). The current run must reclaim it.
        Timestamp staleStart = Timestamp.ofTimeSecondsAndNanos(
                Timestamp.now().getSeconds() - 60L * 60L,  // 1 hour ago
                0);
        firestore.collection("system_settings").document("migration_user_backfill")
                .set(Map.of(
                        "running", true,
                        "claimedBy", "crashed-host",
                        "claimedByUid", "ghost-admin",
                        "startedAt", staleStart
                )).get();

        // Seed at least one user so the run has something to do.
        User u = new User();
        u.setUid("legacy-stale");
        u.setRole("user");
        u.setStatus("active");
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        repo.saveRaw(u);

        // Must NOT throw — stale lock is reclaimed and the run proceeds.
        UserBackfillMigration.RunSummary summary = migration.run("rescue-actor");
        assertNotNull(summary, "Stale-lock reclaim must allow the run to complete");

        // Verify the lock was released cleanly at the end.
        var lockSnap = firestore.collection("system_settings")
                .document("migration_user_backfill").get().get();
        assertEquals(Boolean.FALSE, lockSnap.getBoolean("running"),
                "Lock must end in running=false after a successful reclaim run");
    }

    @Test
    void migration_doesNotReclaimRecentLock() throws Exception {
        // Lock with a 30-second-old startedAt — well inside STALE_LOCK_MS.
        // Must NOT be reclaimed.
        Timestamp recentStart = Timestamp.ofTimeSecondsAndNanos(
                Timestamp.now().getSeconds() - 30L,  // 30s ago
                0);
        firestore.collection("system_settings").document("migration_user_backfill")
                .set(Map.of(
                        "running", true,
                        "claimedBy", "live-host",
                        "claimedByUid", "concurrent-admin",
                        "startedAt", recentStart
                )).get();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> migration.run("would-be-rescuer"),
                "Recent lock must NOT be reclaimed — concurrent run is genuine");
        assertTrue(ex.getMessage().contains("already running"),
                "Error message should describe the lock conflict");

        // Cleanup
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

    // ─────────────────────────────────────────────────────────────────────────
    // F18: phase-2 per-user error isolation.
    //
    // Pre-F18 the phase-2 claim-write loop aborted on the first
    // FirebaseAuthException (e.g. orphaned Firestore doc where the
    // corresponding Firebase Auth user was manually deleted). Phase 1's
    // "completed" lock-release audit fired anyway, so the operator believed
    // the migration succeeded while it had actually skipped users. Now each
    // setUserRoleClaim call is wrapped: failures are logged + counted +
    // surfaced via RunSummary.claimWriteFailures().
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void migration_phase2_continuesOnFirebaseAuthMissingUser() throws Exception {
        // Seed TWO Firestore user docs.
        User present = new User();
        present.setUid("phase2-present");
        present.setEmail("present@t");
        present.setRole("moderator");
        present.setStatus("active");
        present.setCreatedAt(Timestamp.now());
        present.setUpdatedAt(Timestamp.now());
        repo.saveRaw(present);

        User orphan = new User();
        orphan.setUid("phase2-orphan");
        orphan.setEmail("orphan@t");
        orphan.setRole("user");
        orphan.setStatus("active");
        orphan.setCreatedAt(Timestamp.now());
        orphan.setUpdatedAt(Timestamp.now());
        repo.saveRaw(orphan);

        // Override the @BeforeEach stub: getUser succeeds for "phase2-present"
        // but throws FirebaseAuthException for "phase2-orphan", mimicking the
        // orphaned-Firestore-doc scenario (user exists in Firestore but was
        // manually deleted from Firebase Auth).
        com.google.firebase.auth.UserRecord rec =
                org.mockito.Mockito.mock(com.google.firebase.auth.UserRecord.class);
        org.mockito.Mockito.when(rec.getCustomClaims()).thenReturn(null);
        org.mockito.Mockito.when(firebaseAuth.getUser("phase2-present")).thenReturn(rec);

        com.google.firebase.auth.FirebaseAuthException fbEx =
                org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseAuthException.class);
        org.mockito.Mockito.when(fbEx.getMessage()).thenReturn("user-not-found");
        org.mockito.Mockito.when(firebaseAuth.getUser("phase2-orphan")).thenThrow(fbEx);

        // Migration must NOT abort — both users get scanned, only orphan's
        // claim write fails, and the failure is surfaced in the summary.
        UserBackfillMigration.RunSummary summary = migration.run("test-actor");

        assertEquals(1, summary.claimWriteFailures(),
                "F18: exactly one phase-2 claim write must have failed (the orphan)");

        // The user that DID exist in FB Auth had its claim set successfully.
        org.mockito.Mockito.verify(firebaseAuth, org.mockito.Mockito.atLeastOnce())
                .setCustomUserClaims(org.mockito.ArgumentMatchers.eq("phase2-present"),
                        org.mockito.ArgumentMatchers.anyMap());

        // The orphan's claim write was never attempted (getUser threw first).
        org.mockito.Mockito.verify(firebaseAuth, org.mockito.Mockito.never())
                .setCustomUserClaims(org.mockito.ArgumentMatchers.eq("phase2-orphan"),
                        org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void migration_phase2_zeroFailures_whenAllUsersExistInAuth() throws Exception {
        // Sanity check: when all users exist in Auth, claimWriteFailures stays
        // zero. Prevents the counter from drifting upward on healthy data.
        User u = new User();
        u.setUid("phase2-healthy");
        u.setEmail("healthy@t");
        u.setRole("admin");
        u.setStatus("active");
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        repo.saveRaw(u);

        UserBackfillMigration.RunSummary summary = migration.run("test-actor");
        assertEquals(0, summary.claimWriteFailures(),
                "F18: claimWriteFailures must be 0 when every user has a FB Auth record");
    }
}
