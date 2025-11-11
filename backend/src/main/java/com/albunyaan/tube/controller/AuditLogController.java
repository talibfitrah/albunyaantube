package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.repository.AuditLogRepository;
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

    private final AuditLogRepository auditLogRepository;

    public AuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Get recent audit logs
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AuditLog>> getAuditLogs(
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<AuditLog> logs = auditLogRepository.findAll(limit);
        return ResponseEntity.ok(logs);
    }

    /**
     * Get audit logs by actor
     */
    @GetMapping("/actor/{actorUid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AuditLog>> getAuditLogsByActor(
            @PathVariable String actorUid,
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<AuditLog> logs = auditLogRepository.findByActor(actorUid, limit);
        return ResponseEntity.ok(logs);
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
    public ResponseEntity<List<AuditLog>> getAuditLogsByAction(
            @PathVariable String action,
            @RequestParam(defaultValue = "100") int limit
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<AuditLog> logs = auditLogRepository.findByAction(action, limit);
        return ResponseEntity.ok(logs);
    }
}

