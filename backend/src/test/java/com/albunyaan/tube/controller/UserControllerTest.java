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
    void getAllUsers_shouldReturnAllUsers() throws ExecutionException, InterruptedException {
        // Arrange
        List<User> users = Arrays.asList(testAdmin, testModerator);
        when(userRepository.findAll()).thenReturn(users);

        // Act
        ResponseEntity<List<User>> response = userController.getAllUsers();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
        verify(userRepository).findAll();
    }

    @Test
    void getUserByUid_shouldReturnUser_whenExists() throws ExecutionException, InterruptedException {
        // Arrange
        when(userRepository.findByUid("test-admin-uid")).thenReturn(Optional.of(testAdmin));

        // Act
        ResponseEntity<User> response = userController.getUserByUid("test-admin-uid");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("admin@example.com", response.getBody().getEmail());
        verify(userRepository).findByUid("test-admin-uid");
    }

    @Test
    void getUserByUid_shouldReturn404_whenNotFound() throws ExecutionException, InterruptedException {
        // Arrange
        when(userRepository.findByUid("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<User> response = userController.getUserByUid("nonexistent");

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(userRepository).findByUid("nonexistent");
    }

    @Test
    void getUsersByRole_shouldReturnUsersWithRole() throws ExecutionException, InterruptedException {
        // Arrange
        List<User> admins = Arrays.asList(testAdmin);
        when(userRepository.findByRole("admin")).thenReturn(admins);

        // Act
        ResponseEntity<List<User>> response = userController.getUsersByRole("admin");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("admin", response.getBody().get(0).getRole());
        verify(userRepository).findByRole("admin");
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
    void updateUserRole_shouldUpdateRole_andLogAudit() throws Exception {
        // Arrange
        UserController.UpdateRoleRequest request = new UserController.UpdateRoleRequest();
        request.role = "admin";

        User updatedUser = new User("test-mod-uid", "mod@example.com", "Test Moderator", "admin");
        when(authService.updateUserRole("test-mod-uid", "admin")).thenReturn(updatedUser);

        // Act
        ResponseEntity<User> response = userController.updateUserRole("test-mod-uid", request, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("admin", response.getBody().getRole());
        verify(authService).updateUserRole("test-mod-uid", "admin");
        verify(auditLogService).log(eq("user_role_updated"), eq("user"), eq("test-mod-uid"), eq(adminUser));
    }

    @Test
    void updateUserRole_shouldReturnBadRequest_whenFirebaseAuthFails() throws Exception {
        // Arrange
        UserController.UpdateRoleRequest request = new UserController.UpdateRoleRequest();
        request.role = "admin";

        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        when(authService.updateUserRole(any(), any()))
                .thenThrow(mockException);

        // Act
        ResponseEntity<User> response = userController.updateUserRole("nonexistent", request, adminUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void updateUserStatus_shouldUpdateStatus_andLogAudit() throws Exception {
        // Arrange
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.status = "inactive";

        User updatedUser = new User("test-mod-uid", "mod@example.com", "Test Moderator", "moderator");
        updatedUser.setStatus("inactive");
        when(authService.updateUserStatus("test-mod-uid", "inactive")).thenReturn(updatedUser);

        // Act
        ResponseEntity<User> response = userController.updateUserStatus("test-mod-uid", request, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("inactive", response.getBody().getStatus());
        verify(authService).updateUserStatus("test-mod-uid", "inactive");
        verify(auditLogService).log(eq("user_status_updated"), eq("user"), eq("test-mod-uid"), eq(adminUser));
    }

    @Test
    void updateUserStatus_shouldReturnBadRequest_whenFirebaseAuthFails() throws Exception {
        // Arrange
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.status = "inactive";

        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        when(authService.updateUserStatus(any(), any()))
                .thenThrow(mockException);

        // Act
        ResponseEntity<User> response = userController.updateUserStatus("nonexistent", request, adminUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void deleteUser_shouldDeleteUser_andLogAudit() throws Exception {
        // Arrange
        doNothing().when(authService).deleteUser("test-mod-uid");

        // Act
        ResponseEntity<Void> response = userController.deleteUser("test-mod-uid", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(authService).deleteUser("test-mod-uid");
        verify(auditLogService).log(eq("user_deleted"), eq("user"), eq("test-mod-uid"), eq(adminUser));
    }

    @Test
    void deleteUser_shouldReturnBadRequest_whenFirebaseAuthFails() throws Exception {
        // Arrange
        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        doThrow(mockException)
                .when(authService).deleteUser("nonexistent");

        // Act
        ResponseEntity<Void> response = userController.deleteUser("nonexistent", adminUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(auditLogService, never()).log(any(), any(), any(), any());
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
