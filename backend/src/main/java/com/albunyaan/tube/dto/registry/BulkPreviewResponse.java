package com.albunyaan.tube.dto.registry;

import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/preview response. */
public record BulkPreviewResponse(List<PreviewRow> rows) {}
