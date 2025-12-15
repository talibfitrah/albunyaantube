package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.config.ValidationSchedulerProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.google.cloud.firestore.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Distributed lock service for scheduler coordination.
 *
 * Uses Firestore to provide distributed locking across multiple backend instances.
 * Prevents concurrent validation runs which could trigger YouTube rate limiting.
 *
 * Lock semantics:
 * - Collection: system_locks
 * - Document ID: video_validation_scheduler
 * - TTL-based expiration for crash recovery
 *
 * @see com.albunyaan.tube.scheduler.VideoValidationScheduler
 */
@Service
public class SchedulerLockService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerLockService.class);
    private static final String COLLECTION_NAME = "system_locks";
    private static final String LOCK_DOCUMENT_ID = "video_validation_scheduler";

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;
    private final ValidationSchedulerProperties schedulerProperties;
    private final String instanceId;

    public SchedulerLockService(
            Firestore firestore,
            FirestoreTimeoutProperties timeoutProperties,
            ValidationSchedulerProperties schedulerProperties
    ) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
        this.schedulerProperties = schedulerProperties;
        this.instanceId = generateInstanceId();
        logger.info("SchedulerLockService initialized with instance ID: {}", instanceId);
    }

    /**
     * Generate a unique instance identifier for this backend instance.
     * Format: hostname-uuid (truncated for readability).
     */
    private String generateInstanceId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return hostname + "-" + uuid;
    }

    /**
     * Attempt to acquire the scheduler lock using a Firestore transaction.
     *
     * Uses atomic read-then-write to prevent race conditions where multiple
     * instances could simultaneously see "no lock" and both acquire it.
     *
     * @param runId Unique identifier for this validation run (for correlation)
     * @return true if lock acquired, false if lock held by another instance
     */
    public boolean tryAcquireLock(String runId) {
        try {
            DocumentReference lockRef = firestore.collection(COLLECTION_NAME).document(LOCK_DOCUMENT_ID);
            Instant now = Instant.now();

            // Use a transaction for atomic read-check-write
            ApiFuture<Boolean> transactionResult = firestore.runTransaction((Transaction transaction) -> {
                DocumentSnapshot document = transaction.get(lockRef).get();

                if (document.exists()) {
                    // Check if existing lock is expired
                    Timestamp expiresAtTs = document.getTimestamp("expiresAt");
                    if (expiresAtTs != null) {
                        Instant expiresInstant = Instant.ofEpochSecond(expiresAtTs.getSeconds(), expiresAtTs.getNanos());
                        if (expiresInstant.isAfter(now)) {
                            // Lock is still valid, held by another instance
                            String lockedBy = document.getString("lockedBy");
                            String existingRunId = document.getString("runId");
                            logger.info("Lock held by instance '{}' (runId: {}), expires at {}",
                                    lockedBy, existingRunId, expiresInstant);
                            return false;
                        }
                        // Lock is expired, we can take it
                        logger.info("Existing lock expired at {}, acquiring...", expiresInstant);
                    }
                }

                // Acquire the lock within the same transaction
                Instant expiresAt = now.plusSeconds(schedulerProperties.getLockTtlMinutes() * 60L);
                Map<String, Object> lockData = new HashMap<>();
                lockData.put("lockedBy", instanceId);
                lockData.put("lockedAt", Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano()));
                lockData.put("expiresAt", Timestamp.ofTimeSecondsAndNanos(expiresAt.getEpochSecond(), expiresAt.getNano()));
                lockData.put("runId", runId);

                transaction.set(lockRef, lockData);

                logger.info("Lock acquired by instance '{}' for runId '{}', expires at {}",
                        instanceId, runId, expiresAt);
                return true;
            });

            return transactionResult.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // Fail-safe: if we can't check/acquire lock, don't run
            logger.error("Failed to acquire scheduler lock (fail-safe: not running): {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Release the scheduler lock.
     * Should be called in a finally block after validation completes.
     *
     * @param runId The run ID used when acquiring the lock (for verification)
     */
    public void releaseLock(String runId) {
        try {
            DocumentReference lockRef = firestore.collection(COLLECTION_NAME).document(LOCK_DOCUMENT_ID);
            ApiFuture<DocumentSnapshot> future = lockRef.get();
            DocumentSnapshot document = future.get(timeoutProperties.getRead(), TimeUnit.SECONDS);

            if (document.exists()) {
                String lockedBy = document.getString("lockedBy");
                String existingRunId = document.getString("runId");

                // Only release if we own the lock
                if (instanceId.equals(lockedBy) && runId.equals(existingRunId)) {
                    ApiFuture<WriteResult> deleteResult = lockRef.delete();
                    deleteResult.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                    logger.info("Lock released by instance '{}' for runId '{}'", instanceId, runId);
                } else {
                    logger.warn("Lock not owned by this instance (lockedBy: {}, runId: {}), not releasing",
                            lockedBy, existingRunId);
                }
            } else {
                logger.debug("Lock document does not exist, nothing to release");
            }

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // Log but don't throw - lock will expire via TTL
            logger.warn("Failed to release scheduler lock (will expire via TTL): {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Check if a lock is currently held (for monitoring/debugging).
     *
     * @return Lock status information, or null if no lock exists
     */
    public LockStatus getLockStatus() {
        try {
            DocumentReference lockRef = firestore.collection(COLLECTION_NAME).document(LOCK_DOCUMENT_ID);
            ApiFuture<DocumentSnapshot> future = lockRef.get();
            DocumentSnapshot document = future.get(timeoutProperties.getRead(), TimeUnit.SECONDS);

            if (!document.exists()) {
                return null;
            }

            String lockedBy = document.getString("lockedBy");
            String runId = document.getString("runId");
            Timestamp lockedAt = document.getTimestamp("lockedAt");
            Timestamp expiresAt = document.getTimestamp("expiresAt");

            Instant expiresInstant = expiresAt != null
                    ? Instant.ofEpochSecond(expiresAt.getSeconds(), expiresAt.getNanos())
                    : null;
            boolean isExpired = expiresInstant != null && expiresInstant.isBefore(Instant.now());

            return new LockStatus(lockedBy, runId, lockedAt, expiresAt, isExpired);

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.warn("Failed to get lock status: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Lock status information for monitoring.
     */
    public record LockStatus(
            String lockedBy,
            String runId,
            Timestamp lockedAt,
            Timestamp expiresAt,
            boolean isExpired
    ) {}
}
