package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — error codes returned per-row by the bulk preview endpoint. */
public enum PreviewErrorCode {
    UNSUPPORTED_SHORTS,
    UNSUPPORTED_TYPE,
    /**
     * BULK-01 (Group E): /@handle, /c/customname, /user/legacyname URLs are not
     * supported in bulk until the gateway can resolve them to a canonical UC... ID.
     * Without resolution, dedupe + fetch both fail. Moderator should paste the
     * /channel/UC... canonical form (visible in YouTube channel page → Share).
     */
    UNSUPPORTED_HANDLE,
    NOT_YOUTUBE_URL,
    CONTENT_NOT_AVAILABLE,
    PRIVATE_CONTENT,
    AGE_RESTRICTED,
    GEO_RESTRICTED,
    CHANNEL_TERMINATED,
    NEWPIPE_PARSING_ERROR,
    NETWORK_ERROR,
    /**
     * BULK-01 (Group K): backend-side failure unrelated to NewPipe parsing
     * (Firestore unavailability, etc.). Distinct so on-call doesn't chase
     * upstream YouTube extraction when the real cause is internal.
     */
    INTERNAL_ERROR,
    DUPLICATE,
    DUPLICATE_REJECTED
}
