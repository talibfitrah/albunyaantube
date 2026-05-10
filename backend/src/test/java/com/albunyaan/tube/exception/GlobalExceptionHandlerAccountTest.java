package com.albunyaan.tube.exception;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerAccountTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static WebRequest mockRequest(String uri) {
        WebRequest req = Mockito.mock(WebRequest.class);
        Mockito.when(req.getDescription(false)).thenReturn("uri=" + uri);
        return req;
    }

    @Test void blockedReturns403WithCodeAndReason() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) handler
            .handleAccountBlocked(new AccountBlockedException("u1", "spam"), mockRequest("/api/v1/me"))
            .getBody();
        assertEquals("ACCOUNT_BLOCKED", body.get("code"));
        assertEquals("spam", body.get("reason"));
        assertEquals("/api/v1/me", body.get("path"));
        assertEquals("Forbidden", body.get("error"));
        assertEquals(HttpStatus.FORBIDDEN.value(), body.get("status"));
    }

    @Test void blockedWithoutReason_omitsReasonField() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) handler
            .handleAccountBlocked(new AccountBlockedException("u1", null), mockRequest("/api/v1/me"))
            .getBody();
        assertFalse(body.containsKey("reason"));
        assertEquals("ACCOUNT_BLOCKED", body.get("code"));
    }

    @Test void deletedReturns401WithoutLeakingUid() {
        ResponseEntity<Object> r = handler
            .handleAccountDeleted(new AccountDeletedException("u1"), mockRequest("/api/v1/me"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertEquals("ACCOUNT_NOT_FOUND", body.get("code"));
        assertFalse(body.containsKey("uid"), "uid must NOT be in response body");
    }

    @Test void lastAdminReturns409Conflict() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) handler
            .handleLastAdmin(new LastAdminException("Cannot demote the last admin."), mockRequest("/api/admin/users/u1"))
            .getBody();
        assertEquals("LAST_ADMIN_PROTECTED", body.get("code"));
        assertEquals("Cannot demote the last admin.", body.get("message"));
        assertEquals(HttpStatus.CONFLICT.value(), body.get("status"));
    }
}
