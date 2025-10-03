package com.albunyaan.tube.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * FIREBASE-MIGRATE-01: Firebase Configuration
 *
 * Initializes Firebase Admin SDK for:
 * - Firebase Authentication (user management, token verification)
 * - Cloud Firestore (database operations)
 *
 * Service account credentials are loaded from a JSON file or environment variables.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase.service-account-path}")
    private Resource serviceAccountResource;

    @Value("${app.firebase.project-id}")
    private String projectId;

    @Value("${app.firebase.firestore.database-id:(default)}")
    private String databaseId;

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                GoogleCredentials credentials;

                if (serviceAccountResource.exists()) {
                    try (InputStream serviceAccount = serviceAccountResource.getInputStream()) {
                        credentials = GoogleCredentials.fromStream(serviceAccount);
                    }
                } else {
                    logger.warn("Service account file not found, using Application Default Credentials");
                    credentials = GoogleCredentials.getApplicationDefault();
                }

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .setProjectId(projectId)
                        .build();

                FirebaseApp.initializeApp(options);
                logger.info("Firebase initialized successfully for project: {}", projectId);
            }
        } catch (IOException e) {
            logger.error("Failed to initialize Firebase", e);
            throw new IllegalStateException("Could not initialize Firebase", e);
        }
    }

    /**
     * Provides FirebaseAuth instance for user management and token verification
     */
    @Bean
    public FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }

    /**
     * Provides Firestore instance for database operations
     */
    @Bean
    public Firestore firestore() {
        return FirestoreClient.getFirestore();
    }
}
