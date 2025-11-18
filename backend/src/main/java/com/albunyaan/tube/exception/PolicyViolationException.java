package com.albunyaan.tube.exception;

/**
 * Exception thrown when a download policy is violated.
 * Typically mapped to HTTP 403 status code.
 */
public class PolicyViolationException extends RuntimeException {

    public PolicyViolationException(String message) {
        super(message);
    }
}
