package com.albunyaan.tube.dto.registry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/preview body. */
public record BulkPreviewRequest(
        @NotNull @Size(min = 1, max = 25) List<@NotBlank @Size(max = 2048) String> urls
) {}
