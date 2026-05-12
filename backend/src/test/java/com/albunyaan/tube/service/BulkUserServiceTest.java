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

    private static User userWithRole(String role) {
        User u = new User();
        u.setRole(role);
        return u;
    }
}
