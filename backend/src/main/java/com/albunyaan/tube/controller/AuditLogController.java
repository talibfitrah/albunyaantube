package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * FIREBASE-MIGRATE-04: Audit Log Controller
 *
 * Endpoints for viewing audit logs.
 * Only admins can view audit logs.
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditLogController {

    private static final Logger log = LoggerFactory.getLogger(AuditLogController.class);

    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogRepository auditLogRepository, AuditLogService auditLogService) {
        this.auditLogRepository = auditLogRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Get recent audit logs
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<com.albunyaan.tube.dto.PaginatedAuditLog> getAuditLogs(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        try {
            return ResponseEntity.ok(auditLogService.findPaginated(null, null, limit, cursor));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("audit.list failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get audit logs by actor
     */
    @GetMapping("/actor/{actorUid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<com.albunyaan.tube.dto.PaginatedAuditLog> getAuditLogsByActor(
            @PathVariable String actorUid,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        try {
            return ResponseEntity.ok(auditLogService.findPaginated(actorUid, null, limit, cursor));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("audit.byActor failed actor={}", actorUid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get audit logs by entity type
     */
    @GetMapping("/entity-type/{entityType}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AuditLog>> getAuditLogsByEntityType(
            @PathVariable String entityType,
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<AuditLog> logs = auditLogRepository.findByEntityType(entityType, limit);
        return ResponseEntity.ok(logs);
    }

    /**
     * Get audit logs by action
     */
    @GetMapping("/action/{action}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<com.albunyaan.tube.dto.PaginatedAuditLog> getAuditLogsByAction(
            @PathVariable String action,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        try {
            return ResponseEntity.ok(auditLogService.findPaginated(null, action, limit, cursor));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("audit.byAction failed action={}", action, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

