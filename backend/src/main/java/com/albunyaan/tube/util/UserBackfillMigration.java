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

    /**
     * F14: if a previously-claimed lock has not been released within this
     * window, treat it as stale (probably a JVM crash mid-run) and reclaim.
     *
     * 30 minutes is well past any reasonable backfill duration — at BATCH_SIZE
     * = 200 docs/iteration and typical Firestore latencies, a migration that
     * hasn't completed in 30 min is wedged or dead. The original CAS lock had
     * no staleness recovery, so a crashed instance left running:true forever
     * and required manual Firestore intervention.
     */
    static final long STALE_LOCK_MS = 30L * 60L * 1000L;

    public record RunSummary(int scanned, int updated, int skipped,
                             int claimWriteFailures,
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
        // F14: if a prior lock with running:true has been held longer than
        // STALE_LOCK_MS, treat it as stale (likely a JVM crash mid-run) and
        // reclaim. Pre-F14 a crashed instance left running:true forever, so the
        // operator had to manually delete the Firestore lock doc to recover.
        boolean claimed = firestore.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(lockRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (snap.exists() && Boolean.TRUE.equals(snap.getBoolean("running"))) {
                Timestamp startedAt = snap.getTimestamp("startedAt");
                long ageMs = startedAt == null
                        ? Long.MAX_VALUE  // missing startedAt → conservatively treat as stale
                        : Timestamp.now().toDate().getTime() - startedAt.toDate().getTime();
                if (ageMs < STALE_LOCK_MS) {
                    // Lock is still fresh — another run is genuinely in flight.
                    return false;
                }
                // Stale lock — log loudly so the operator knows a prior run crashed.
                logger.warn(
                    "Reclaiming stale migration lock (held {} ms by claimedBy={} / claimedByUid={}). "
                    + "This usually means a previous run crashed mid-flight. "
                    + "Inspect audit_logs for incomplete USER_BACKFILL_RUN summary.",
                    ageMs, snap.getString("claimedBy"), snap.getString("claimedByUid"));
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
        int claimWriteFailures = 0;
        // Cubic R7 P2 — resume from any cursor checkpoint left by a prior
        // crashed run. The stale-lock reclaim (F14) only releases the
        // claim; the cursor was lost so the new run restarted from the
        // top, re-doing every already-normalised user. We now read the
        // checkpoint set by the prior run (if any) and resume after it.
        // First-run case: snap.getString("phase1Cursor") returns null,
        // matching the previous behaviour.
        DocumentSnapshot postClaimSnap = lockRef.get()
                .get(timeoutProperties.getRead(), TimeUnit.SECONDS);
        String cursor = postClaimSnap.exists() ? postClaimSnap.getString("phase1Cursor") : null;
        if (cursor != null) {
            logger.info("Resuming backfill phase 1 from checkpoint cursor={}", cursor);
        }

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
                // Cubic R7 P2 — write the cursor checkpoint after each page.
                // Best-effort: if the checkpoint write fails we still continue
                // (the phase will complete normally and the failure is logged);
                // if the JVM then crashes the worst case is re-processing
                // BATCH_SIZE rows on resume, which is idempotent (normalize()
                // returns false when nothing needs to change).
                try {
                    lockRef.update("phase1Cursor", cursor,
                                   "phase1CheckpointedAt", Timestamp.now())
                            .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.warn("Phase 1 cursor checkpoint write failed (cursor={}): {}",
                            cursor, e.getMessage());
                }
                if (page.size() < BATCH_SIZE) break;
            }

            // Phase 2: re-issue lowercase Firebase Auth custom claims (D6).
            // includeDeleted=true so we also fix deleted accounts in case they are recovered later.
            // F7: merge-set via AuthService.setUserRoleClaim so any OTHER custom claims
            // (subscription tier, feature flags) survive the backfill. Pre-fix the
            // backfill silently wiped every non-role claim on every run.
            //
            // F18: per-user error isolation. setUserRoleClaim calls firebaseAuth.getUser
            // first (to read existing claims for the merge). If ANY user in the loop is
            // missing from Firebase Auth (orphaned Firestore doc — normal divergence
            // after manual deletions or partial-failure history) getUser throws
            // FirebaseAuthException and the entire phase-2 loop aborts. Pre-F18 the
            // operator saw a "completed" lock release in the finally block and assumed
            // success, when in reality phase 2 had silently skipped users. Now we
            // wrap each call, log + count failures, and surface the count back to
            // the operator via RunSummary.
            // CodeRabbit-flagged: paginate phase 2 with the same cursor-pattern as phase 1.
            // findAll(true) loaded the whole users collection into memory; on a large user
            // base that is an OOM hazard. Reuse findAfter(cursor, BATCH_SIZE).
            String claimCursor = null;
            while (true) {
                List<User> claimPage = userRepository.findAfter(claimCursor, BATCH_SIZE);
                if (claimPage.isEmpty()) break;
                for (User u : claimPage) {
                    if (u.getRole() != null && !u.getRole().isBlank()) {
                        try {
                            authService.setUserRoleClaim(u.getUid(), u.getRole());
                        } catch (Exception e) {
                            // Any exception — FirebaseAuthException (user missing in Auth),
                            // network, etc. — must not abort the loop. Log loudly so the
                            // operator can investigate the orphaned doc separately.
                            logger.warn("Phase 2 claim write failed for uid={}: {}",
                                u.getUid(), e.getMessage());
                            claimWriteFailures++;
                        }
                    }
                }
                claimCursor = claimPage.get(claimPage.size() - 1).getUid();
                if (claimPage.size() < BATCH_SIZE) break;
            }

        } finally {
            // Release lock + write synchronous summary audit in a single transaction.
            // F5: explicit write timeout — a JVM crash mid-loop already leaves
            // running:true on the lock; we mustn't add an indefinite block here too.
            // CodeRabbit-flagged: persist claimWriteFailures so operators can see Phase 2
            // failures in both the lock doc and the audit log.
            final int finalScanned = scanned;
            final int finalUpdated = updated;
            final int finalClaimWriteFailures = claimWriteFailures;
            firestore.runTransaction(tx -> {
                tx.set(lockRef, Map.of(
                    "running", false,
                    "completedAt", Timestamp.now(),
                    "lastScanned", finalScanned,
                    "lastUpdated", finalUpdated,
                    "lastClaimWriteFailures", finalClaimWriteFailures
                ), SetOptions.merge());

                AuditLog summary = auditLogService.buildBackfillRun(
                    finalScanned, finalUpdated, finalClaimWriteFailures, actorUid);
                auditLogRepository.saveInTransaction(tx, summary);
                return null;
            }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        }

        String completedAt = Timestamp.now().toString();
        logger.info("UserBackfillMigration complete: scanned={} updated={} skipped={} claimWriteFailures={}",
            scanned, updated, skipped, claimWriteFailures);
        return new RunSummary(scanned, updated, skipped, claimWriteFailures, startedAt, completedAt);
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
