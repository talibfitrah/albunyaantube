package com.albunyaan.tube.model;

import java.util.Locale;

public enum Role {
    USER("user", 0),
    MODERATOR("moderator", 1),
    ADMIN("admin", 2);

    private final String value;
    private final int rank;

    Role(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    public String getValue() {
        return value;
    }

    public boolean includesEqualOrAbove(Role other) {
        return this.rank >= other.rank;
    }

    public static Role fromString(String value) {
        if (value == null) return USER;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return USER;
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "admin" -> ADMIN;
            case "moderator" -> MODERATOR;
            case "user" -> USER;
            default -> USER;
        };
    }
}
