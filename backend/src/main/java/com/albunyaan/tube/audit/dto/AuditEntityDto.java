package com.albunyaan.tube.audit.dto;

public record AuditEntityDto(
    String type,
    String id,
    String slug
) {}
