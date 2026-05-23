package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — per-row outcome of bulk submit. */
public record SubmitResult(
        int rowIndex,
        String originalUrl,
        String registryId,
        SubmitStatus status,
        String errorCode   // null unless status=FAILED; left as String — only WRITE_ERROR exists today, enum would be premature
) {}
