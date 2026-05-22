package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — error codes returned per-row by the bulk preview endpoint. */
public enum PreviewErrorCode {
    UNSUPPORTED_SHORTS,
    UNSUPPORTED_TYPE,
    NOT_YOUTUBE_URL,
    CONTENT_NOT_AVAILABLE,
    PRIVATE_CONTENT,
    AGE_RESTRICTED,
    GEO_RESTRICTED,
    CHANNEL_TERMINATED,
    NEWPIPE_PARSING_ERROR,
    NETWORK_ERROR,
    DUPLICATE,
    DUPLICATE_REJECTED
}
