package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — per-row outcome of the bulk preview pipeline. */
public enum RowStatus {
    OK,
    DUPLICATE,
    DUPLICATE_REJECTED,
    ERROR
}
