package com.albunyaan.tube.service;

import com.albunyaan.tube.model.AuditLog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogServiceBuildersTest {

    private final AuditLogService svc = new AuditLogService(null /* repo */, null /* firestore */, null /* timeouts */);

    @Test
    void buildBlock_setsActionAndReason() {
        AuditLog log = svc.buildBlock("u1", "actor", "spam");
        assertEquals("USER_BLOCKED", log.getAction());
        assertEquals("user", log.getEntityType());
        assertEquals("u1", log.getEntityId());
        assertEquals("actor", log.getActorUid());
        assertEquals("spam", log.getDetails().get("reason"));
        assertNotNull(log.getTimestamp());
    }

    @Test
    void buildUnblock_omitsReason() {
        AuditLog log = svc.buildUnblock("u1", "actor");
        assertEquals("USER_UNBLOCKED", log.getAction());
        assertNull(log.getDetails().get("reason"));
    }

    @Test
    void buildSoftDelete_setsActionAndReason() {
        AuditLog log = svc.buildSoftDelete("u1", "actor", "user-request");
        assertEquals("USER_SOFT_DELETED", log.getAction());
        assertEquals("user-request", log.getDetails().get("reason"));
    }

    @Test
    void buildRecover_simple() {
        AuditLog log = svc.buildRecover("u1", "actor");
        assertEquals("USER_RECOVERED", log.getAction());
    }

    @Test
    void buildRoleChange_capturesFromTo() {
        AuditLog log = svc.buildRoleChange("u1", "actor", "moderator", "admin");
        assertEquals("USER_ROLE_CHANGED", log.getAction());
        assertEquals("moderator", log.getDetails().get("fromRole"));
        assertEquals("admin", log.getDetails().get("toRole"));
    }
}
