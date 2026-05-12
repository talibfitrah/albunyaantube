package com.albunyaan.tube.service;

import com.albunyaan.tube.security.FirebaseUserDetails;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceRevokeSessionsTest {

    @Test
    void revokeSessions_callsFirebase_andAuditsWithReason() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        AuditLogService auditLog = mock(AuditLogService.class);
        FirebaseUserDetails actor = new FirebaseUserDetails("admin-uid", "admin@fitrahtube.com", "admin");
        AuthService svc = AuthServiceTestFactory.with(firebaseAuth, auditLog);

        svc.revokeSessions("target-uid", actor, "user reported phishing");

        verify(firebaseAuth).revokeRefreshTokens("target-uid");
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).log(eq("USER_SESSIONS_REVOKED"), eq("user"), eq("target-uid"),
                eq(actor), details.capture());
        assertEquals("user reported phishing", details.getValue().get("reason"));
    }

    @Test
    void revokeSessions_nullReason_auditsWithoutReasonKey() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        AuditLogService auditLog = mock(AuditLogService.class);
        FirebaseUserDetails actor = new FirebaseUserDetails("admin-uid", "admin@fitrahtube.com", "admin");
        AuthService svc = AuthServiceTestFactory.with(firebaseAuth, auditLog);

        svc.revokeSessions("target-uid", actor, null);

        verify(firebaseAuth).revokeRefreshTokens("target-uid");
        verify(auditLog).log(eq("USER_SESSIONS_REVOKED"), eq("user"), eq("target-uid"),
                eq(actor),
                argThat(m -> !m.containsKey("reason") || m.get("reason") == null));
    }
}
