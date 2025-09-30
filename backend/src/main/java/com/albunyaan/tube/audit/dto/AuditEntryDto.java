package com.albunyaan.tube.audit.dto;

import com.albunyaan.tube.audit.AuditAction;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditEntryDto(
    UUID id,
    AuditAction action,
    AuditActorDto actor,
    AuditEntityDto entity,
    Map<String, Object> metadata,
    OffsetDateTime createdAt,
    String traceId
) {}
