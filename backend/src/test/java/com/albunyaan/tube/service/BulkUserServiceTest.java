package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BulkUserActionResult;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkUserServiceTest {

    private static final FirebaseUserDetails ADMIN_ACTOR =
            new FirebaseUserDetails("admin-uid", "admin@fitrahtube.com", "admin");

    @Test
    void happyPath_block_threeUsersSucceed() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid(anyString())).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("u1", "u2", "u3"),
                ADMIN_ACTOR,
                "policy violation");

        assertEquals(List.of("u1", "u2", "u3"), result.getSuccesses());
        assertTrue(result.getFailures().isEmpty());

        verify(authService).blockUser("u1", "admin-uid", "policy violation");
        verify(authService).blockUser("u2", "admin-uid", "policy violation");
        verify(authService).blockUser("u3", "admin-uid", "policy violation");

        verify(auditLog).log(
                eq("USER_BULK_ACTION"),
                eq("user"), eq("(batch)"),
                eq(ADMIN_ACTOR),
                argThat(m -> "block".equals(m.get("action"))
                        && Integer.valueOf(3).equals(m.get("successes"))
                        && Integer.valueOf(0).equals(m.get("failures"))));
    }

    @Test
    void selfAction_isBucketedAsFailure_andDoesNotCallAuth() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("other-uid")).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("admin-uid", "other-uid"),
                ADMIN_ACTOR,
                null);

        assertEquals(List.of("other-uid"), result.getSuccesses());
        assertEquals(1, result.getFailures().size());
        assertEquals("admin-uid", result.getFailures().get(0).uid());
        assertEquals("self_action_forbidden", result.getFailures().get(0).reason());

        verify(authService, never()).blockUser(eq("admin-uid"), any(), any());
        verify(authService).blockUser("other-uid", "admin-uid", null);
    }

    @Test
    void blockAdminTarget_isRejected_withAdminTargetForbidden() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("admin-target")).thenReturn(Optional.of(userWithRole("admin")));
        when(userRepo.findByUid("user-target")).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("admin-target", "user-target"),
                ADMIN_ACTOR,
                null);

        assertEquals(List.of("user-target"), result.getSuccesses());
        assertEquals(1, result.getFailures().size());
        assertEquals("admin_target_forbidden", result.getFailures().get(0).reason());
        verify(authService, never()).blockUser(eq("admin-target"), any(), any());
    }

    @Test
    void recoverAdminTarget_isAllowed() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("admin-target")).thenReturn(Optional.of(userWithRole("admin")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.RECOVER,
                List.of("admin-target"),
                ADMIN_ACTOR,
                null);

        assertEquals(List.of("admin-target"), result.getSuccesses());
        assertTrue(result.getFailures().isEmpty());
        verify(authService).recoverUser("admin-target", "admin-uid");
    }

    private static User userWithRole(String role) {
        User u = new User();
        u.setRole(role);
        return u;
    }
}
