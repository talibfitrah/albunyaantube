package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * System Settings Repository (Firestore)
 *
 * Stores system-level settings in a single collection.
 * Used for circuit breaker state, feature flags, etc.
 */
@Repository
public class SystemSettingsRepository {

    private static final Logger logger = LoggerFactory.getLogger(SystemSettingsRepository.class);
    private static final String COLLECTION_NAME = "system_settings";

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public SystemSettingsRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private DocumentReference getDocument(String key) {
        return firestore.collection(COLLECTION_NAME).document(key);
    }

    /**
     * Save a settings document by key.
     */
    public void save(String key, Map<String, Object> data) {
        try {
            ApiFuture<WriteResult> result = getDocument(key).set(data);
            result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            logger.debug("Saved system setting: {}", key);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.warn("Failed to save system setting '{}': {}", key, e.getMessage());
            // Don't throw - persistence is best-effort
        }
    }

    /**
     * Load a settings document by key.
     */
    public Optional<Map<String, Object>> load(String key) {
        try {
            ApiFuture<DocumentSnapshot> future = getDocument(key).get();
            DocumentSnapshot document = future.get(timeoutProperties.getRead(), TimeUnit.SECONDS);
            if (document.exists()) {
                return Optional.of(document.getData());
            }
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.warn("Failed to load system setting '{}': {}", key, e.getMessage());
            // Don't throw - return empty and let caller use defaults
        }
        return Optional.empty();
    }

    /**
     * Delete a settings document by key.
     */
    public void delete(String key) {
        try {
            ApiFuture<WriteResult> result = getDocument(key).delete();
            result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            logger.debug("Deleted system setting: {}", key);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.warn("Failed to delete system setting '{}': {}", key, e.getMessage());
            // Don't throw - deletion is best-effort
        }
    }
}
