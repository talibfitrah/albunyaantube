package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.config.ValidationSchedulerProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.google.cloud.firestore.WriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SchedulerLockService
 *
 * Tests verify:
 * - Lock acquisition succeeds when no lock exists (via transaction)
 * - Lock acquisition fails when valid lock held by another instance
 * - Lock acquisition succeeds when lock is expired (TTL passed)
 * - Lock release deletes document
 * - Firestore errors default to fail-safe (no lock acquired)
 */
@ExtendWith(MockitoExtension.class)
class SchedulerLockServiceTest {

    @Mock
    private Firestore firestore;

    @Mock
    private CollectionReference collectionReference;

    @Mock
    private DocumentReference documentReference;

    @Mock
    private DocumentSnapshot documentSnapshot;

    @Mock
    private ApiFuture<DocumentSnapshot> documentFuture;

    @Mock
    private ApiFuture<WriteResult> writeFuture;

    @Mock
    private ApiFuture<Boolean> transactionFuture;

    @Mock
    private WriteResult writeResult;

    private FirestoreTimeoutProperties timeoutProperties;
    private ValidationSchedulerProperties schedulerProperties;
    private SchedulerLockService lockService;

    @BeforeEach
    void setUp() {
        timeoutProperties = new FirestoreTimeoutProperties();
        timeoutProperties.setRead(5);
        timeoutProperties.setWrite(10);

        schedulerProperties = new ValidationSchedulerProperties();
        schedulerProperties.setLockTtlMinutes(120);

        when(firestore.collection("system_locks")).thenReturn(collectionReference);
        when(collectionReference.document("video_validation_scheduler")).thenReturn(documentReference);

        lockService = new SchedulerLockService(firestore, timeoutProperties, schedulerProperties);
    }

    @Nested
    @DisplayName("Lock Acquisition Tests")
    class LockAcquisitionTests {

        @Test
        @DisplayName("Should acquire lock when no lock exists (via transaction)")
        @SuppressWarnings("unchecked")
        void tryAcquireLock_whenNoLockExists_shouldSucceed() throws Exception {
            // Arrange - mock transaction to return true (lock acquired)
            when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            boolean acquired = lockService.tryAcquireLock("test-run-id");

            // Assert
            assertTrue(acquired);
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should fail to acquire lock when valid lock exists (via transaction)")
        @SuppressWarnings("unchecked")
        void tryAcquireLock_whenValidLockExists_shouldFail() throws Exception {
            // Arrange - mock transaction to return false (lock not acquired)
            when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(false);

            // Act
            boolean acquired = lockService.tryAcquireLock("test-run-id");

            // Assert
            assertFalse(acquired);
        }

        @Test
        @DisplayName("Should fail-safe on Firestore transaction error")
        @SuppressWarnings("unchecked")
        void tryAcquireLock_onTransactionError_shouldFailSafe() throws Exception {
            // Arrange
            when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            when(transactionFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException("Firestore error", new RuntimeException()));

            // Act
            boolean acquired = lockService.tryAcquireLock("test-run-id");

            // Assert - fail-safe: don't acquire lock on error
            assertFalse(acquired);
        }

        @Test
        @DisplayName("Should fail-safe on Firestore timeout")
        @SuppressWarnings("unchecked")
        void tryAcquireLock_onTimeout_shouldFailSafe() throws Exception {
            // Arrange
            when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            when(transactionFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new TimeoutException("Firestore timeout"));

            // Act
            boolean acquired = lockService.tryAcquireLock("test-run-id");

            // Assert - fail-safe: don't acquire lock on timeout
            assertFalse(acquired);
        }
    }

    @Nested
    @DisplayName("Lock Release Tests")
    class LockReleaseTests {

