package com.albunyaan.tube.exception;

/**
 * Exception thrown when video stream extraction fails.
 * Typically mapped to HTTP 502 status code (Bad Gateway).
 */
public class StreamExtractionException extends RuntimeException {

    public StreamExtractionException(String message) {
        super(message);
    }

    public StreamExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
