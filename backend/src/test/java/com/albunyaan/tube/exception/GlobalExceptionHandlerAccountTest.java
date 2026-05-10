package com.albunyaan.tube.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerAccountTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test void blockedReturns403WithCode() {
        ResponseEntity<Map<String, Object>> r = handler.handleAccountBlocked(
            new AccountBlockedException("u1", "spam"));
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals("ACCOUNT_BLOCKED", r.getBody().get("code"));
        assertEquals("spam", r.getBody().get("reason"));
    }

    @Test void deletedReturns401WithoutLeakingDetails() {
        ResponseEntity<Map<String, Object>> r = handler.handleAccountDeleted(
            new AccountDeletedException("u1"));
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertEquals("ACCOUNT_NOT_FOUND", r.getBody().get("code"));
        assertFalse(r.getBody().containsKey("uid"));
    }

    @Test void lastAdminReturns409Conflict() {
        ResponseEntity<Map<String, Object>> r = handler.handleLastAdmin(
            new LastAdminException("Cannot demote the last admin."));
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("LAST_ADMIN_PROTECTED", r.getBody().get("code"));
    }
}
