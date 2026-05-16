package com.albunyaan.tube.util;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.AuthService;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BACKEND-AUTH-01 review-pipeline finding F5:
 *
 * Verifies UserBackfillMigration applies the configured Firestore write timeout
 * to its transaction futures. Pre-fix: {@code firestore.runTransaction(...).get()}
 * had NO timeout, so a stalled Firestore would block the migration thread
 * indefinitely AND leave the CAS lock claimed.
 *
 * Strategy: stub the Firestore.runTransaction future so its get(timeout, unit)
 * call throws TimeoutException. The migration must surface that as an
 * exception (not swallow / hang).
 */
@ExtendWith(MockitoExtension.class)
class UserBackfillMigrationTimeoutTest {

    @Mock Firestore firestore;
    @Mock UserRepository userRepository;
    @Mock AuditLogService auditLogService;
    @Mock AuditLogRepository auditLogRepository;
    @Mock AuthService authService;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void runTransactionTimeoutIsHonored_onLockClaim() throws Exception {
        // Arrange: write timeout = 1s
        FirestoreTimeoutProperties timeouts = new FirestoreTimeoutProperties();
        timeouts.setWrite(1L);

        // Mock the lock-doc ref so runTransaction is reached
        com.google.cloud.firestore.CollectionReference systemCollection =
                mock(com.google.cloud.firestore.CollectionReference.class);
        com.google.cloud.firestore.DocumentReference lockRef =
                mock(com.google.cloud.firestore.DocumentReference.class);
        when(firestore.collection("system_settings")).thenReturn(systemCollection);
        when(systemCollection.document(any())).thenReturn(lockRef);

        // Future that throws TimeoutException on get(timeout, TimeUnit) — simulates Firestore stall
        ApiFuture future = mock(ApiFuture.class);
        when(future.get(eq(1L), eq(TimeUnit.SECONDS))).thenThrow(new TimeoutException("simulated stall"));
        doReturn(future).when(firestore).runTransaction(any());

        UserBackfillMigration migration = new UserBackfillMigration(
                firestore, userRepository, auditLogService, auditLogRepository, timeouts, authService);

        // Act + Assert: the timeout must surface as TimeoutException (or wrap it),
        // NOT block indefinitely. We accept any exception that propagates from the future.
        Exception ex = assertThrows(Exception.class, () -> migration.run("test-actor"),
                "Migration must surface the timeout, not hang");
        // The TimeoutException is what the future throws; ExecutionException would mask it.
        // Either way verify get(1s, SECONDS) was actually called (proves the timeout property
        // reached the call site).
        verify(future, atLeastOnce()).get(eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void releaseLockTransactionTimeoutIsHonored() throws Exception {
        // Arrange: write timeout = 2s. The lock-claim tx succeeds; release tx times out.
        FirestoreTimeoutProperties timeouts = new FirestoreTimeoutProperties();
        timeouts.setWrite(2L);

        com.google.cloud.firestore.CollectionReference systemCollection =
                mock(com.google.cloud.firestore.CollectionReference.class);
        com.google.cloud.firestore.DocumentReference lockRef =
                mock(com.google.cloud.firestore.DocumentReference.class);
        when(firestore.collection("system_settings")).thenReturn(systemCollection);
        when(systemCollection.document(any())).thenReturn(lockRef);

        // claim-lock future: returns true (claimed)
        ApiFuture claimFuture = mock(ApiFuture.class);
        when(claimFuture.get(eq(2L), eq(TimeUnit.SECONDS))).thenReturn(true);

        // release-lock future: throws TimeoutException on get
        ApiFuture releaseFuture = mock(ApiFuture.class);
        when(releaseFuture.get(eq(2L), eq(TimeUnit.SECONDS)))
                .thenThrow(new TimeoutException("simulated stall on release"));

        // post-claim resume read: no prior phase-1 cursor
        ApiFuture postClaimReadFuture = mock(ApiFuture.class);
        com.google.cloud.firestore.DocumentSnapshot postClaimSnap =
                mock(com.google.cloud.firestore.DocumentSnapshot.class);
        when(lockRef.get()).thenReturn(postClaimReadFuture);
        when(postClaimReadFuture.get(eq(timeouts.getRead()), eq(TimeUnit.SECONDS)))
                .thenReturn(postClaimSnap);
        when(postClaimSnap.exists()).thenReturn(false);

        // runTransaction is called twice — return claim first, release second
        doReturn(claimFuture, releaseFuture).when(firestore).runTransaction(any());

        // userRepository.findAfter returns no users → both phase-1 and phase-2 loops
        // exit cleanly, then the release path runs in finally and times out.
        when(userRepository.findAfter(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Collections.emptyList());

        UserBackfillMigration migration = new UserBackfillMigration(
                firestore, userRepository, auditLogService, auditLogRepository, timeouts, authService);

        // The release timeout fires inside the finally{} block — pre-F5 this hung forever.
        assertThrows(Exception.class, () -> migration.run("test-actor"));
        verify(releaseFuture, atLeastOnce()).get(eq(2L), eq(TimeUnit.SECONDS));
    }
}
