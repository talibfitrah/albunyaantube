package com.albunyaan.tube.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A live-category refusal (spec §4.2): an HTTP status, a typed code the admin UI maps to {@code apiErrors.<CODE>},
 * and details (for whole-request refusals, {@code details.offenders}). A plain RuntimeException on purpose: several
 * controllers swallow IllegalArgumentException/IllegalStateException into bare 400/409s.
 */
public class LiveCategoryException extends RuntimeException {

    public static final String ADMIN_ONLY = "LIVE_CATEGORY_ADMIN_ONLY";
    public static final String TYPE_NOT_ALLOWED = "LIVE_CATEGORY_TYPE_NOT_ALLOWED";
    public static final String NOT_LIVE = "LIVE_LINK_NOT_LIVE";
    public static final String CHANNEL_UNKNOWN = "LIVE_LINK_CHANNEL_UNKNOWN";
    public static final String CHECK_UNAVAILABLE = "LIVE_CHECK_UNAVAILABLE";
    public static final String FULL = "LIVE_CATEGORY_FULL";
    public static final String SHAPE = "LIVE_CATEGORY_SHAPE";
    public static final String MEMBERS_INVALID = "LIVE_CATEGORY_MEMBERS_INVALID";

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public LiveCategoryException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    /** D8: a LIVE category is top-level and nothing nests under one (category create/update and the JSON import). */
    public static LiveCategoryException shape(String categoryName) {
        return new LiveCategoryException(HttpStatus.BAD_REQUEST, SHAPE,
                "A live category can't have a parent category or subcategories",
                Map.of("category", categoryName == null ? "" : categoryName));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
