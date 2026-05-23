package com.albunyaan.tube.dto.registry;

/** Per-row outcome of the bulk preview pipeline. */
public enum RowStatus {
    OK,
    DUPLICATE,
    DUPLICATE_REJECTED,
    ERROR
}