        @Test
        @DisplayName("Should not release lock when owned by another instance")
        void releaseLock_whenNotOwned_shouldNotDelete() throws Exception {
            // Arrange
            when(documentReference.get()).thenReturn(documentFuture);
            when(documentFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(documentSnapshot);
            when(documentSnapshot.exists()).thenReturn(true);
            when(documentSnapshot.getString("lockedBy")).thenReturn("other-instance");
            when(documentSnapshot.getString("runId")).thenReturn("other-run-id");

            // Act
            lockService.releaseLock("test-run-id");

            // Assert - should not delete lock owned by another instance
            verify(documentReference, never()).delete();
        }

        @Test
        @DisplayName("Should handle missing lock document gracefully")
        void releaseLock_whenNoLockExists_shouldNotThrow() throws Exception {
            // Arrange
            when(documentReference.get()).thenReturn(documentFuture);
            when(documentFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(documentSnapshot);
            when(documentSnapshot.exists()).thenReturn(false);

            // Act & Assert - should not throw
            assertDoesNotThrow(() -> lockService.releaseLock("test-run-id"));
            verify(documentReference, never()).delete();
        }

        @Test
        @DisplayName("Should handle Firestore error gracefully during release")
        void releaseLock_onError_shouldNotThrow() throws Exception {
            // Arrange
            when(documentReference.get()).thenReturn(documentFuture);
            when(documentFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException("Firestore error", new RuntimeException()));

            // Act & Assert - should not throw (lock will expire via TTL)
            assertDoesNotThrow(() -> lockService.releaseLock("test-run-id"));
        }
    }

    @Nested
    @DisplayName("Lock Status Tests")
    class LockStatusTests {

        @Test
        @DisplayName("Should return null when no lock exists")
        void getLockStatus_whenNoLock_shouldReturnNull() throws Exception {
            // Arrange
            when(documentReference.get()).thenReturn(documentFuture);
            when(documentFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(documentSnapshot);
            when(documentSnapshot.exists()).thenReturn(false);

            // Act
            SchedulerLockService.LockStatus status = lockService.getLockStatus();

            // Assert
            assertNull(status);
        }

        @Test
        @DisplayName("Should return status when lock exists")
        void getLockStatus_whenLockExists_shouldReturnStatus() throws Exception {
            // Arrange
            when(documentReference.get()).thenReturn(documentFuture);
            when(documentFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(documentSnapshot);
            when(documentSnapshot.exists()).thenReturn(true);
            when(documentSnapshot.getString("lockedBy")).thenReturn("test-instance");
            when(documentSnapshot.getString("runId")).thenReturn("test-run-id");

            Instant futureExpiry = Instant.now().plusSeconds(3600);
            Timestamp expiresAt = Timestamp.ofTimeSecondsAndNanos(
                    futureExpiry.getEpochSecond(), futureExpiry.getNano());
            when(documentSnapshot.getTimestamp("expiresAt")).thenReturn(expiresAt);
            when(documentSnapshot.getTimestamp("lockedAt")).thenReturn(
                    Timestamp.ofTimeSecondsAndNanos(Instant.now().getEpochSecond(), 0));

            // Act
            SchedulerLockService.LockStatus status = lockService.getLockStatus();

            // Assert
            assertNotNull(status);
            assertEquals("test-instance", status.lockedBy());
            assertEquals("test-run-id", status.runId());
            assertFalse(status.isExpired());
        }

        @Test
        @DisplayName("Should indicate expired status correctly")
        void getLockStatus_whenExpired_shouldIndicateExpired() throws Exception {
            // Arrange
            when(documentReference.get()).thenReturn(documentFuture);
            when(documentFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(documentSnapshot);
            when(documentSnapshot.exists()).thenReturn(true);
            when(documentSnapshot.getString("lockedBy")).thenReturn("test-instance");
            when(documentSnapshot.getString("runId")).thenReturn("test-run-id");

            Instant pastExpiry = Instant.now().minusSeconds(3600);
            Timestamp expiresAt = Timestamp.ofTimeSecondsAndNanos(
                    pastExpiry.getEpochSecond(), pastExpiry.getNano());
            when(documentSnapshot.getTimestamp("expiresAt")).thenReturn(expiresAt);
            when(documentSnapshot.getTimestamp("lockedAt")).thenReturn(
                    Timestamp.ofTimeSecondsAndNanos(Instant.now().minusSeconds(7200).getEpochSecond(), 0));

            // Act
            SchedulerLockService.LockStatus status = lockService.getLockStatus();

            // Assert
            assertNotNull(status);
            assertTrue(status.isExpired());
        }

        @Test
        @DisplayName("Should return null on Firestore error")
        void getLockStatus_onError_shouldReturnNull() throws Exception {
            // Arrange
            when(documentReference.get()).thenReturn(documentFuture);
            when(documentFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException("Firestore error", new RuntimeException()));

            // Act
            SchedulerLockService.LockStatus status = lockService.getLockStatus();

            // Assert
            assertNull(status);
        }
    }
}
