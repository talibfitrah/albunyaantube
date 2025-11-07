package com.albunyaan.tube.repository;

import com.albunyaan.tube.model.User;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserRepository
 * Tests CRUD operations and queries using mocked Firestore
 */
@ExtendWith(MockitoExtension.class)
class UserRepositoryTest {

    @Mock
    private Firestore firestore;

    @Mock
    private CollectionReference collectionReference;

    @Mock
    private DocumentReference documentReference;

    @Mock
    private Query query;

    @Mock
    private ApiFuture<WriteResult> writeResultFuture;

    @Mock
    private ApiFuture<DocumentSnapshot> documentSnapshotFuture;

    @Mock
    private ApiFuture<QuerySnapshot> querySnapshotFuture;

    @Mock
    private DocumentSnapshot documentSnapshot;

    @Mock
    private QuerySnapshot querySnapshot;

    @InjectMocks
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("test-uid", "test@example.com", "Test User", "moderator");
        testUser.setStatus("active");

        // Setup basic Firestore mocking
        when(firestore.collection("users")).thenReturn(collectionReference);
    }

    @Test
    void save_shouldSaveUserToFirestore() throws Exception {
        // Arrange
        when(collectionReference.document("test-uid")).thenReturn(documentReference);
        when(documentReference.set(any(User.class))).thenReturn(writeResultFuture);
        when(writeResultFuture.get()).thenReturn(mock(WriteResult.class));

        // Act
        User savedUser = userRepository.save(testUser);

        // Assert
        assertNotNull(savedUser);
        assertEquals("test-uid", savedUser.getUid());
        assertNotNull(savedUser.getUpdatedAt()); // touch() should update timestamp

        verify(collectionReference).document("test-uid");
        verify(documentReference).set(testUser);
    }

    @Test
    void findByUid_shouldReturnUser_whenExists() throws Exception {
        // Arrange
        when(collectionReference.document("test-uid")).thenReturn(documentReference);
        when(documentReference.get()).thenReturn(documentSnapshotFuture);
        when(documentSnapshotFuture.get()).thenReturn(documentSnapshot);
        when(documentSnapshot.toObject(User.class)).thenReturn(testUser);

        // Act
        Optional<User> result = userRepository.findByUid("test-uid");

        // Assert
        assertTrue(result.isPresent());
        assertEquals("test-uid", result.get().getUid());
        assertEquals("test@example.com", result.get().getEmail());

        verify(collectionReference).document("test-uid");
        verify(documentReference).get();
    }

    @Test
    void findByUid_shouldReturnEmpty_whenNotFound() throws Exception {
        // Arrange
        when(collectionReference.document("nonexistent")).thenReturn(documentReference);
        when(documentReference.get()).thenReturn(documentSnapshotFuture);
        when(documentSnapshotFuture.get()).thenReturn(documentSnapshot);
        when(documentSnapshot.toObject(User.class)).thenReturn(null);

        // Act
        Optional<User> result = userRepository.findByUid("nonexistent");

        // Assert
        assertFalse(result.isPresent());

        verify(collectionReference).document("nonexistent");
    }

    @Test
    void findByEmail_shouldReturnUser_whenExists() throws Exception {
        // Arrange
        when(collectionReference.whereEqualTo("email", "test@example.com")).thenReturn(query);
        when(query.limit(1)).thenReturn(query);
        when(query.get()).thenReturn(querySnapshotFuture);
        when(querySnapshotFuture.get()).thenReturn(querySnapshot);
        when(querySnapshot.toObjects(User.class)).thenReturn(Arrays.asList(testUser));

        // Act
        Optional<User> result = userRepository.findByEmail("test@example.com");

        // Assert
        assertTrue(result.isPresent());
        assertEquals("test@example.com", result.get().getEmail());

        verify(collectionReference).whereEqualTo("email", "test@example.com");
        verify(query).limit(1);
        verify(query).get();
    }

    @Test
    void findByEmail_shouldReturnEmpty_whenNotFound() throws Exception {
        // Arrange
        when(collectionReference.whereEqualTo("email", "nonexistent@example.com")).thenReturn(query);
        when(query.limit(1)).thenReturn(query);
        when(query.get()).thenReturn(querySnapshotFuture);
        when(querySnapshotFuture.get()).thenReturn(querySnapshot);
        when(querySnapshot.toObjects(User.class)).thenReturn(Arrays.asList());

        // Act
        Optional<User> result = userRepository.findByEmail("nonexistent@example.com");

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void findAll_shouldReturnAllUsers() throws Exception {
        // Arrange
        User user2 = new User("test-uid-2", "test2@example.com", "Test User 2", "admin");
        List<User> users = Arrays.asList(testUser, user2);

        when(collectionReference.orderBy("createdAt", Query.Direction.DESCENDING)).thenReturn(query);
        when(query.get()).thenReturn(querySnapshotFuture);
        when(querySnapshotFuture.get()).thenReturn(querySnapshot);
        when(querySnapshot.toObjects(User.class)).thenReturn(users);

        // Act
        List<User> result = userRepository.findAll();

        // Assert
        assertEquals(2, result.size());
        assertEquals("test-uid", result.get(0).getUid());
        assertEquals("test-uid-2", result.get(1).getUid());

        verify(collectionReference).orderBy("createdAt", Query.Direction.DESCENDING);
        verify(query).get();
    }

    @Test
    void findByRole_shouldReturnUsersWithRole() throws Exception {
        // Arrange
        User admin1 = new User("admin-1", "admin1@example.com", "Admin 1", "admin");
        User admin2 = new User("admin-2", "admin2@example.com", "Admin 2", "admin");
        List<User> admins = Arrays.asList(admin1, admin2);

        when(collectionReference.whereEqualTo("role", "admin")).thenReturn(query);
        when(query.orderBy("displayName", Query.Direction.ASCENDING)).thenReturn(query);
        when(query.get()).thenReturn(querySnapshotFuture);
        when(querySnapshotFuture.get()).thenReturn(querySnapshot);
        when(querySnapshot.toObjects(User.class)).thenReturn(admins);

        // Act
        List<User> result = userRepository.findByRole("admin");

        // Assert
        assertEquals(2, result.size());
        assertEquals("admin", result.get(0).getRole());
        assertEquals("admin", result.get(1).getRole());

        verify(collectionReference).whereEqualTo("role", "admin");
        verify(query).orderBy("displayName", Query.Direction.ASCENDING);
    }

    @Test
    void deleteByUid_shouldDeleteUser() throws Exception {
        // Arrange
        when(collectionReference.document("test-uid")).thenReturn(documentReference);
        when(documentReference.delete()).thenReturn(writeResultFuture);
        when(writeResultFuture.get()).thenReturn(mock(WriteResult.class));

        // Act
        userRepository.deleteByUid("test-uid");

        // Assert
        verify(collectionReference).document("test-uid");
        verify(documentReference).delete();
        verify(writeResultFuture).get();
    }

    @Test
    void existsByUid_shouldReturnTrue_whenUserExists() throws Exception {
        // Arrange
        when(collectionReference.document("test-uid")).thenReturn(documentReference);
        when(documentReference.get()).thenReturn(documentSnapshotFuture);
        when(documentSnapshotFuture.get()).thenReturn(documentSnapshot);
        when(documentSnapshot.exists()).thenReturn(true);

        // Act
        boolean exists = userRepository.existsByUid("test-uid");

        // Assert
        assertTrue(exists);

        verify(collectionReference).document("test-uid");
        verify(documentSnapshot).exists();
    }

    @Test
    void existsByUid_shouldReturnFalse_whenUserDoesNotExist() throws Exception {
        // Arrange
        when(collectionReference.document("nonexistent")).thenReturn(documentReference);
        when(documentReference.get()).thenReturn(documentSnapshotFuture);
        when(documentSnapshotFuture.get()).thenReturn(documentSnapshot);
        when(documentSnapshot.exists()).thenReturn(false);

        // Act
        boolean exists = userRepository.existsByUid("nonexistent");

        // Assert
        assertFalse(exists);

        verify(collectionReference).document("nonexistent");
        verify(documentSnapshot).exists();
    }
}

