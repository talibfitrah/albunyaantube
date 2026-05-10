package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuditLogService;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private Firestore firestore;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private FirestoreTimeoutProperties timeoutProperties;

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
                claims.get("role").equals("moderator")  // Role is converted to lowercase in implementation
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

    // updateUserRole_shouldUpdateRoleInFirebaseAndFirestore,
    // updateUserRole_shouldThrowException_whenUserNotFound, and
    // updateUserRole_shouldThrowException_whenFirebaseAuthFails were removed:
    // the old 2-arg updateUserRole() method was deleted in Task 8.
    // Transactional path coverage lives in AuthServiceLastAdminIntegrationTest.

    @Test
    void createUser_setsLowercaseRoleClaim() throws Exception {
        // Arrange
        when(mockUserRecord.getUid()).thenReturn("u1");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(mockUserRecord);
        doNothing().when(firebaseAuth).setCustomUserClaims(eq("u1"), any(Map.class));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        authService.createUser("e@t", "password123", "Test", "ADMIN", "actor");

        // Assert
        verify(firebaseAuth).setCustomUserClaims(eq("u1"), argThat(claims ->
                claims.get("role").equals("admin")
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateUserRoleAsActor_setsLowercaseRoleClaim() throws Exception {
        // Arrange: stub the Firestore transaction to return a User with the new role applied
        User u = new User("u1", "e@t", "Test", "user");
        u.setStatus("active");
        ApiFuture<User> txFuture = mock(ApiFuture.class);
        when(txFuture.get(anyLong(), any())).thenReturn(u);
        doReturn(txFuture).when(firestore).runTransaction(any());
        doNothing().when(firebaseAuth).setCustomUserClaims(eq("u1"), any(Map.class));
        when(cacheManager.getCache("userStatus")).thenReturn(null);
        when(timeoutProperties.getWrite()).thenReturn(10L);

        // Act
        authService.updateUserRoleAsActor("u1", "MODERATOR", "actor-uid");

        // Assert: claim written outside tx uses lowercase value from Role.fromString
        verify(firebaseAuth).setCustomUserClaims(eq("u1"), argThat(claims ->
                claims.get("role").equals("moderator")
        ));
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
    @SuppressWarnings("unchecked")
    void softDeleteUser_shouldDisableFirebaseAuthAndEvictCache() throws Exception {
        // Arrange: stub the Firestore transaction to complete successfully (real logic tested in integration test)
        ApiFuture<Void> doneFuture = mock(ApiFuture.class);
        when(doneFuture.get(anyLong(), any())).thenReturn(null);
        doReturn(doneFuture).when(firestore).runTransaction(any());

        when(firebaseAuth.updateUser(any())).thenReturn(null);
        doNothing().when(firebaseAuth).revokeRefreshTokens(any());

        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache("userStatus")).thenReturn(mockCache);
        when(timeoutProperties.getWrite()).thenReturn(10L);

        // Act
        authService.softDeleteUser("test-uid", "admin-uid", "policy-violation");

        // Assert: Firebase Auth disabled + tokens revoked after transaction
        verify(firebaseAuth).updateUser(any());
        verify(firebaseAuth).revokeRefreshTokens("test-uid");
        verify(mockCache).evict("test-uid");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoverUser_shouldEnableFirebaseAuthAndEvictCache() throws Exception {
        // Arrange: stub the Firestore transaction to complete successfully
        ApiFuture<Void> doneFuture = mock(ApiFuture.class);
        when(doneFuture.get(anyLong(), any())).thenReturn(null);
        doReturn(doneFuture).when(firestore).runTransaction(any());

        when(firebaseAuth.updateUser(any())).thenReturn(null);

        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache("userStatus")).thenReturn(mockCache);
        when(timeoutProperties.getWrite()).thenReturn(10L);

        // Act
        authService.recoverUser("test-uid", "admin-uid");

        // Assert: Firebase Auth re-enabled after transaction
        verify(firebaseAuth).updateUser(any());
        verify(mockCache).evict("test-uid");
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

