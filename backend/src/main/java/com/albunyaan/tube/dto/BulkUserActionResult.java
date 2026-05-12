package com.albunyaan.tube.dto;

import java.util.List;

/**
 * Plan F (ADMIN-USER-01, F4) — bulk action response.
 * Always HTTP 200 regardless of mixed success/failure. Per-uid outcome lives here.
 */
public class BulkUserActionResult {

    public record FailureEntry(String uid, String reason) {}

    private final List<String> successes;
    private final List<FailureEntry> failures;

    public BulkUserActionResult(List<String> successes, List<FailureEntry> failures) {
        this.successes = successes;
        this.failures = failures;
    }

    public List<String> getSuccesses() { return successes; }
    public List<FailureEntry> getFailures() { return failures; }
}
