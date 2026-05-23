package com.albunyaan.tube.dto.registry;

import java.util.List;

/** POST /api/admin/registry/bulk/preview response. */
public record BulkPreviewResponse(List<PreviewRow> rows) {}
