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
            @AuthenticationPrincipal FirebaseUserDetails actor) throws Exception {

        if (actor == null) {
            return ResponseEntity.status(401).body(Map.of("code", "UNAUTHENTICATED"));
        }

        if (!backfillEnabled) {
            return ResponseEntity.status(503).body(Map.of(
                "code", "MIGRATION_DISABLED",
                "hint", "Set app.migrations.user-backfill.enabled=true in the active profile."));
        }

        try {
            UserBackfillMigration.RunSummary summary = migration.run(actor.getUid());
            logger.info("Migration user-backfill triggered by uid={} scanned={} updated={} skipped={}",
                actor.getUid(), summary.scanned(), summary.updated(), summary.skipped());
            return ResponseEntity.ok(Map.of(
                "scanned",     summary.scanned(),
                "updated",     summary.updated(),
                "skipped",     summary.skipped(),
                "startedAt",   summary.startedAt(),
                "completedAt", summary.completedAt()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of(
                "code",    "MIGRATION_RUNNING",
                "message", e.getMessage()));
        }
    }
}
