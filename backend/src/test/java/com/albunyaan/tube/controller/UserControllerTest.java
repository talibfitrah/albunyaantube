package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.AuthService;
import com.google.firebase.auth.FirebaseAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserController
 * Tests all 8 REST endpoints for user management
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthService authService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserController userController;

    private FirebaseUserDetails adminUser;
    private User testAdmin;
    private User testModerator;

    @BeforeEach
    void setUp() {
        // Create admin user for authentication
        adminUser = new FirebaseUserDetails("admin-uid", "admin@test.com", "admin");

        // Create test users
        testAdmin = new User("test-admin-uid", "admin@example.com", "Test Admin", "admin");
        testModerator = new User("test-mod-uid", "mod@example.com", "Test Moderator", "moderator");
        testModerator.setStatus("active");
    }

    @Test
    void getAllUsers_shouldReturnAllUsers() throws ExecutionException, InterruptedException, TimeoutException {
        // Arrange
        List<User> users = Arrays.asList(testAdmin, testModerator);
        when(userRepository.findAll(false)).thenReturn(users);

        // Act
        ResponseEntity<List<User>> response = userController.getAllUsers(false);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
        verify(userRepository).findAll(false);
    }

    @Test
    void getUserByUid_shouldReturnUser_whenExists() throws ExecutionException, InterruptedException, TimeoutException {
        // Arrange
        when(userRepository.findByUid("test-admin-uid")).thenReturn(Optional.of(testAdmin));

        // Act
        ResponseEntity<User> response = userController.getUserByUid("test-admin-uid", false);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("admin@example.com", response.getBody().getEmail());
        verify(userRepository).findByUid("test-admin-uid");
    }

    @Test
    void getUserByUid_shouldReturn404_whenNotFound() throws ExecutionException, InterruptedException, TimeoutException {
        // Arrange
        when(userRepository.findByUid("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<User> response = userController.getUserByUid("nonexistent", false);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(userRepository).findByUid("nonexistent");
    }

    // ── F11 — getUserByUid filters deleted users by default ──────────────────
    // Pre-F11 a soft-deleted user was still returned by /api/admin/users/{uid},
    // diverging from the GET /api/admin/users list filter. Now defaults to 404
    // for deleted users; includeDeleted=true exposes them.

    @Test
    void getUserByUid_returnsNotFound_forDeleted_byDefault() throws Exception {
        // Arrange: returns a soft-deleted user.
        User deleted = new User("u-del", "del@t.com", "Deleted", "moderator");
        deleted.setStatus("deleted");
        when(userRepository.findByUid("u-del")).thenReturn(Optional.of(deleted));

        // Act + Assert: default (includeDeleted=false) → 404.
        ResponseEntity<User> response = userController.getUserByUid("u-del", false);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Deleted users must return 404 by default");
    }

    @Test
    void getUserByUid_returnsDeleted_whenIncludeDeletedTrue() throws Exception {
        User deleted = new User("u-del", "del@t.com", "Deleted", "moderator");
        deleted.setStatus("deleted");
        when(userRepository.findByUid("u-del")).thenReturn(Optional.of(deleted));

        ResponseEntity<User> response = userController.getUserByUid("u-del", true);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "includeDeleted=true must allow deleted users through");
        assertEquals("del@t.com", response.getBody().getEmail());
    }

    @Test
    void getUsersByRole_shouldReturnUsersWithRole() throws ExecutionException, InterruptedException, TimeoutException {
        // Arrange
        List<User> admins = Arrays.asList(testAdmin);
        when(userRepository.findByRole("admin")).thenReturn(admins);

        // Act
        ResponseEntity<List<User>> response = userController.getUsersByRole("admin", false);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("admin", response.getBody().get(0).getRole());
        verify(userRepository).findByRole("admin");
    }

    // ── F9 — role path param is normalized to canonical lowercase ────────────
    // Pre-F9 a request to /role/ADMIN passed "ADMIN" straight to
    // findByRole(...), and Firestore (post-D6, all lowercase) returned zero
    // matches. The controller now canonicalises the path param.

    @Test
    void getUsersByRole_normalizesUppercasePathParam() throws Exception {
        // Arrange: the controller must normalise "ADMIN" → "admin" before query.
        List<User> admins = Arrays.asList(testAdmin);
        when(userRepository.findByRole("admin")).thenReturn(admins);

        // Act
        ResponseEntity<List<User>> response = userController.getUsersByRole("ADMIN", false);

        // Assert: same result as the lowercase call — proves normalization.
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(userRepository).findByRole("admin");
        verify(userRepository, never()).findByRole("ADMIN");
    }

    @Test
    void getUsersByRole_normalizesMixedCasePathParam() throws Exception {
        when(userRepository.findByRole("moderator")).thenReturn(Arrays.asList(testModerator));

        ResponseEntity<List<User>> response = userController.getUsersByRole("Moderator", false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(userRepository).findByRole("moderator");
    }

    @Test
    void getUsersByRole_unknownRoleFallsBackToUser() throws Exception {
        // Role.fromString returns Role.USER for unknown values (least-privilege).
        // Controller queries the "user" collection rather than the unknown string,
        // matching the existing list-filter behaviour.
        when(userRepository.findByRole("user")).thenReturn(List.of());

        ResponseEntity<List<User>> response = userController.getUsersByRole("super-admin", false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(userRepository).findByRole("user");
    }

    @Test
    void createUser_shouldCreateUser_andLogAudit() throws Exception {
        // Arrange
        UserController.CreateUserRequest request = new UserController.CreateUserRequest();
        request.email = "newuser@example.com";
        request.password = "password123";
        request.displayName = "New User";
        request.role = "moderator";

        User createdUser = new User("new-uid", "newuser@example.com", "New User", "moderator");
        when(authService.createUser(
                eq("newuser@example.com"),
                eq("password123"),
                eq("New User"),
                eq("moderator"),
                eq("admin-uid")
        )).thenReturn(createdUser);

        // Act
        ResponseEntity<User> response = userController.createUser(request, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("newuser@example.com", response.getBody().getEmail());
        verify(authService).createUser(
                "newuser@example.com",
                "password123",
                "New User",
                "moderator",
                "admin-uid"
        );
        verify(auditLogService).log(eq("user_created"), eq("user"), eq("new-uid"), eq(adminUser));
    }

    @Test
    void createUser_shouldReturnBadRequest_whenFirebaseAuthFails() throws Exception {
        // Arrange
        UserController.CreateUserRequest request = new UserController.CreateUserRequest();
        request.email = "invalid@example.com";
        request.password = "pass";
        request.displayName = "Invalid User";
        request.role = "moderator";

        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        when(authService.createUser(any(), any(), any(), any(), any()))
                .thenThrow(mockException);

        // Act
        ResponseEntity<User> response = userController.createUser(request, adminUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void createUser_shouldLogAuditFailure_butStillSucceed() throws Exception {
        // Arrange
        UserController.CreateUserRequest request = new UserController.CreateUserRequest();
        request.email = "newuser@example.com";
        request.password = "password123";
        request.displayName = "New User";
        request.role = "moderator";

        User createdUser = new User("new-uid", "newuser@example.com", "New User", "moderator");
        when(authService.createUser(any(), any(), any(), any(), any())).thenReturn(createdUser);
        doThrow(new RuntimeException("Audit log failed"))
                .when(auditLogService).log(any(), any(), any(), any());

        // Act
        ResponseEntity<User> response = userController.createUser(request, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("newuser@example.com", response.getBody().getEmail());
        verify(auditLogService).log(any(), any(), any(), any());
    }

    @Test
    void updateUserRole_shouldUpdateRole() throws Exception {
        // Arrange
        UserController.UpdateRoleRequest request = new UserController.UpdateRoleRequest();
        request.role = "admin";

        User updatedUser = new User("test-mod-uid", "mod@example.com", "Test Moderator", "admin");
        when(authService.updateUserRoleAsActor("test-mod-uid", "admin", "admin-uid")).thenReturn(updatedUser);

        // Act
        ResponseEntity<User> response = userController.updateUserRole("test-mod-uid", request, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("admin", response.getBody().getRole());
        verify(authService).updateUserRoleAsActor("test-mod-uid", "admin", "admin-uid");
    }

    @Test
    void updateUserRole_shouldReturn401_whenActorIsNull() throws Exception {
        // Arrange
        UserController.UpdateRoleRequest request = new UserController.UpdateRoleRequest();
        request.role = "admin";

        // Act
        ResponseEntity<User> response = userController.updateUserRole("test-mod-uid", request, null);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(authService, never()).updateUserRoleAsActor(any(), any(), any());
    }

    @Test
    void updateUserStatus_shouldDelegateToBlockingFlow_whenStatusBlocked() throws Exception {
        // Arrange
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.status = "blocked";
        request.reason = "policy";

        User updatedUser = new User("test-mod-uid", "mod@example.com", "Test Moderator", "moderator");
        updatedUser.setStatus("blocked");
        when(authService.updateUserStatus("test-mod-uid", "blocked", "admin-uid", "policy"))
                .thenReturn(updatedUser);

        // Act
        ResponseEntity<User> response = userController.updateUserStatus("test-mod-uid", request, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("blocked", response.getBody().getStatus());
        verify(authService).updateUserStatus("test-mod-uid", "blocked", "admin-uid", "policy");
    }

    @Test
    void updateUserStatus_shouldReturn401_whenActorIsNull() throws Exception {
        // Arrange
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.status = "blocked";
        request.reason = "policy";

        // Act
        ResponseEntity<User> response = userController.updateUserStatus("test-mod-uid", request, null);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(authService, never()).updateUserStatus(any(), any(), any(), any());
    }

    @Test
    void updateUserStatus_shouldPropagateIllegalArgumentException_forBadStatus() throws Exception {
        // Arrange
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.status = "inactive"; // legacy value — no longer accepted
        when(authService.updateUserStatus(eq("test-mod-uid"), eq("inactive"), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid status: inactive"));

        // Act + Assert: exception propagates so GlobalExceptionHandler maps it to 400
        assertThrows(IllegalArgumentException.class,
                () -> userController.updateUserStatus("test-mod-uid", request, adminUser));
    }

    @Test
    void deleteUser_shouldSoftDeleteUser_andReturn204() throws Exception {
        // Arrange
        doNothing().when(authService).softDeleteUser(eq("test-mod-uid"), eq("admin-uid"), eq("admin-action"));

        // Act
        ResponseEntity<Void> response = userController.deleteUser("test-mod-uid", adminUser, "admin-action");

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(authService).softDeleteUser("test-mod-uid", "admin-uid", "admin-action");
    }

    @Test
    void deleteUser_shouldReturn401_whenActorIsNull() throws Exception {
        // Act
        ResponseEntity<Void> response = userController.deleteUser("test-mod-uid", null, "admin-action");

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(authService, never()).softDeleteUser(any(), any(), any());
    }

    @Test
    void recoverUser_shouldRecoverUser_andReturn204() throws Exception {
        // Arrange
        doNothing().when(authService).recoverUser(eq("test-mod-uid"), eq("admin-uid"));

        // Act
        ResponseEntity<Void> response = userController.recoverUser("test-mod-uid", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(authService).recoverUser("test-mod-uid", "admin-uid");
    }

    @Test
    void sendPasswordReset_shouldSendEmail_andLogAudit() throws Exception {
        // Arrange
        when(userRepository.findByUid("test-mod-uid")).thenReturn(Optional.of(testModerator));
        doNothing().when(authService).sendPasswordResetEmail("mod@example.com");

        // Act
        ResponseEntity<Void> response = userController.sendPasswordReset("test-mod-uid", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(userRepository).findByUid("test-mod-uid");
        verify(authService).sendPasswordResetEmail("mod@example.com");
        verify(auditLogService).log(eq("user_password_reset"), eq("user"), eq("test-mod-uid"), eq(adminUser));
    }

    @Test
    void sendPasswordReset_shouldReturn404_whenUserNotFound() throws Exception {
        // Arrange
        when(userRepository.findByUid("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Void> response = userController.sendPasswordReset("nonexistent", adminUser);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(authService, never()).sendPasswordResetEmail(any());
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void sendPasswordReset_shouldReturnBadRequest_whenFirebaseAuthFails() throws Exception {
        // Arrange
        when(userRepository.findByUid("test-mod-uid")).thenReturn(Optional.of(testModerator));
        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        doThrow(mockException)
                .when(authService).sendPasswordResetEmail("mod@example.com");

        // Act
        ResponseEntity<Void> response = userController.sendPasswordReset("test-mod-uid", adminUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }
}

