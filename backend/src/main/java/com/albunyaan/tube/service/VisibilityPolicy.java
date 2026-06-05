package com.albunyaan.tube.service;

import java.util.List;

/**
 * Finding 3: single source of truth for content visibility access control.
 *
 * Centralizes the PUBLIC-vs-PERSONAL gate that several read paths kept re-implementing
 * (and individually missing): by-id details, cursor feeds, search index, import resolve,
 * sync derive, and downloads. Use {@link #isPublic} for "may appear in public surfaces"
 * and {@link #isAccessible} for "may this specific user reach it" (public OR a grantee).
 *
 * Fail-closed: anything that is not null/blank/"PUBLIC" is treated as non-public, so a
 * future visibility value can never accidentally fail open to the public.
 */
public final class VisibilityPolicy {

    private VisibilityPolicy() {}

    /** Public unless explicitly non-PUBLIC. null/blank/"PUBLIC" (legacy + organic) are public. */
    public static boolean isPublic(String visibility) {
        return visibility == null || visibility.isBlank() || "PUBLIC".equalsIgnoreCase(visibility);
    }

    /**
     * Whether {@code uid} may access an item with this visibility: it is public, OR the user
     * is in the personal-grant list. Non-PUBLIC items require a matching grant.
     */
    public static boolean isAccessible(String visibility, List<String> personalGrants, String uid) {
        return isPublic(visibility)
                || (uid != null && personalGrants != null && personalGrants.contains(uid));
    }
}
