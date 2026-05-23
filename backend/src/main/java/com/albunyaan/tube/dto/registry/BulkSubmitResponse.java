package com.albunyaan.tube.dto.registry;

import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/submit response. */
public record BulkSubmitResponse(
        int totalSubmitted,
        int added,
        int failed,
        List<SubmitResult> results
) {}
