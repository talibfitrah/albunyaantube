package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.AsyncConfig;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import com.albunyaan.tube.security.FirebaseUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for circuit-breaker endpoints in ContentValidationController.
 *
 * Validates:
 * - Reset returns 503 when persistence fails (MAJOR-1)
 * - Reset returns 200 when persistence succeeds
 * - GET status is side-effect-free (uses getStatusSnapshot)
 */
@ExtendWith(MockitoExtension.class)
class ContentValidationControllerCircuitBreakerTest {

    @Mock
    private ContentValidationService contentValidationService;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private Executor validationExecutor;

    @Mock
    private AsyncConfig.LoggingCallerRunsPolicy rejectionHandler;

    @Mock
    private YouTubeCircuitBreaker circuitBreaker;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private FirebaseUserDetails adminUser;

    private ContentValidationController controller;

    @BeforeEach
    void setUp() {
        controller = new ContentValidationController(
                contentValidationService,
                categoryRepository,
                validationExecutor,
                rejectionHandler,
                circuitBreaker,
                auditLogService
        );
    }

    @Test
    @DisplayName("Reset returns 503 when persistence fails")
    void resetCircuitBreaker_returns503WhenPersistenceUnavailable() {
        // Arrange - reset() returns false (persistence failed)
        when(circuitBreaker.reset()).thenReturn(false);
        when(circuitBreaker.getStatusSnapshot()).thenReturn(
                new YouTubeCircuitBreaker.CircuitBreakerStatus(
                        false, YouTubeCircuitBreaker.State.CLOSED, 0,
                        null, null, 0, null, null, 0, 0
                )
        );

        // Act
        ResponseEntity<?> response = controller.resetCircuitBreaker(adminUser);

        // Assert
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("Reset not durable", body.get("message"));
        assertEquals(false, body.get("persisted"));
        assertEquals(false, body.get("circuitOpen"));
        // Audit log should NOT be called on failure
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any(FirebaseUserDetails.class));
    }

    @Test
    @DisplayName("Reset returns 503 when state still OPEN after reset")
    void resetCircuitBreaker_returns503WhenStillOpen() {
        // Arrange - persist succeeds but status snapshot shows still open
        when(circuitBreaker.reset()).thenReturn(true);
        when(circuitBreaker.getStatusSnapshot()).thenReturn(
                new YouTubeCircuitBreaker.CircuitBreakerStatus(
                        true, YouTubeCircuitBreaker.State.OPEN, 60000,
                        null, null, 1, null, null, 5, 2
                )
        );

        // Act
        ResponseEntity<?> response = controller.resetCircuitBreaker(adminUser);

        // Assert
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("Reset reopened", body.get("message"));
        assertEquals(true, body.get("persisted"));
        assertEquals(true, body.get("circuitOpen"));
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any(FirebaseUserDetails.class));
    }

    @Test
    @DisplayName("Reset returns 200 when persistence succeeds and circuit is closed")
    void resetCircuitBreaker_returns200WhenSuccessful() {
        // Arrange
        when(circuitBreaker.reset()).thenReturn(true);
        when(circuitBreaker.getStatusSnapshot()).thenReturn(
                new YouTubeCircuitBreaker.CircuitBreakerStatus(
                        false, YouTubeCircuitBreaker.State.CLOSED, 0,
                        null, null, 0, null, null, 0, 0
                )
        );

        // Act
        ResponseEntity<?> response = controller.resetCircuitBreaker(adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("Circuit breaker reset to CLOSED", body.get("message"));
        // Audit log should be called on success
        verify(auditLogService).log(eq("circuit_breaker_reset"), eq("system"),
                eq("youtube_circuit_breaker"), eq(adminUser));
    }

    @Test
    @DisplayName("GET status uses getStatusSnapshot (side-effect-free)")
    void getCircuitBreakerStatus_usesSnapshotNotGetStatus() {
        // Arrange
        YouTubeCircuitBreaker.CircuitBreakerStatus snapshot =
                new YouTubeCircuitBreaker.CircuitBreakerStatus(
                        false, YouTubeCircuitBreaker.State.CLOSED, 0,
                        null, null, 0, null, null, 0, 0
                );
        when(circuitBreaker.getStatusSnapshot()).thenReturn(snapshot);

        // Act
        ResponseEntity<?> response = controller.getCircuitBreakerStatus();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(snapshot, response.getBody());
        // Verify getStatusSnapshot was called (side-effect-free)
        verify(circuitBreaker).getStatusSnapshot();
        // Verify getStatus (which has side effects) was NOT called
        verify(circuitBreaker, never()).getStatus();
        // Verify isOpen (which has side effects) was NOT called
        verify(circuitBreaker, never()).isOpen();
    }
}
