package com.albunyaan.tube.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
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
import java.util.Date;

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

    @Value("${app.firebase.firestore.emulator.enabled:false}")
    private boolean emulatorEnabled;

    @Value("${app.firebase.firestore.emulator.host:localhost}")
    private String emulatorHost;

    @Value("${app.firebase.firestore.emulator.port:8090}")
    private int emulatorPort;

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                GoogleCredentials credentials;

                // For emulator, use mock credentials
                if (emulatorEnabled) {
                    logger.info("Firebase emulator mode enabled - using mock credentials");
                    // Create mock credentials with a fake access token for emulator
                    credentials = GoogleCredentials.create(new AccessToken("emulator-token", new Date(System.currentTimeMillis() + 3600000)));
                } else if (serviceAccountResource.exists()) {
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
     * Uses direct FirestoreOptions to bypass database detection issues
     */
    @Bean
    public Firestore firestore() throws IOException {
        logger.info("Connecting to Firestore database: {} in project: {}", databaseId, projectId);

        FirestoreOptions.Builder optionsBuilder = FirestoreOptions.newBuilder()
                .setProjectId(projectId)
                .setDatabaseId(databaseId);

        // Configure for emulator or real Firestore
        if (emulatorEnabled) {
            String emulatorEndpoint = emulatorHost + ":" + emulatorPort;
            logger.info("Using Firestore emulator at: {}", emulatorEndpoint);
            optionsBuilder.setHost(emulatorEndpoint);
            // Use mock credentials for emulator
            GoogleCredentials mockCredentials = GoogleCredentials.create(
                new AccessToken("emulator-token", new Date(System.currentTimeMillis() + 3600000))
            );
            optionsBuilder.setCredentials(mockCredentials);
        } else {
            // Build Firestore with explicit credentials for production
            GoogleCredentials credentials;
            if (serviceAccountResource.exists()) {
                try (InputStream serviceAccount = serviceAccountResource.getInputStream()) {
                    credentials = GoogleCredentials.fromStream(serviceAccount);
                }
            } else {
                credentials = GoogleCredentials.getApplicationDefault();
            }
            optionsBuilder.setCredentials(credentials);

            // Build database path: projects/{project}/databases/{database}
            String databasePath = String.format("projects/%s/databases/%s", projectId, databaseId);
            logger.info("Using Firestore database path: {}", databasePath);
        }

        return optionsBuilder.build().getService();
    }
}
