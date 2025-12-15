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

import java.util.HashMap;
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

    // ==================== Distributed Locking ====================

    /**
     * Try to acquire a distributed lock. Uses Firestore transactions to ensure atomicity.
     *
     * @param lockKey The lock identifier
     * @param instanceId Unique ID of this instance (e.g., hostname + pid)
     * @param ttlSeconds How long the lock is valid (prevents zombie locks)
     * @return true if lock was acquired, false if already held by another instance
     */
    public boolean tryAcquireLock(String lockKey, String instanceId, int ttlSeconds) {
        try {
            String docKey = "lock_" + lockKey;
            DocumentReference docRef = getDocument(docKey);

            Boolean acquired = firestore.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(docRef).get();

                if (snapshot.exists()) {
                    Map<String, Object> data = snapshot.getData();
                    if (data != null) {
                        Number expiresAtNum = (Number) data.get("expiresAt");
                        String holder = (String) data.get("heldBy");

                        // Check if lock is still valid
                        if (expiresAtNum != null && expiresAtNum.longValue() > System.currentTimeMillis()) {
                            // Lock is held and not expired
                            if (!instanceId.equals(holder)) {
                                logger.debug("Lock {} is held by {} until {}", lockKey, holder,
                                        java.time.Instant.ofEpochMilli(expiresAtNum.longValue()));
                                return false; // Lock held by someone else
                            }
                            // We already hold this lock - extend it
                        }
                        // Lock expired or held by us - can acquire/extend
                    }
                }

                // Acquire or extend the lock
                Map<String, Object> lockData = new HashMap<>();
                lockData.put("heldBy", instanceId);
                lockData.put("acquiredAt", System.currentTimeMillis());
                lockData.put("expiresAt", System.currentTimeMillis() + (ttlSeconds * 1000L));

                // Track extension count for debugging (how many times lock was extended)
                int extensionCount = 0;
                if (snapshot.exists()) {
                    Map<String, Object> existingData = snapshot.getData();
                    if (existingData != null && existingData.containsKey("extensionCount")) {
                        Number existing = (Number) existingData.get("extensionCount");
                        extensionCount = (existing != null) ? existing.intValue() + 1 : 1;
                    }
                }
                lockData.put("extensionCount", extensionCount);
                lockData.put("lastExtendedAt", System.currentTimeMillis());

                transaction.set(docRef, lockData);

                return true;
            }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

            if (acquired) {
                logger.info("Acquired distributed lock: {} (instance: {}, ttl: {}s)", lockKey, instanceId, ttlSeconds);
            }
            return acquired;

        } catch (Exception e) {
            logger.error("Failed to acquire distributed lock '{}': {}", lockKey, e.getMessage());
            // Fail-safe: return false (don't acquire lock on error)
            return false;
        }
    }

    /**
     * Release a distributed lock.
     *
     * @param lockKey The lock identifier
     * @param instanceId Must match the instance that acquired the lock
     * @return true if lock was released, false if not held by this instance
     */
    public boolean releaseLock(String lockKey, String instanceId) {
        try {
            String docKey = "lock_" + lockKey;
            DocumentReference docRef = getDocument(docKey);

            Boolean released = firestore.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(docRef).get();

                if (!snapshot.exists()) {
                    return true; // Already released
                }

                Map<String, Object> data = snapshot.getData();
                if (data != null) {
                    String holder = (String) data.get("heldBy");
                    if (!instanceId.equals(holder)) {
                        logger.warn("Cannot release lock {} - held by {} not {}", lockKey, holder, instanceId);
                        return false;
                    }
                }

                transaction.delete(docRef);
                return true;
            }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

            if (released) {
                logger.info("Released distributed lock: {} (instance: {})", lockKey, instanceId);
            }
            return released;

        } catch (Exception e) {
            logger.error("Failed to release distributed lock '{}': {}", lockKey, e.getMessage());
            return false;
        }
    }

    /**
     * Check if a lock is currently held (by any instance).
     */
    public boolean isLockHeld(String lockKey) {
        try {
            String docKey = "lock_" + lockKey;
            DocumentSnapshot snapshot = getDocument(docKey).get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);

            if (!snapshot.exists()) {
                return false;
            }

            Map<String, Object> data = snapshot.getData();
            if (data == null) {
                return false;
            }

            Number expiresAtNum = (Number) data.get("expiresAt");
            if (expiresAtNum == null) {
                return false;
            }

            return expiresAtNum.longValue() > System.currentTimeMillis();

        } catch (Exception e) {
            logger.warn("Failed to check lock status '{}': {}", lockKey, e.getMessage());
            // Fail-safe: assume lock might be held
            return true;
        }
    }
}
