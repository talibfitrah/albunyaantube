package com.albunyaan.tube.service;

/**
 * Thrown by {@link BulkSubmissionService#submit} when the per-uid daily
 * write budget would be exceeded by the row-count consumption (50/24h
 * shared with single-add, see {@link SubmissionRateLimiter#LIMIT}).
 *
 * <p>Carries {@code retryAfterSec} so the {@code @ExceptionHandler} in
 * {@code GlobalExceptionHandler} can emit a JSON response shape that
 * matches the {@code SubmissionRateLimitInterceptor}'s body
 * ({@code {"code":"RATE_LIMIT","retryAfterSeconds":N,"message":...}})
 * plus a {@code Retry-After} header. Without this shape parity, the
 * frontend's bulk-submit error toast couldn't read retry-after from
 * the response and surfaced a generic "request failed" message.
 */
public class BulkSubmissionRateLimitedException extends RuntimeException {

    private final long retryAfterSec;

    public BulkSubmissionRateLimitedException(long retryAfterSec) {
        super("Daily submission limit reached. Retry after " + retryAfterSec + " seconds.");
        this.retryAfterSec = retryAfterSec;
    }

    public long getRetryAfterSec() {
        return retryAfterSec;
    }
}
