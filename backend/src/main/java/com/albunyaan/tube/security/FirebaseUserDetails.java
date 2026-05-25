package com.albunyaan.tube.security;

/**
 * FIREBASE-MIGRATE-02: User details from Firebase token
 *
 * Represents authenticated user information extracted from Firebase ID token.
 */
public class FirebaseUserDetails {

    private final String uid;
    private final String email;
    private final String role;
    private final boolean emailVerified;

    public FirebaseUserDetails(String uid, String email, String role) {
        this(uid, email, role, false);
    }

    public FirebaseUserDetails(String uid, String email, String role, boolean emailVerified) {
        this.uid = uid;
        this.email = email;
        this.role = role;
        this.emailVerified = emailVerified;
    }

    public String getUid() {
        return uid;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }

    public boolean isModerator() {
        return "moderator".equalsIgnoreCase(role) || isAdmin();
    }
}

