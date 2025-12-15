package com.albunyaan.tube.exception;

import com.albunyaan.tube.service.YouTubeCircuitBreakerService;

import java.io.IOException;

/**
 * Exception thrown when a YouTube request is blocked by the circuit breaker.
 *
 * This exception indicates that the circuit breaker is in OPEN state due to
 * detected YouTube rate limiting. The request should not be retried until
 * the cooldown period has passed.
 *
 * Extends IOException to be compatible with existing exception handling in
 * callers that already catch IOException for network-related failures.
 */
public class CircuitBreakerOpenException extends IOException {

    private final YouTubeCircuitBreakerService.CircuitBreakerStatus status;

    public CircuitBreakerOpenException(String message, YouTubeCircuitBreakerService.CircuitBreakerStatus status) {
        super(message);
        this.status = status;
    }

    public CircuitBreakerOpenException(String message, YouTubeCircuitBreakerService.CircuitBreakerStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * Get the circuit breaker status at the time the exception was thrown.
     */
    public YouTubeCircuitBreakerService.CircuitBreakerStatus getStatus() {
        return status;
    }
}
