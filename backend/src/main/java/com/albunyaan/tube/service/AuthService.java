package com.albunyaan.tube.service;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * FIREBASE-MIGRATE-02: Authentication Service
 *
 * Manages user creation, role assignment, and Firebase Authentication integration.
 * Syncs user data between Firebase Auth and Firestore.
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;

    @Value("${app.security.initial-admin.email}")
    private String initialAdminEmail;

    @Value("${app.security.initial-admin.password}")
    private String initialAdminPassword;

    @Value("${app.security.initial-admin.display-name}")
    private String initialAdminDisplayName;

    public AuthService(FirebaseAuth firebaseAuth, UserRepository userRepository) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
    }

    /**
     * Create initial admin user on application startup
     */
    @PostConstruct
    public void initializeAdmin() {
        try {
            // Check if admin already exists
            UserRecord existingUser = null;
            try {
                existingUser = firebaseAuth.getUserByEmail(initialAdminEmail);
            } catch (FirebaseAuthException e) {
                // User doesn't exist, this is expected for first run
            }

            if (existingUser == null) {
                logger.info("Creating initial admin user: {}", initialAdminEmail);
                createUser(initialAdminEmail, initialAdminPassword, initialAdminDisplayName, "admin", null);
                logger.info("Initial admin user created successfully");
            } else {
                logger.info("Initial admin user already exists: {}", initialAdminEmail);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize admin user", e);
            // Don't throw - allow application to start
        }
    }

    /**
     * Create a new user in Firebase Auth and Firestore
     */
    public User createUser(String email, String password, String displayName, String role, String createdByUid)
            throws FirebaseAuthException, ExecutionException, InterruptedException {

        // Create user in Firebase Authentication
        UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                .setEmail(email)
                .setPassword(password)
                .setDisplayName(displayName)
                .setEmailVerified(false);

        UserRecord userRecord = firebaseAuth.createUser(request);
        String uid = userRecord.getUid();

        // Set custom claims for role-based access
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        firebaseAuth.setCustomUserClaims(uid, claims);

        // Create user document in Firestore
        User user = new User(uid, email, displayName, role);
        user.setCreatedBy(createdByUid);
        userRepository.save(user);

        logger.info("Created user: {} (uid: {}) with role: {}", email, uid, role);
        return user;
    }

    /**
     * Update user role (both Firebase claims and Firestore)
     */
    public User updateUserRole(String uid, String newRole)
            throws FirebaseAuthException, ExecutionException, InterruptedException {

        // Update custom claims in Firebase
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", newRole);
        firebaseAuth.setCustomUserClaims(uid, claims);

        // Update Firestore document
        User user = userRepository.findByUid(uid)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + uid));
        user.setRole(newRole);
        user.touch();
        userRepository.save(user);

        logger.info("Updated role for user {} to: {}", uid, newRole);
        return user;
    }

    /**
     * Activate/deactivate user
     */
    public User updateUserStatus(String uid, String status)
            throws FirebaseAuthException, ExecutionException, InterruptedException {

        boolean disabled = "inactive".equals(status);

        // Update Firebase Auth
        UserRecord.UpdateRequest request = new UserRecord.UpdateRequest(uid)
                .setDisabled(disabled);
        firebaseAuth.updateUser(request);

        // Update Firestore
        User user = userRepository.findByUid(uid)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + uid));
        user.setStatus(status);
        user.touch();
        userRepository.save(user);

        logger.info("Updated status for user {} to: {}", uid, status);
        return user;
    }

    /**
     * Delete user from Firebase Auth and Firestore
     */
    public void deleteUser(String uid) throws FirebaseAuthException, ExecutionException, InterruptedException {
        firebaseAuth.deleteUser(uid);
        userRepository.deleteByUid(uid);
        logger.info("Deleted user: {}", uid);
    }

    /**
     * Record user login
     */
    public void recordLogin(String uid) throws ExecutionException, InterruptedException {
        User user = userRepository.findByUid(uid).orElse(null);
        if (user != null) {
            user.recordLogin();
            userRepository.save(user);
        }
    }

    /**
     * Send password reset email
     */
    public void sendPasswordResetEmail(String email) throws FirebaseAuthException {
        String link = firebaseAuth.generatePasswordResetLink(email);
        // In production, send this link via email service
        logger.info("Password reset link generated for: {}", email);
        // TODO: Integrate with email service
    }

    /**
     * Verify if email exists
     */
    public boolean emailExists(String email) {
        try {
            firebaseAuth.getUserByEmail(email);
            return true;
        } catch (FirebaseAuthException e) {
            return false;
        }
    }
}

