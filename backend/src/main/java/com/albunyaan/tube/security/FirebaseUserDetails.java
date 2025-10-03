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

    public FirebaseUserDetails(String uid, String email, String role) {
        this.uid = uid;
        this.email = email;
        this.role = role;
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

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }

    public boolean isModerator() {
        return "moderator".equalsIgnoreCase(role) || isAdmin();
    }
}
