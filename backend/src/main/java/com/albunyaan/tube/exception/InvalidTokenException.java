package com.albunyaan.tube.exception;

/**
 * Exception thrown when a download token is invalid or expired.
 * Typically mapped to HTTP 401 status code.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
