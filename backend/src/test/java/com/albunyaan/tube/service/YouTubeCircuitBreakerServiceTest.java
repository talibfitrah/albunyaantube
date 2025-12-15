package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.config.YouTubeCircuitBreakerProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
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
 * Unit tests for YouTubeCircuitBreakerService
 *
 * Tests verify:
 * - Breaker allows requests when CLOSED
 * - Breaker blocks requests when OPEN (with valid cooldown)
 * - Breaker transitions from OPEN to HALF_OPEN when cooldown expires
 * - Successful probe closes the breaker
 * - Failed probe reopens breaker with increased backoff
 * - Rate-limit errors open the breaker after threshold
 * - Fail-safe: block requests when Firestore unavailable
 * - Rate-limit error detection patterns
 */
@ExtendWith(MockitoExtension.class)
class YouTubeCircuitBreakerServiceTest {

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
    private ApiFuture<Boolean> transactionFuture;

    private FirestoreTimeoutProperties timeoutProperties;
    private YouTubeCircuitBreakerProperties breakerProperties;
    private YouTubeCircuitBreakerService circuitBreakerService;

    @BeforeEach
    void setUp() {
        timeoutProperties = new FirestoreTimeoutProperties();
        timeoutProperties.setRead(5);
        timeoutProperties.setWrite(10);

        breakerProperties = new YouTubeCircuitBreakerProperties();
        breakerProperties.setEnabled(true);
        breakerProperties.setPersistenceCollection("system_settings");
        breakerProperties.setPersistenceDocumentId("youtube_circuit_breaker");
        breakerProperties.setRollingWindowErrorThreshold(3);
        breakerProperties.setRollingWindowMinutes(10);
        breakerProperties.setCooldownBaseMinutes(60);
        breakerProperties.setCooldownMaxMinutes(2880);

        // Use lenient mocking for common setup that may not be used in all tests
        lenient().when(firestore.collection("system_settings")).thenReturn(collectionReference);
        lenient().when(collectionReference.document("youtube_circuit_breaker")).thenReturn(documentReference);

        circuitBreakerService = new YouTubeCircuitBreakerService(firestore, timeoutProperties, breakerProperties);
    }

