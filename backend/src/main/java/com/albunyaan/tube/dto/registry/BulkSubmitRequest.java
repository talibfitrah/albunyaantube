package com.albunyaan.tube.dto.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/submit body. */
public record BulkSubmitRequest(
        @NotNull @Size(min = 1, max = 25) List<@Valid SubmitRow> rows,
        /** Optional. Defaults to PENDING. Only ADMIN role honored when set to APPROVED. */
        String status
) {}
