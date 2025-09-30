package com.albunyaan.tube.admin;

import com.albunyaan.tube.audit.AuditLogService;
import com.albunyaan.tube.audit.dto.AuditEntryDto;
import com.albunyaan.tube.registry.dto.CursorPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/admins/audit", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<CursorPage<AuditEntryDto>> listAuditEntries(
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        var page = auditLogService.listEntries(cursor, limit);
        return ResponseEntity.ok(page);
    }
}
