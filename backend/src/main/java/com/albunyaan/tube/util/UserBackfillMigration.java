package com.albunyaan.tube.util;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * BACKEND-AUTH-01 Task 12: Idempotent user-backfill migration.
 *
 * Scans the entire users collection in cursor-paginated batches and:
 *   1. Sets missing status / role / createdAt / updatedAt defaults.
 *   2. Converts legacy "inactive" status to "blocked" with block metadata.
 *   3. Re-issues Firebase Auth custom claims in lowercase for all users.
 *
 * Safety guarantees:
 *   - CAS lock in system_settings/migration_user_backfill prevents concurrent runs.
 *   - Already-normalised docs are skipped (idempotent).
 *   - A synchronous summary AuditLog is written in the same transaction that
 *     releases the lock, so the summary is always present on success.
 *   - Per-user audit events are async best-effort (volume-driven).
 */
@Component
public class UserBackfillMigration {

    private static final Logger logger = LoggerFactory.getLogger(UserBackfillMigration.class);
    private static final int BATCH_SIZE = 200;
    private static final String LOCK_DOC = "migration_user_backfill";

    public record RunSummary(int scanned, int updated, int skipped,
                             String startedAt, String completedAt) {}

    private final Firestore firestore;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final FirestoreTimeoutProperties timeoutProperties;
    private final AuthService authService;

    public UserBackfillMigration(Firestore firestore,
                                 UserRepository userRepository,
                                 AuditLogService auditLogService,
                                 AuditLogRepository auditLogRepository,
                                 FirestoreTimeoutProperties timeoutProperties,
                                 AuthService authService) {
        this.firestore = firestore;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
        this.timeoutProperties = timeoutProperties;
        this.authService = authService;
    }

    /**
     * Run the backfill. Throws {@link IllegalStateException} if a concurrent run
     * has already claimed the lock.
     *
     * @param actorUid UID of the admin triggering the run (used in audit logs).
     */
    public RunSummary run(String actorUid) throws Exception {
        DocumentReference lockRef = firestore.collection("system_settings").document(LOCK_DOC);

        // Atomic CAS: claim the lock or bail out immediately.
        // F5: explicit write timeout — a stalled Firestore would otherwise block
        // forever and leave the calling admin's request thread hung indefinitely.
        boolean claimed = firestore.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(lockRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (snap.exists() && Boolean.TRUE.equals(snap.getBoolean("running"))) {
                return false;
            }
            tx.set(lockRef, Map.of(
                "running", true,
                "startedAt", Timestamp.now(),
                "claimedBy", InetAddress.getLocalHost().getHostName(),
                "claimedByUid", actorUid
            ));
            return true;
        }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

        if (!claimed) {
            throw new IllegalStateException(
                "Backfill is already running. Wait for completion or clear "
                + "system_settings/" + LOCK_DOC + " if the previous run crashed.");
        }

        String startedAt = Timestamp.now().toString();
        int scanned = 0;
        int updated = 0;
        int skipped = 0;
        String cursor = null;

        try {
            // Phase 1: cursor-paginated normalisation pass.
            while (true) {
                List<User> page = userRepository.findAfter(cursor, BATCH_SIZE);
                if (page.isEmpty()) break;

                for (User u : page) {
                    scanned++;
                    if (normalize(u)) {
                        userRepository.save(u);
                        // Per-user audit: async, best-effort (high volume).
                        auditLogService.logSystem("USER_BACKFILLED", "user", u.getUid(),
                            "UserBackfillMigration");
                        updated++;
                    } else {
                        skipped++;
                    }
                }
                cursor = page.get(page.size() - 1).getUid();
                if (page.size() < BATCH_SIZE) break;
            }

            // Phase 2: re-issue lowercase Firebase Auth custom claims (D6).
            // includeDeleted=true so we also fix deleted accounts in case they are recovered later.
            // F7: merge-set via AuthService.setUserRoleClaim so any OTHER custom claims
            // (subscription tier, feature flags) survive the backfill. Pre-fix the
            // backfill silently wiped every non-role claim on every run.
            for (User u : userRepository.findAll(true)) {
                if (u.getRole() != null && !u.getRole().isBlank()) {
                    authService.setUserRoleClaim(u.getUid(), u.getRole());
                }
            }

        } finally {
            // Release lock + write synchronous summary audit in a single transaction.
            // F5: explicit write timeout — a JVM crash mid-loop already leaves
            // running:true on the lock; we mustn't add an indefinite block here too.
            final int finalScanned = scanned;
            final int finalUpdated = updated;
            firestore.runTransaction(tx -> {
                tx.set(lockRef, Map.of(
                    "running", false,
                    "completedAt", Timestamp.now(),
                    "lastScanned", finalScanned,
                    "lastUpdated", finalUpdated
                ), SetOptions.merge());

                AuditLog summary = auditLogService.buildBackfillRun(
                    finalScanned, finalUpdated, actorUid);
                tx.set(auditLogRepository.auditLogsCollection().document(), summary);
                return null;
            }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        }

        String completedAt = Timestamp.now().toString();
        logger.info("UserBackfillMigration complete: scanned={} updated={} skipped={}",
            scanned, updated, skipped);
        return new RunSummary(scanned, updated, skipped, startedAt, completedAt);
    }

    /**
     * Normalise a single user document in-place. Returns {@code true} if any
     * field was changed and the document must be re-persisted.
     */
    private boolean normalize(User u) {
        boolean changed = false;
        Timestamp now = Timestamp.now();

        // Status: null → active; legacy "inactive" → blocked.
        if (u.getStatus() == null) {
            u.setStatus(UserStatus.ACTIVE.getValue());
            changed = true;
        } else if ("inactive".equalsIgnoreCase(u.getStatus())) {
            u.setStatus(UserStatus.BLOCKED.getValue());
            if (u.getBlockReason() == null) u.setBlockReason("legacy-inactive");
            if (u.getBlockedAt() == null) {
                u.setBlockedAt(u.getCreatedAt() != null ? u.getCreatedAt() : now);
            }
            changed = true;
        }

        // Role normalisation (D6).
        // The migration must heal three classes of broken role values:
        //   - null / blank  → "user" (safest default; matches Role.fromString fallback)
        //   - uppercase / mixed-case canonical values → lowercase canonical
        //     (e.g. "ADMIN" → "admin"); otherwise the admin-count guard misses them.
        //   - non-canonical garbage (e.g. legacy "super-admin") → "user"
        //     (privilege-reduction, safe).
        String currentRole = u.getRole();
        if (currentRole == null || currentRole.isBlank()) {
            u.setRole("user");
            changed = true;
        } else {
            String canonical = currentRole.toLowerCase(Locale.ROOT);
            if (!Set.of("admin", "moderator", "user").contains(canonical)) {
                // Unknown role value — clamp to "user".
                u.setRole("user");
                changed = true;
            } else if (!currentRole.equals(canonical)) {
                u.setRole(canonical);
                changed = true;
            }
        }

        // Timestamps: fill gaps without overwriting existing values.
        if (u.getCreatedAt() == null) {
            u.setCreatedAt(now);
            changed = true;
        }

        if (u.getUpdatedAt() == null) {
            u.setUpdatedAt(u.getCreatedAt());
            changed = true;
        }

        return changed;
    }
}
