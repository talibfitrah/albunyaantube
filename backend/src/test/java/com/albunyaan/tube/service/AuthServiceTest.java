package com.albunyaan.tube.service;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService
 * Tests user operations including create, update role, update status, delete, and password reset
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private FirebaseAuth firebaseAuth;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private UserRecord mockUserRecord;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new User("test-uid", "test@example.com", "Test User", "moderator");
        testUser.setStatus("active");

        // Create mock UserRecord (stubbing moved to individual tests to avoid unnecessary stubbing warnings)
        mockUserRecord = mock(UserRecord.class);
    }

    @Test
    void createUser_shouldCreateUserInFirebaseAndFirestore() throws Exception {
        // Arrange
        when(mockUserRecord.getUid()).thenReturn("test-uid");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(mockUserRecord);
        doNothing().when(firebaseAuth).setCustomUserClaims(eq("test-uid"), any(Map.class));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        User createdUser = authService.createUser(
                "test@example.com",
                "password123",
                "Test User",
                "moderator",
                "admin-uid"
        );

        // Assert
        assertNotNull(createdUser);
        assertEquals("test-uid", createdUser.getUid());
        assertEquals("test@example.com", createdUser.getEmail());
        assertEquals("Test User", createdUser.getDisplayName());
        assertEquals("moderator", createdUser.getRole());
        assertEquals("admin-uid", createdUser.getCreatedBy());

        verify(firebaseAuth).createUser(any(UserRecord.CreateRequest.class));
        verify(firebaseAuth).setCustomUserClaims(eq("test-uid"), argThat(claims ->
                claims.get("role").equals("MODERATOR")  // Role is converted to uppercase in implementation
        ));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void createUser_shouldThrowException_whenFirebaseAuthFails() throws Exception {
        // Arrange
        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenThrow(mockException);

        // Act & Assert
        assertThrows(FirebaseAuthException.class, () ->
                authService.createUser(
                        "test@example.com",
                        "password123",
                        "Test User",
                        "moderator",
                        "admin-uid"
                )
        );

        verify(firebaseAuth).createUser(any(UserRecord.CreateRequest.class));
        verify(firebaseAuth, never()).setCustomUserClaims(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserRole_shouldUpdateRoleInFirebaseAndFirestore() throws Exception {
        // Arrange
        when(userRepository.findByUid("test-uid")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(firebaseAuth).setCustomUserClaims(eq("test-uid"), any(Map.class));

        // Act
        User updatedUser = authService.updateUserRole("test-uid", "admin");

        // Assert
        assertNotNull(updatedUser);
        assertEquals("admin", updatedUser.getRole());

        verify(firebaseAuth).setCustomUserClaims(eq("test-uid"), argThat(claims ->
                claims.get("role").equals("ADMIN")  // Role is converted to uppercase in implementation
        ));
        verify(userRepository).findByUid("test-uid");
        verify(userRepository).save(testUser);
    }

    @Test
    void updateUserRole_shouldThrowException_whenUserNotFound() throws Exception {
        // Arrange
        when(userRepository.findByUid("nonexistent")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                authService.updateUserRole("nonexistent", "admin")
        );

        verify(userRepository).findByUid("nonexistent");
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserRole_shouldThrowException_whenFirebaseAuthFails() throws Exception {
        // Arrange
        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        doThrow(mockException).when(firebaseAuth).setCustomUserClaims(any(), any());

        // Act & Assert
        assertThrows(FirebaseAuthException.class, () ->
                authService.updateUserRole("test-uid", "admin")
        );

        verify(firebaseAuth).setCustomUserClaims(any(), any());
        verify(userRepository, never()).findByUid(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserStatus_shouldDeactivateUser() throws Exception {
        // Arrange
        when(userRepository.findByUid("test-uid")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenReturn(mockUserRecord);

        // Act
        User updatedUser = authService.updateUserStatus("test-uid", "inactive");

        // Assert
        assertNotNull(updatedUser);
        assertEquals("inactive", updatedUser.getStatus());

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        verify(userRepository).findByUid("test-uid");
        verify(userRepository).save(testUser);
    }

    @Test
    void updateUserStatus_shouldActivateUser() throws Exception {
        // Arrange
        testUser.setStatus("inactive");
        when(userRepository.findByUid("test-uid")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenReturn(mockUserRecord);

        // Act
        User updatedUser = authService.updateUserStatus("test-uid", "active");

        // Assert
        assertNotNull(updatedUser);
        assertEquals("active", updatedUser.getStatus());

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        verify(userRepository).findByUid("test-uid");
        verify(userRepository).save(testUser);
    }

    @Test
    void updateUserStatus_shouldThrowException_whenUserNotFound() throws Exception {
        // Arrange
        when(userRepository.findByUid("nonexistent")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                authService.updateUserStatus("nonexistent", "inactive")
        );

        verify(userRepository).findByUid("nonexistent");
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteUser_shouldDeleteFromFirebaseAndFirestore() throws Exception {
        // Arrange
        doNothing().when(firebaseAuth).deleteUser("test-uid");
        doNothing().when(userRepository).deleteByUid("test-uid");

        // Act
        authService.deleteUser("test-uid");

        // Assert
        verify(firebaseAuth).deleteUser("test-uid");
        verify(userRepository).deleteByUid("test-uid");
    }

    @Test
    void deleteUser_shouldThrowException_whenFirebaseAuthFails() throws Exception {
        // Arrange
        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        doThrow(mockException).when(firebaseAuth).deleteUser("test-uid");

        // Act & Assert
        assertThrows(FirebaseAuthException.class, () ->
                authService.deleteUser("test-uid")
        );

        verify(firebaseAuth).deleteUser("test-uid");
        verify(userRepository, never()).deleteByUid(any());
    }

    @Test
    void recordLogin_shouldUpdateLastLoginTimestamp() throws Exception {
        // Arrange
        when(userRepository.findByUid("test-uid")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        authService.recordLogin("test-uid");

        // Assert
        assertNotNull(testUser.getLastLoginAt());
        verify(userRepository).findByUid("test-uid");
        verify(userRepository).save(testUser);
    }

    @Test
    void recordLogin_shouldDoNothing_whenUserNotFound() throws Exception {
        // Arrange
        when(userRepository.findByUid("nonexistent")).thenReturn(Optional.empty());

        // Act
        authService.recordLogin("nonexistent");

        // Assert
        verify(userRepository).findByUid("nonexistent");
        verify(userRepository, never()).save(any());
    }

    @Test
    void sendPasswordResetEmail_shouldGenerateResetLink() throws Exception {
        // Arrange
        String resetLink = "https://firebase.app/reset?token=abc123";
        when(firebaseAuth.generatePasswordResetLink("test@example.com")).thenReturn(resetLink);

        // Act
        authService.sendPasswordResetEmail("test@example.com");

        // Assert
        verify(firebaseAuth).generatePasswordResetLink("test@example.com");
    }

    @Test
    void sendPasswordResetEmail_shouldThrowException_whenFirebaseAuthFails() throws Exception {
        // Arrange
        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        when(firebaseAuth.generatePasswordResetLink("test@example.com")).thenThrow(mockException);

        // Act & Assert
        assertThrows(FirebaseAuthException.class, () ->
                authService.sendPasswordResetEmail("test@example.com")
        );

        verify(firebaseAuth).generatePasswordResetLink("test@example.com");
    }

    @Test
    void emailExists_shouldReturnTrue_whenEmailExists() throws Exception {
        // Arrange
        when(firebaseAuth.getUserByEmail("test@example.com")).thenReturn(mockUserRecord);

        // Act
        boolean exists = authService.emailExists("test@example.com");

        // Assert
        assertTrue(exists);
        verify(firebaseAuth).getUserByEmail("test@example.com");
    }

    @Test
    void emailExists_shouldReturnFalse_whenEmailDoesNotExist() throws Exception {
        // Arrange
        FirebaseAuthException mockException = mock(FirebaseAuthException.class);
        when(firebaseAuth.getUserByEmail("nonexistent@example.com")).thenThrow(mockException);

        // Act
        boolean exists = authService.emailExists("nonexistent@example.com");

        // Assert
        assertFalse(exists);
        verify(firebaseAuth).getUserByEmail("nonexistent@example.com");
    }
}

