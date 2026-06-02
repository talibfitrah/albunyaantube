package com.albunyaan.tube.service;

/**
 * Thrown by {@link com.albunyaan.tube.controller.ImportController} when the
 * per-user daily import-item budget ({@link SubmissionRateLimiter#IMPORT_DAILY_ITEM_BUDGET})
 * would be exceeded by the current request.
 *
 * <p>The whole request is rejected (all-or-nothing) with HTTP 429. The Android
 * client chunks imports into ≤200-item requests processed sequentially, so a
 * 429 on one chunk means "prior chunks accepted; retry this chunk and the remainder
 * after the window resets" — no change to the 200-success response shape is needed.
 */
public class ImportRateLimitedException extends RuntimeException {

    private final long retryAfterSec;

    public ImportRateLimitedException(long retryAfterSec) {
        super("Daily import item limit reached. Retry after " + retryAfterSec + " seconds.");
        this.retryAfterSec = retryAfterSec;
    }

    /**
     * Items still available in the daily budget.
     * Always 0 — the whole request is rejected; unconsumed amount is not tracked.
     */
    public int getRemaining() {
        return 0;
    }

    public long getRetryAfterSec() {
        return retryAfterSec;
    }
}
