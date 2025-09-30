package com.albunyaan.tube.audit.dto;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record AuditActorDto(
    UUID id,
    String email,
    String displayName,
    Set<String> roles,
    String status,
    OffsetDateTime lastLoginAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
