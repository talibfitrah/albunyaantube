package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FIREBASE-MIGRATE-04: Audit Log Service
 *
 * Service for logging admin actions asynchronously.
 */
@Service
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public AuditLogService(AuditLogRepository auditLogRepository,
                           Firestore firestore,
                           FirestoreTimeoutProperties timeoutProperties) {
        this.auditLogRepository = auditLogRepository;
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    /**
     * Log an admin action (async to not block request)
     */
    @Async("auditExecutor")
    public void log(String action, String entityType, String entityId, FirebaseUserDetails actor) {
        log(action, entityType, entityId, actor, null);
    }

    /**
     * Log an admin action with additional details (async)
     */
    @Async("auditExecutor")
    public void log(String action, String entityType, String entityId, FirebaseUserDetails actor, Map<String, Object> details) {
        try {
            AuditLog auditLog = new AuditLog(action, entityType, entityId, actor.getUid());
            auditLog.setActorDisplayName(actor.getEmail()); // Using email as display name for now

            if (details != null) {
                auditLog.setDetails(details);
            }

            auditLogRepository.save(auditLog);
            logger.debug("Audit log created: {} on {} by {}", action, entityType, actor.getUid());
        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            logger.error("Failed to create audit log: {} on {} by {}", action, entityType, actor.getUid(), e);
        }
    }

    /**
     * Log action with simple string actor (for system actions)
     */
    @Async("auditExecutor")
    public void logSystem(String action, String entityType, String entityId, String actorDescription) {
        try {
            AuditLog auditLog = new AuditLog(action, entityType, entityId, "system");
            auditLog.setActorDisplayName(actorDescription);
            auditLogRepository.save(auditLog);
            logger.debug("System audit log created: {} on {}", action, entityType);
        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            logger.error("Failed to create system audit log: {} on {}", action, entityType, e);
        }
    }

    /**
     * Log approval action (BACKEND-APPR-01)
     */
    @Async("auditExecutor")
    public void logApproval(String entityType, String entityId, String actorUid, String actorDisplayName, String notes) {
        try {
            AuditLog auditLog = new AuditLog(entityType + "_approved", entityType, entityId, actorUid);
            auditLog.setActorDisplayName(actorDisplayName);
            if (notes != null) {
                auditLog.addDetail("reviewNotes", notes);
            }
            auditLogRepository.save(auditLog);
            logger.debug("Approval audit log created: {} {} by {}", entityType, entityId, actorUid);
        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            logger.error("Failed to create approval audit log: {} {} by {}", entityType, entityId, actorUid, e);
        }
    }

    /**
     * Log rejection action (BACKEND-APPR-01)
     */
    @Async("auditExecutor")
    public void logRejection(String entityType, String entityId, String actorUid, String actorDisplayName, Map<String, Object> details) {
        try {
            AuditLog auditLog = new AuditLog(entityType + "_rejected", entityType, entityId, actorUid);
            auditLog.setActorDisplayName(actorDisplayName);
            if (details != null) {
                auditLog.setDetails(details);
            }
            auditLogRepository.save(auditLog);
            logger.debug("Rejection audit log created: {} {} by {}", entityType, entityId, actorUid);
        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            logger.error("Failed to create rejection audit log: {} {} by {}", entityType, entityId, actorUid, e);
        }
    }

    /**
     * Cubic R5 P1 — REQUEST_CHANGES has its own audit action.
     *
     * Previously the requestChanges* paths reused {@link #logRejection}, so a
     * "needs changes" review showed up as {@code *_rejected} in the audit log.
     * Downstream dashboards and any reviewer query filtering on
     * {@code action="*_rejected"} treated changes-requested events as
     * rejections — false positives that polluted incident timelines and the
     * moderator KPI tables.
     */
    @Async("auditExecutor")
    public void logChangesRequested(String entityType, String entityId, String actorUid,
                                    String actorDisplayName, Map<String, Object> details) {
        try {
            AuditLog auditLog = new AuditLog(entityType + "_changes_requested", entityType, entityId, actorUid);
            auditLog.setActorDisplayName(actorDisplayName);
            if (details != null) {
                auditLog.setDetails(details);
            }
            auditLogRepository.save(auditLog);
            logger.debug("Changes-requested audit log created: {} {} by {}", entityType, entityId, actorUid);
        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            logger.error("Failed to create changes-requested audit log: {} {} by {}", entityType, entityId, actorUid, e);
        }
    }

    /**
     * Builder for USER_BLOCKED action log.
     * Used in transactional user blocking (Task 7).
     * Does NOT persist — caller must use tx.set() within AuthService transaction.
     */
    public AuditLog buildBlock(String targetUid, String actorUid, String reason) {
        return AuditLog.of("USER_BLOCKED", "user", targetUid, actorUid,
            reason != null ? Map.of("reason", reason) : Map.of());
    }

    /**
     * Builder for USER_UNBLOCKED action log.
     * Used in transactional user unblocking (Task 7).
     * Does NOT persist — caller must use tx.set() within AuthService transaction.
     */
    public AuditLog buildUnblock(String targetUid, String actorUid) {
        return AuditLog.of("USER_UNBLOCKED", "user", targetUid, actorUid, Map.of());
    }

    /**
     * Builder for USER_SOFT_DELETED action log.
     * Used in transactional user soft delete (Task 6).
     * Does NOT persist — caller must use tx.set() within AuthService transaction.
     */
    public AuditLog buildSoftDelete(String targetUid, String actorUid, String reason) {
        return AuditLog.of("USER_SOFT_DELETED", "user", targetUid, actorUid,
            reason != null ? Map.of("reason", reason) : Map.of());
    }

    /**
     * Builder for USER_RECOVERED action log.
     * Used in transactional user recovery (Task 6).
     * Does NOT persist — caller must use tx.set() within AuthService transaction.
     */
    public AuditLog buildRecover(String targetUid, String actorUid) {
        return AuditLog.of("USER_RECOVERED", "user", targetUid, actorUid, Map.of());
    }

    /**
     * Builder for USER_ROLE_CHANGED action log.
     * Used in transactional user role updates (Task 8).
     * Does NOT persist — caller must use tx.set() within AuthService transaction.
     */
    public AuditLog buildRoleChange(String targetUid, String actorUid,
                                    String fromRole, String toRole) {
        return AuditLog.of("USER_ROLE_CHANGED", "user", targetUid, actorUid,
            Map.of("fromRole", fromRole, "toRole", toRole));
    }

    /**
     * Builder for USER_BACKFILL_RUN action log (system action).
     * Used in user backfill migration (Task 12).
     * Does NOT persist — caller must use tx.set() within Firestore transaction.
     */
    public AuditLog buildBackfillRun(int scanned, int updated, int claimWriteFailures, String actorUid) {
        return AuditLog.of("USER_BACKFILL_RUN", "system", "user-backfill", actorUid,
            Map.of("scanned", scanned, "updated", updated, "claimWriteFailures", claimWriteFailures));
    }

    /**
     * Plan G B2 stub — full implementation lands in Task B4.
     *
     * <p>Logs a user-initiated profile edit. {@code diff} contains changed-field
     * entries; dateOfBirth is recorded as "changed" (no raw value) to avoid
     * logging PII. Called synchronously from {@code AccountProfileService.updateProfile}
     * so the audit row is guaranteed before the response returns (unlike the
     * async admin-action paths above which use {@code @Async("auditExecutor")}).
     *
     * <p>B4 will replace this stub with a proper Firestore write.
     */
    public void logProfileEdit(String actorUid, java.util.Map<String, Object> diff) {
        try {
            AuditLog auditLog = new AuditLog("PROFILE_EDIT", "user", actorUid, actorUid);
            if (diff != null) {
                auditLog.setDetails(diff);
            }
            auditLogRepository.save(auditLog);
            logger.debug("Profile-edit audit log created for uid={}", actorUid);
        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            logger.error("Failed to create profile-edit audit log for uid={}", actorUid, e);
        }
    }

    public com.albunyaan.tube.dto.PaginatedAuditLog findPaginated(
            String actorUid, String action, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        int effLimit = Math.min(Math.max(limit, 1), 200);
        com.google.cloud.firestore.Query q = firestore.collection("audit_logs")
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .orderBy(com.google.cloud.firestore.FieldPath.documentId(),
                        com.google.cloud.firestore.Query.Direction.DESCENDING);

        if (actorUid != null && !actorUid.isBlank()) q = q.whereEqualTo("actorUid", actorUid);
        if (action != null && !action.isBlank())     q = q.whereEqualTo("action", action);

        if (cursor != null && !cursor.isBlank()) {
            com.albunyaan.tube.util.AuditCursor.Decoded c =
                    com.albunyaan.tube.util.AuditCursor.decode(cursor);
            com.google.cloud.firestore.DocumentSnapshot snap = firestore.collection("audit_logs")
                    .document(c.docId())
                    .get()
                    .get(timeoutProperties.getRead(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                // Stale cursor (doc archived/pruned). Reset to first page rather
                // than 400 — a long-lived admin tab shouldn't hard-fail when a
                // referenced audit row disappears; the next page query without
                // cursor returns the freshest rows. The returned page mints its
                // own nextCursor from the last row so pagination continues
                // rather than appearing to end.
                //
                // Cubic R6 P2 — no raw docId in the warn line; pre-fix that
                // turned the log into an oracle.
                // Cubic R7 P2 — uniform warn wording across all stale-cursor
                // branches (missing-doc, actorUid-mismatch, action-mismatch)
                // so the warn pattern itself is not an oracle for which
                // filter the cursor failed against. See branch below.
                logger.warn("Audit cursor invalid; resetting to first page");
                return findFirstPageWithCursor(actorUid, action, effLimit);
            }
            // Cubic R6 P2 — close the cross-filter existence-oracle.
            //
            // Pre-fix: the {@code firestore.document(c.docId()).get()} above
            // fetched ANY audit_logs doc by id without applying the request's
            // {@code actorUid}/{@code action} filters. {@code startAfter(snap)}
            // then narrowed the page result to the filtered set, but the
            // initial fetch leaked "does docId X exist in audit_logs" — and
            // (via the snap-vs-filter divergence) "does docId X match these
            // filters". One admin could craft cursors to probe audit rows
            // outside the filter views they're entitled to.
            //
            // Post-fix: require the snap to satisfy the same predicates as
            // the page query. If it does not, drop into the same first-page
            // fallback as the not-exists branch — so the response shape is
            // identical for {not-exists} ∪ {exists-but-mismatched-filter},
            // collapsing the side-channel. The admin learns nothing new about
            // docs outside their filter scope.
            // Cubic R7 P2 — uniform warn ("Audit cursor invalid") across all
            // three stale-cursor branches collapses the side channel: an
            // attacker watching info-level logs cannot distinguish
            // missing-doc vs actorUid-mismatch vs action-mismatch and so
            // cannot probe whether docId X exists under a filter view they
            // are not entitled to.
            if (actorUid != null && !actorUid.isBlank()
                    && !actorUid.equals(snap.getString("actorUid"))) {
                logger.warn("Audit cursor invalid; resetting to first page");
                return findFirstPageWithCursor(actorUid, action, effLimit);
            }
            if (action != null && !action.isBlank()
                    && !action.equals(snap.getString("action"))) {
                logger.warn("Audit cursor invalid; resetting to first page");
                return findFirstPageWithCursor(actorUid, action, effLimit);
            }
            // F8 sanity check: encoded ts should match the stored timestamp on the
            // referenced doc. Drift here means either the doc was rewritten or the
            // cursor was tampered with — log a warning so operators can spot it,
            // but don't fail the page (the docId is the authoritative tiebreak).
            //
            // Both sides are truncated to millisecond precision before comparison:
            // Firestore Timestamp.toDate() rounds to milliseconds, but the encode
            // fallback `: Instant.now()` mints nanoseconds. Comparing nanos to millis
            // would log spurious "drift" on every cursor minted down that fallback.
            com.google.cloud.Timestamp storedTs = snap.getTimestamp("timestamp");
            if (storedTs != null && c.ts() != null) {
                java.time.Instant storedMs = storedTs.toDate().toInstant()
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                java.time.Instant cursorMs = c.ts()
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                if (!storedMs.equals(cursorMs)) {
                    logger.warn("Audit cursor ts drift: cursorTs={} storedTs={} docId={}",
                            cursorMs, storedMs, c.docId());
                }
            }
            q = q.startAfter(snap);
        }

        com.google.cloud.firestore.QuerySnapshot snapAll = q.limit(effLimit + 1)
                .get()
                .get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        var docs = snapAll.getDocuments();
        java.util.List<com.albunyaan.tube.model.AuditLog> rows =
                docs.stream()
                    .limit(effLimit)
                    .map(d -> d.toObject(com.albunyaan.tube.model.AuditLog.class))
                    .toList();

        boolean hasMore = docs.size() > effLimit;
        String nextCursor = null;
        if (hasMore) {
            var lastDoc = docs.get(effLimit - 1);
            com.albunyaan.tube.model.AuditLog last = lastDoc.toObject(com.albunyaan.tube.model.AuditLog.class);
            java.time.Instant ts = last.getTimestamp() != null
                    ? last.getTimestamp().toDate().toInstant()
                    : java.time.Instant.now();
            nextCursor = com.albunyaan.tube.util.AuditCursor.encode(ts, lastDoc.getId());
        }
        return new com.albunyaan.tube.dto.PaginatedAuditLog(rows, nextCursor);
    }

    /**
     * Stale-cursor fallback for {@link #findPaginated}: re-runs the same query
     * without any startAfter, returning at most {@code effLimit} rows plus a
     * fresh {@code nextCursor} computed from the last row so pagination
     * continues. Used when the cursor's referenced doc no longer exists so
     * admin UIs degrade gracefully into a first-page view instead of a 400
     * but still expose subsequent pages.
     */
    private com.albunyaan.tube.dto.PaginatedAuditLog findFirstPageWithCursor(
            String actorUid, String action, int effLimit)
            throws ExecutionException, InterruptedException, TimeoutException {
        com.google.cloud.firestore.Query q = firestore.collection("audit_logs")
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .orderBy(com.google.cloud.firestore.FieldPath.documentId(),
                        com.google.cloud.firestore.Query.Direction.DESCENDING);
        if (actorUid != null && !actorUid.isBlank()) q = q.whereEqualTo("actorUid", actorUid);
        if (action != null && !action.isBlank())     q = q.whereEqualTo("action", action);
        com.google.cloud.firestore.QuerySnapshot snap = q.limit(effLimit + 1)
                .get()
                .get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        var docs = snap.getDocuments();
        java.util.List<com.albunyaan.tube.model.AuditLog> rows = docs.stream()
                .limit(effLimit)
                .map(d -> d.toObject(com.albunyaan.tube.model.AuditLog.class))
                .toList();
        String nextCursor = null;
        if (docs.size() > effLimit) {
            var lastDoc = docs.get(effLimit - 1);
            com.albunyaan.tube.model.AuditLog last = lastDoc.toObject(com.albunyaan.tube.model.AuditLog.class);
            java.time.Instant ts = last.getTimestamp() != null
                    ? last.getTimestamp().toDate().toInstant()
                    : java.time.Instant.now();
            nextCursor = com.albunyaan.tube.util.AuditCursor.encode(ts, lastDoc.getId());
        }
        return new com.albunyaan.tube.dto.PaginatedAuditLog(rows, nextCursor);
    }
}

