package com.albunyaan.tube.service;

/**
 * Thrown when a user-supplied profile field fails semantic validation
 * (e.g., control characters, embedded URLs, length limits).
 *
 * <p>Distinct from Bean Validation ({@code @Valid}) failures which are caught
 * by {@code MethodArgumentNotValidException}. This exception is thrown by
 * service-layer validators that require logic beyond declarative annotations.
 *
 * <p>Mapped to HTTP 400 via {@code @ExceptionHandler} in
 * {@code AccountController}.
 */
public class ProfileValidationException extends RuntimeException {

    private final String field;
    private final String reason;

    public ProfileValidationException(String field, String reason) {
        super(field + ": " + reason);
        this.field = field;
        this.reason = reason;
    }

    /** The profile field that failed validation (e.g., {@code "displayName"}). */
    public String getField() { return field; }

    /** Human-readable reason for the failure. */
    public String getReason() { return reason; }
}