    /**
     * Helper to set up document mock with common fields
     */
    private void setupDocumentMock(String state, Long consecutiveFailures, Long backoffLevel, Timestamp cooldownUntil) throws Exception {
        lenient().when(documentReference.get()).thenReturn(documentFuture);
        lenient().when(documentFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(documentSnapshot);
        lenient().when(documentSnapshot.exists()).thenReturn(true);
        lenient().when(documentSnapshot.getString("state")).thenReturn(state);
        lenient().when(documentSnapshot.getLong("consecutiveFailures")).thenReturn(consecutiveFailures);
        lenient().when(documentSnapshot.getLong("backoffLevel")).thenReturn(backoffLevel);
        lenient().when(documentSnapshot.getLong("version")).thenReturn(1L);
        lenient().when(documentSnapshot.getTimestamp("cooldownUntil")).thenReturn(cooldownUntil);
        // Other fields return null by default which is fine
    }

    @Nested
    @DisplayName("Allow Request Tests")
    class AllowRequestTests {

        @Test
        @DisplayName("Should allow request when disabled")
        void allowRequest_whenDisabled_shouldAllow() {
            // Arrange
            breakerProperties.setEnabled(false);

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert
            assertTrue(allowed);
        }

        @Test
        @DisplayName("Should allow request when no state exists (first run)")
        void allowRequest_whenNoStateExists_shouldAllow() throws Exception {
            // Arrange
            setupDocumentMock("CLOSED", 0L, 0L, null);
            lenient().when(documentSnapshot.exists()).thenReturn(false);

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert
            assertTrue(allowed);
        }

        @Test
        @DisplayName("Should allow request when state is CLOSED")
        void allowRequest_whenClosed_shouldAllow() throws Exception {
            // Arrange
            setupDocumentMock("CLOSED", 0L, 0L, null);

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert
            assertTrue(allowed);
        }

        @Test
        @DisplayName("Should block request when state is OPEN with valid cooldown")
        void allowRequest_whenOpenWithValidCooldown_shouldBlock() throws Exception {
            // Arrange
            Instant futureCooldown = Instant.now().plusSeconds(3600); // 1 hour in future
            Timestamp cooldownUntil = Timestamp.ofTimeSecondsAndNanos(
                    futureCooldown.getEpochSecond(), futureCooldown.getNano());

            setupDocumentMock("OPEN", 3L, 0L, cooldownUntil);

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert
            assertFalse(allowed);
        }

        @Test
        @DisplayName("Should allow request and transition to HALF_OPEN when cooldown expired")
        @SuppressWarnings("unchecked")
        void allowRequest_whenOpenWithExpiredCooldown_shouldTransitionToHalfOpen() throws Exception {
            // Arrange
            Instant pastCooldown = Instant.now().minusSeconds(60); // 1 minute in past
            Timestamp cooldownUntil = Timestamp.ofTimeSecondsAndNanos(
                    pastCooldown.getEpochSecond(), pastCooldown.getNano());

            setupDocumentMock("OPEN", 3L, 0L, cooldownUntil);

            // Mock transaction for state transition
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert
            assertTrue(allowed);
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should allow request when state is HALF_OPEN and no probe in-flight (can claim)")
        @SuppressWarnings("unchecked")
        void allowRequest_whenHalfOpenNoProbe_shouldAllowAndClaimProbe() throws Exception {
            // Arrange - HALF_OPEN with no probe in-flight
            setupDocumentMock("HALF_OPEN", 3L, 1L, null);
            lenient().when(documentSnapshot.getString("probeStatus")).thenReturn("NONE");

            // Mock transaction for probe claim
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert
            assertTrue(allowed);
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should block request when state is HALF_OPEN and probe already in-flight")
        void allowRequest_whenHalfOpenProbeInFlight_shouldBlock() throws Exception {
            // Arrange - HALF_OPEN with probe already in-flight
            Instant probeStarted = Instant.now().minusSeconds(5); // Started 5 seconds ago
            setupDocumentMock("HALF_OPEN", 3L, 1L, null);
            lenient().when(documentSnapshot.getString("probeStatus")).thenReturn("IN_FLIGHT");
            lenient().when(documentSnapshot.getTimestamp("probeStartedAt")).thenReturn(
                    Timestamp.ofTimeSecondsAndNanos(probeStarted.getEpochSecond(), probeStarted.getNano()));

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert - should block because probe is in-flight
            assertFalse(allowed);
        }

        @Test
        @DisplayName("Should fail-safe (block) on Firestore error")
        void allowRequest_onFirestoreError_shouldFailSafe() throws Exception {
            // Arrange
            lenient().when(documentReference.get()).thenReturn(documentFuture);
            lenient().when(documentFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException("Firestore error", new RuntimeException()));

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert - fail-safe: block requests when state unknown
            assertFalse(allowed);
        }

        @Test
        @DisplayName("Should fail-safe (block) on Firestore timeout")
        void allowRequest_onTimeout_shouldFailSafe() throws Exception {
            // Arrange
            lenient().when(documentReference.get()).thenReturn(documentFuture);
            lenient().when(documentFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new TimeoutException("Firestore timeout"));

            // Act
            boolean allowed = circuitBreakerService.allowRequest();

            // Assert - fail-safe: block requests when state unknown
            assertFalse(allowed);
        }
    }

    @Nested
    @DisplayName("Record Success Tests")
    class RecordSuccessTests {

        @Test
        @DisplayName("Should do nothing when disabled")
        void recordSuccess_whenDisabled_shouldDoNothing() {
            // Arrange
            breakerProperties.setEnabled(false);

            // Act
            circuitBreakerService.recordSuccess();

            // Assert - no Firestore interaction when disabled
            verify(documentReference, never()).get();
        }

        @Test
        @DisplayName("Should close breaker when HALF_OPEN probe succeeds")
        @SuppressWarnings("unchecked")
        void recordSuccess_whenHalfOpen_shouldClose() throws Exception {
            // Arrange
            setupDocumentMock("HALF_OPEN", 3L, 1L, null);

            // Mock transaction for state transition
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            circuitBreakerService.recordSuccess();

            // Assert - should have called transaction to update state
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should do nothing when CLOSED with no backoff")
        void recordSuccess_whenClosed_shouldDoNothing() throws Exception {
            // Arrange
            setupDocumentMock("CLOSED", 0L, 0L, null);

            // Act
            circuitBreakerService.recordSuccess();

            // Assert - no state change needed (no backoff to decay)
            verify(firestore, never()).runTransaction(any());
        }
    }

    @Nested
    @DisplayName("Rate Limit Error Detection Tests")
    class RateLimitErrorDetectionTests {

        @Test
        @DisplayName("Should detect 'confirm you're not a bot' message")
        void isRateLimitError_confirmNotBot_shouldDetect() {
            // Arrange
            Exception e = new RuntimeException("Please confirm you're not a bot");

            // Act
            boolean isRateLimit = circuitBreakerService.isRateLimitError(e);

            // Assert
            assertTrue(isRateLimit);
        }

        @Test
        @DisplayName("Should detect 'sign in to confirm' message")
        void isRateLimitError_signInToConfirm_shouldDetect() {
            // Arrange
            Exception e = new RuntimeException("Sign in to confirm you're not a robot");

            // Act
            boolean isRateLimit = circuitBreakerService.isRateLimitError(e);

            // Assert
            assertTrue(isRateLimit);
        }

        @Test
        @DisplayName("Should detect 'LOGIN_REQUIRED' message")
        void isRateLimitError_loginRequired_shouldDetect() {
            // Arrange
            Exception e = new RuntimeException("LOGIN_REQUIRED: Please sign in");

            // Act
            boolean isRateLimit = circuitBreakerService.isRateLimitError(e);

            // Assert
            assertTrue(isRateLimit);
        }

        @Test
        @DisplayName("Should detect SignInConfirmNotBotException by class name")
        void isRateLimitError_signInConfirmException_shouldDetect() {
            // Arrange - simulate the exception by checking class name
            Exception e = new SignInConfirmNotBotException("Bot check required");

            // Act
            boolean isRateLimit = circuitBreakerService.isRateLimitError(e);

            // Assert
            assertTrue(isRateLimit);
        }

        @Test
        @DisplayName("Should detect rate-limit error in cause chain")
        void isRateLimitError_inCauseChain_shouldDetect() {
            // Arrange
            Exception cause = new RuntimeException("confirm you're not a bot");
            Exception wrapper = new RuntimeException("Extraction failed", cause);

            // Act
            boolean isRateLimit = circuitBreakerService.isRateLimitError(wrapper);

            // Assert
            assertTrue(isRateLimit);
        }

        @Test
        @DisplayName("Should not detect normal errors")
        void isRateLimitError_normalError_shouldNotDetect() {
            // Arrange
            Exception e = new RuntimeException("Video not found");

            // Act
            boolean isRateLimit = circuitBreakerService.isRateLimitError(e);

            // Assert
            assertFalse(isRateLimit);
        }

        @Test
        @DisplayName("Should handle null exception")
        void isRateLimitError_null_shouldReturnFalse() {
            // Act
            boolean isRateLimit = circuitBreakerService.isRateLimitError(null);

            // Assert
            assertFalse(isRateLimit);
        }

        @Test
        @DisplayName("Should handle exception with null message")
        void isRateLimitError_nullMessage_shouldNotThrow() {
            // Arrange
            Exception e = new RuntimeException((String) null);

            // Act & Assert - should not throw
            boolean isRateLimit = circuitBreakerService.isRateLimitError(e);
            assertFalse(isRateLimit);
        }

        // Helper class to simulate SignInConfirmNotBotException
        private static class SignInConfirmNotBotException extends RuntimeException {
            SignInConfirmNotBotException(String message) {
                super(message);
            }
        }
    }

    @Nested
    @DisplayName("Record Rate Limit Error Tests")
    class RecordRateLimitErrorTests {

        @Test
        @DisplayName("Should do nothing when disabled")
        void recordRateLimitError_whenDisabled_shouldDoNothing() {
            // Arrange
            breakerProperties.setEnabled(false);
            Exception e = new RuntimeException("confirm you're not a bot");

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - no Firestore interaction when disabled
            verify(documentReference, never()).get();
        }

        @Test
        @DisplayName("Should do nothing for non-rate-limit errors")
        void recordRateLimitError_nonRateLimit_shouldDoNothing() throws Exception {
            // Arrange
            Exception e = new RuntimeException("Video not found");

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - no state read needed for non-rate-limit errors
            verify(documentReference, never()).get();
        }

        @Test
        @DisplayName("Should increment failure count on first rate-limit error")
        @SuppressWarnings("unchecked")
        void recordRateLimitError_firstError_shouldIncrementCount() throws Exception {
            // Arrange
            Exception e = new RuntimeException("confirm you're not a bot");

            setupDocumentMock("CLOSED", 0L, 0L, null);
            lenient().when(documentSnapshot.exists()).thenReturn(false); // No state yet

            // Mock transaction for state update
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - should have recorded the error
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should open breaker when threshold reached")
        @SuppressWarnings("unchecked")
        void recordRateLimitError_thresholdReached_shouldOpenBreaker() throws Exception {
            // Arrange
            Exception e = new RuntimeException("confirm you're not a bot");
            Instant recentFailure = Instant.now().minusSeconds(60); // Within rolling window

            setupDocumentMock("CLOSED", 2L, 0L, null); // Next error = threshold (3)
            lenient().when(documentSnapshot.getTimestamp("lastFailureAt")).thenReturn(
                    Timestamp.ofTimeSecondsAndNanos(recentFailure.getEpochSecond(), recentFailure.getNano()));

            // Mock transaction for state update
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - should have opened the breaker
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should reopen breaker with increased backoff when HALF_OPEN probe fails")
        @SuppressWarnings("unchecked")
        void recordRateLimitError_halfOpenProbeFails_shouldReopenWithBackoff() throws Exception {
            // Arrange
            Exception e = new RuntimeException("confirm you're not a bot");

            setupDocumentMock("HALF_OPEN", 3L, 1L, null);
            lenient().when(documentSnapshot.getLong("version")).thenReturn(2L);

            // Mock transaction for state update
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - should have called transaction to reopen with increased backoff
            verify(firestore, atLeastOnce()).runTransaction(any(Transaction.Function.class));
        }
    }

    @Nested
    @DisplayName("Get Status Tests")
    class GetStatusTests {

        @Test
        @DisplayName("Should return default status when no state exists")
        void getStatus_noState_shouldReturnDefaults() throws Exception {
            // Arrange
            setupDocumentMock("CLOSED", 0L, 0L, null);
            lenient().when(documentSnapshot.exists()).thenReturn(false);

            // Act
            YouTubeCircuitBreakerService.CircuitBreakerStatus status = circuitBreakerService.getStatus();

            // Assert
            assertNotNull(status);
            assertTrue(status.enabled());
            assertEquals("CLOSED", status.state());
            assertEquals(0, status.backoffLevel());
            assertEquals(0, status.consecutiveFailures());
        }

        @Test
        @DisplayName("Should return current state")
        void getStatus_withState_shouldReturnCurrentState() throws Exception {
            // Arrange
            Instant openedAt = Instant.now().minusSeconds(300);
            Instant cooldownUntil = Instant.now().plusSeconds(3300);

            Timestamp cooldownTs = Timestamp.ofTimeSecondsAndNanos(cooldownUntil.getEpochSecond(), cooldownUntil.getNano());
            setupDocumentMock("OPEN", 5L, 2L, cooldownTs);
            lenient().when(documentSnapshot.getTimestamp("openedAt")).thenReturn(
                    Timestamp.ofTimeSecondsAndNanos(openedAt.getEpochSecond(), openedAt.getNano()));
            lenient().when(documentSnapshot.getString("lastTriggeredBy")).thenReturn("RuntimeException");
            lenient().when(documentSnapshot.getLong("version")).thenReturn(3L);

            // Act
            YouTubeCircuitBreakerService.CircuitBreakerStatus status = circuitBreakerService.getStatus();

            // Assert
            assertNotNull(status);
            assertTrue(status.enabled());
            assertEquals("OPEN", status.state());
            assertEquals(2, status.backoffLevel());
            assertEquals(5, status.consecutiveFailures());
            assertEquals("RuntimeException", status.lastTriggeredBy());
            assertNotNull(status.openedAt());
            assertNotNull(status.cooldownUntil());
        }

        @Test
        @DisplayName("Should return UNKNOWN status on error")
        void getStatus_onError_shouldReturnUnknown() throws Exception {
            // Arrange
            lenient().when(documentReference.get()).thenReturn(documentFuture);
            lenient().when(documentFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException("Firestore error", new RuntimeException()));

            // Act
            YouTubeCircuitBreakerService.CircuitBreakerStatus status = circuitBreakerService.getStatus();

            // Assert
            assertNotNull(status);
            assertEquals("UNKNOWN", status.state());
        }
    }

    @Nested
    @DisplayName("Try Allow Request Tests (combined allowed + status)")
    class TryAllowRequestTests {

        @Test
        @DisplayName("Should return allowed=true with status when CLOSED")
        void tryAllowRequest_whenClosed_shouldReturnAllowedWithStatus() throws Exception {
            // Arrange
            setupDocumentMock("CLOSED", 0L, 0L, null);

            // Act
            YouTubeCircuitBreakerService.AllowRequestResult result = circuitBreakerService.tryAllowRequest();

            // Assert
            assertNotNull(result);
            assertTrue(result.allowed());
            assertEquals("CLOSED", result.status().state());
        }

        @Test
        @DisplayName("Should return allowed=false with status when OPEN with valid cooldown")
        void tryAllowRequest_whenOpen_shouldReturnBlockedWithStatus() throws Exception {
            // Arrange
            Instant futureCooldown = Instant.now().plusSeconds(3600);
            Timestamp cooldownTs = Timestamp.ofTimeSecondsAndNanos(
                    futureCooldown.getEpochSecond(), futureCooldown.getNano());
            setupDocumentMock("OPEN", 3L, 1L, cooldownTs);

            // Act
            YouTubeCircuitBreakerService.AllowRequestResult result = circuitBreakerService.tryAllowRequest();

            // Assert
            assertNotNull(result);
            assertFalse(result.allowed());
            assertEquals("OPEN", result.status().state());
            assertNotNull(result.status().cooldownUntil());
        }

        @Test
        @DisplayName("Should return allowed=false with UNKNOWN status on Firestore error")
        void tryAllowRequest_onError_shouldReturnBlockedWithUnknownStatus() throws Exception {
            // Arrange
            lenient().when(documentReference.get()).thenReturn(documentFuture);
            lenient().when(documentFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException("Firestore error", new RuntimeException()));

            // Act
            YouTubeCircuitBreakerService.AllowRequestResult result = circuitBreakerService.tryAllowRequest();

            // Assert
            assertNotNull(result);
            assertFalse(result.allowed());
            assertEquals("UNKNOWN", result.status().state());
        }
    }

    @Nested
    @DisplayName("Rolling Window Tests")
    class RollingWindowTests {

        @Test
        @DisplayName("Should start new window when windowStartAt is null")
        @SuppressWarnings("unchecked")
        void recordRateLimitError_nullWindow_shouldStartNewWindow() throws Exception {
            // Arrange
            Exception e = new RuntimeException("confirm you're not a bot");
            setupDocumentMock("CLOSED", 0L, 0L, null);
            lenient().when(documentSnapshot.getTimestamp("windowStartAt")).thenReturn(null);

            // Mock transaction for state update
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - should have recorded the error with new window
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should start new window when windowStartAt is outside rolling window")
        @SuppressWarnings("unchecked")
        void recordRateLimitError_expiredWindow_shouldStartNewWindow() throws Exception {
            // Arrange
            Exception e = new RuntimeException("confirm you're not a bot");
            Instant oldWindow = Instant.now().minusSeconds(breakerProperties.getRollingWindowMinutes() * 60 + 60); // Beyond window

            setupDocumentMock("CLOSED", 2L, 0L, null); // 2 failures in old window
            lenient().when(documentSnapshot.getTimestamp("windowStartAt")).thenReturn(
                    Timestamp.ofTimeSecondsAndNanos(oldWindow.getEpochSecond(), oldWindow.getNano()));

            // Mock transaction for state update
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - should have started new window (failure count reset to 1)
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }

        @Test
        @DisplayName("Should continue window when windowStartAt is within rolling window")
        @SuppressWarnings("unchecked")
        void recordRateLimitError_validWindow_shouldContinueWindow() throws Exception {
            // Arrange
            Exception e = new RuntimeException("confirm you're not a bot");
            Instant recentWindow = Instant.now().minusSeconds(60); // Within window

            setupDocumentMock("CLOSED", 1L, 0L, null); // 1 failure in current window
            lenient().when(documentSnapshot.getTimestamp("windowStartAt")).thenReturn(
                    Timestamp.ofTimeSecondsAndNanos(recentWindow.getEpochSecond(), recentWindow.getNano()));

            // Mock transaction for state update
            lenient().when(firestore.runTransaction(any(Transaction.Function.class))).thenReturn(transactionFuture);
            lenient().when(transactionFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(true);

            // Act
            circuitBreakerService.recordRateLimitError(e);

            // Assert - should have continued window (failure count incremented to 2)
            verify(firestore).runTransaction(any(Transaction.Function.class));
        }
    }
}
