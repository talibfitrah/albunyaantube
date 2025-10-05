package com.albunyaan.tube.service;

import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
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

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Log an admin action (async to not block request)
     */
    @Async
    public void log(String action, String entityType, String entityId, FirebaseUserDetails actor) {
        log(action, entityType, entityId, actor, null);
    }

    /**
     * Log an admin action with additional details (async)
     */
    @Async
    public void log(String action, String entityType, String entityId, FirebaseUserDetails actor, Map<String, Object> details) {
        try {
            AuditLog auditLog = new AuditLog(action, entityType, entityId, actor.getUid());
            auditLog.setActorDisplayName(actor.getEmail()); // Using email as display name for now

            if (details != null) {
                auditLog.setDetails(details);
            }

            auditLogRepository.save(auditLog);
            logger.debug("Audit log created: {} on {} by {}", action, entityType, actor.getUid());
        } catch (ExecutionException | InterruptedException e) {
            logger.error("Failed to create audit log: {} on {} by {}", action, entityType, actor.getUid(), e);
        }
    }

    /**
     * Log action with simple string actor (for system actions)
     */
    @Async
    public void logSystem(String action, String entityType, String entityId, String actorDescription) {
        try {
            AuditLog auditLog = new AuditLog(action, entityType, entityId, "system");
            auditLog.setActorDisplayName(actorDescription);
            auditLogRepository.save(auditLog);
            logger.debug("System audit log created: {} on {}", action, entityType);
        } catch (ExecutionException | InterruptedException e) {
            logger.error("Failed to create system audit log: {} on {}", action, entityType, e);
        }
    }

    /**
     * Log approval action (BACKEND-APPR-01)
     */
    @Async
    public void logApproval(String entityType, String entityId, String actorUid, String actorDisplayName, String notes) {
        try {
            AuditLog auditLog = new AuditLog(entityType + "_approved", entityType, entityId, actorUid);
            auditLog.setActorDisplayName(actorDisplayName);
            if (notes != null) {
                auditLog.addDetail("reviewNotes", notes);
            }
            auditLogRepository.save(auditLog);
            logger.debug("Approval audit log created: {} {} by {}", entityType, entityId, actorUid);
        } catch (ExecutionException | InterruptedException e) {
            logger.error("Failed to create approval audit log: {} {} by {}", entityType, entityId, actorUid, e);
        }
    }

    /**
     * Log rejection action (BACKEND-APPR-01)
     */
    @Async
    public void logRejection(String entityType, String entityId, String actorUid, String actorDisplayName, Map<String, Object> details) {
        try {
            AuditLog auditLog = new AuditLog(entityType + "_rejected", entityType, entityId, actorUid);
            auditLog.setActorDisplayName(actorDisplayName);
            if (details != null) {
                auditLog.setDetails(details);
            }
            auditLogRepository.save(auditLog);
            logger.debug("Rejection audit log created: {} {} by {}", entityType, entityId, actorUid);
        } catch (ExecutionException | InterruptedException e) {
            logger.error("Failed to create rejection audit log: {} {} by {}", entityType, entityId, actorUid, e);
        }
    }
}
