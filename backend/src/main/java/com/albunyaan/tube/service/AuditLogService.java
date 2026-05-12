package com.albunyaan.tube.service;

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

    public AuditLogService(AuditLogRepository auditLogRepository, Firestore firestore) {
        this.auditLogRepository = auditLogRepository;
        this.firestore = firestore;
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

    public com.albunyaan.tube.dto.PaginatedAuditLog findPaginated(
            String actorUid, String action, int limit, String cursor)
            throws java.util.concurrent.ExecutionException, InterruptedException {
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
                    .document(c.docId()).get().get();
            if (!snap.exists()) {
                throw new IllegalArgumentException("Cursor references missing document");
            }
            q = q.startAfter(snap);
        }

        com.google.cloud.firestore.QuerySnapshot snapAll = q.limit(effLimit + 1).get().get();
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
}

