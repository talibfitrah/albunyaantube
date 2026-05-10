package com.albunyaan.tube.model;

import java.util.Locale;

public enum UserStatus {
    ACTIVE("active"),
    BLOCKED("blocked"),
    DELETED("deleted"),
    PENDING_PROFILE("pending_profile");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean allowsAuth() {
        return this == ACTIVE || this == PENDING_PROFILE;
    }

    public static UserStatus fromString(String value) {
        if (value == null) return ACTIVE;
        String normalized = value.trim()
            .replaceAll("([a-z])([A-Z])", "$1_$2")  // split camelCase BEFORE lowercasing
            .toLowerCase(Locale.ROOT)
            .replace('-', '_');
        if (normalized.isEmpty()) return ACTIVE;
        return switch (normalized) {
            case "active" -> ACTIVE;
            case "blocked" -> BLOCKED;
            case "deleted" -> DELETED;
            case "pending_profile" -> PENDING_PROFILE;
            default -> ACTIVE;
        };
    }
}
