package com.albunyaan.tube.model;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum Role {
    USER("user", 0),
    MODERATOR("moderator", 1),
    ADMIN("admin", 2);

    private static final Logger log = LoggerFactory.getLogger(Role.class);

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
            default -> {
                // Cubic R-final7 P3 — log unknown role values so a backend
                // schema drift or a typo'd custom-claim mint surfaces in
                // observability instead of silently downgrading to USER.
                log.warn("Role.fromString: unknown role value '{}' — defaulting to USER", trimmed);
                yield USER;
            }
        };
    }
}
