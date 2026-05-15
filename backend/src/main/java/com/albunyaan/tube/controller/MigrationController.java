package com.albunyaan.tube.controller;

import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.util.UserBackfillMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * BACKEND-AUTH-01 Task 10 Step 1c: HTTP surface for UserBackfillMigration.
 *
 * Exposes a single admin-only endpoint:
 *   POST /api/admin/migrations/user-backfill
 *
 * The endpoint is gated by a feature flag (app.migrations.user-backfill.enabled)
 * so it is inert in production unless explicitly enabled. The underlying
 * UserBackfillMigration holds a Firestore CAS lock that prevents concurrent runs.
 */
@RestController
@RequestMapping("/api/admin/migrations")
public class MigrationController {

    private static final Logger logger = LoggerFactory.getLogger(MigrationController.class);

    private final UserBackfillMigration migration;

    @Value("${app.migrations.user-backfill.enabled:false}")
    private boolean backfillEnabled;

    public MigrationController(UserBackfillMigration migration) {
        this.migration = migration;
    }

    /**
     * Trigger the user-backfill migration.
     *
     * <ul>
     *   <li>401 – unauthenticated (no valid Firebase token)</li>
     *   <li>403 – authenticated but not ADMIN (handled by {@code @PreAuthorize})</li>
     *   <li>503 – feature flag is off</li>
     *   <li>409 – a concurrent run has already claimed the lock</li>
     *   <li>200 – migration completed; body contains scanned/updated/skipped/startedAt/completedAt</li>
     * </ul>
     */
    @PostMapping("/user-backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> runUserBackfill(
            @AuthenticationPrincipal FirebaseUserDetails actor,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "X-Confirm-Migration", required = false) String confirmHeader)
            throws Exception {

        // Cubic R8 P3 — `actor == null` 401 branch removed.
        // @PreAuthorize("hasRole('ADMIN')") above already rejects an
        // unauthenticated principal with 403 before this handler body runs,
        // so the inline 401 was unreachable defensive scaffolding from an
        // earlier shape. Authentication state below is guaranteed non-null.

        // Cubic R7 P2 — confirm-header gate for destructive admin action.
        //
        // Pre-fix any authenticated admin token could trigger a full
        // tenant-wide user backfill with a single POST; a CSRF-equivalent
        // (misclick in admin UI, replay of a leaked curl, malicious browser
        // extension on an admin machine) would silently kick off a long
        // mutation. Until 2FA arrives, gating the endpoint on an
        // explicit-intent header — which the admin UI must populate, and a
        // generic POST replay would not — closes the trivial-trigger
        // attack. The expected value mirrors the action so a "yes" coerced
        // into an unrelated form does not fire.
        if (!"run-user-backfill".equals(confirmHeader)) {
            return ResponseEntity.status(428).body(Map.of(
                "code", "MIGRATION_CONFIRM_REQUIRED",
                "hint", "Set X-Confirm-Migration: run-user-backfill header."));
        }

        if (!backfillEnabled) {
            return ResponseEntity.status(503).body(Map.of(
                "code", "MIGRATION_DISABLED",
                "hint", "Set app.migrations.user-backfill.enabled=true in the active profile."));
        }

        try {
            UserBackfillMigration.RunSummary summary = migration.run(actor.getUid());
            logger.info(
                "Migration user-backfill triggered by uid={} scanned={} updated={} skipped={} claimWriteFailures={}",
                actor.getUid(), summary.scanned(), summary.updated(),
                summary.skipped(), summary.claimWriteFailures());
            // F18: surface claimWriteFailures so the operator can see when phase-2
            // skipped users due to orphaned Firestore docs (user in Firestore but
            // missing in Firebase Auth). Pre-F18 the loop aborted on the first
            // such case and the operator saw a "success" response anyway.
            return ResponseEntity.ok(Map.of(
                "scanned",             summary.scanned(),
                "updated",             summary.updated(),
                "skipped",             summary.skipped(),
                "claimWriteFailures",  summary.claimWriteFailures(),
                "startedAt",           summary.startedAt(),
                "completedAt",         summary.completedAt()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of(
                "code",    "MIGRATION_RUNNING",
                "message", e.getMessage()));
        }
    }
}
