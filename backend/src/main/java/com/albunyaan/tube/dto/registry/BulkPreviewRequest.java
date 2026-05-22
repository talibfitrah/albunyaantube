package com.albunyaan.tube.dto.registry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/preview body. */
public record BulkPreviewRequest(
        @Size(min = 1, max = 25) List<@NotBlank String> urls,
        @Size(min = 1) List<@NotBlank String> defaultCategoryIds
) {}
