package com.albunyaan.tube.integration;

import com.albunyaan.tube.repository.SystemSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for distributed locking in VideoValidationScheduler.
 *
 * Tests verify:
 * - Lock acquisition succeeds when no lock exists
 * - Lock acquisition fails when valid lock held by another instance
 * - Lock acquisition succeeds when lock is expired (TTL passed)
 * - Lock release deletes document
 * - Two backend instances cannot run validation simultaneously
 * - Simulated crash scenario: lock expires after TTL, next run proceeds
 */
public class SchedulerLockIntegrationTest extends BaseIntegrationTest {

    private static final String LOCK_KEY = "video_validation_scheduler";
    private static final int TTL_SECONDS = 5; // Short TTL for testing

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{
                "system_settings",
                "categories",
                "channels",
                "playlists",
                "videos"
        };
    }

    @Test
    @DisplayName("Lock acquisition should succeed when no lock exists")
    void lockAcquisition_shouldSucceedWhenNoLockExists() {
        // Arrange
        String instanceId = "instance-" + UUID.randomUUID();

        // Act
        boolean acquired = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instanceId, TTL_SECONDS);

        // Assert
        assertTrue(acquired, "Lock should be acquired when no lock exists");
        assertTrue(systemSettingsRepository.isLockHeld(LOCK_KEY), "Lock should be held after acquisition");
    }

    @Test
    @DisplayName("Lock acquisition should fail when valid lock held by another instance")
    void lockAcquisition_shouldFailWhenHeldByAnotherInstance() {
        // Arrange - first instance acquires lock
        String instance1 = "instance-1-" + UUID.randomUUID();
        String instance2 = "instance-2-" + UUID.randomUUID();

        boolean acquired1 = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance1, TTL_SECONDS);
        assertTrue(acquired1, "First instance should acquire lock");

        // Act - second instance attempts to acquire
        boolean acquired2 = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance2, TTL_SECONDS);

        // Assert
        assertFalse(acquired2, "Second instance should NOT acquire lock held by another");
    }

    @Test
    @DisplayName("Lock acquisition should succeed when lock is expired (TTL passed)")
    void lockAcquisition_shouldSucceedWhenLockExpired() throws Exception {
        // Arrange - first instance acquires lock with very short TTL
        String instance1 = "instance-1-" + UUID.randomUUID();
        String instance2 = "instance-2-" + UUID.randomUUID();
        int shortTtl = 1; // 1 second

        boolean acquired1 = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance1, shortTtl);
        assertTrue(acquired1, "First instance should acquire lock");

        // Wait for TTL to expire
        Thread.sleep(1500); // Wait 1.5 seconds

        // Act - second instance attempts to acquire after expiry
        boolean acquired2 = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance2, TTL_SECONDS);

        // Assert
        assertTrue(acquired2, "Second instance should acquire expired lock");
    }

    @Test
    @DisplayName("Lock release should delete document on normal completion")
    void lockRelease_shouldDeleteDocument() {
        // Arrange
        String instanceId = "instance-" + UUID.randomUUID();
        boolean acquired = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instanceId, TTL_SECONDS);
        assertTrue(acquired);
        assertTrue(systemSettingsRepository.isLockHeld(LOCK_KEY));

        // Act
        boolean released = systemSettingsRepository.releaseLock(LOCK_KEY, instanceId);

        // Assert
        assertTrue(released, "Lock release should succeed");
        assertFalse(systemSettingsRepository.isLockHeld(LOCK_KEY), "Lock should no longer be held after release");
    }

    @Test
    @DisplayName("Lock release should fail when attempting to release lock held by another instance")
    void lockRelease_shouldFailWhenHeldByAnother() {
        // Arrange
        String instance1 = "instance-1-" + UUID.randomUUID();
        String instance2 = "instance-2-" + UUID.randomUUID();

        boolean acquired = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance1, TTL_SECONDS);
        assertTrue(acquired);

        // Act - instance 2 tries to release instance 1's lock
        boolean released = systemSettingsRepository.releaseLock(LOCK_KEY, instance2);

        // Assert
        assertFalse(released, "Should not be able to release another instance's lock");
        assertTrue(systemSettingsRepository.isLockHeld(LOCK_KEY), "Lock should still be held by instance 1");
    }

    @Test
    @DisplayName("Two instances cannot hold the lock simultaneously")
    void twoInstances_cannotHoldLockSimultaneously() throws Exception {
        // Arrange
        String instance1 = "instance-1-" + UUID.randomUUID();
        String instance2 = "instance-2-" + UUID.randomUUID();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // Track concurrent lock holders to verify mutual exclusion
        AtomicInteger currentHolders = new AtomicInteger(0);
        AtomicInteger maxConcurrentHolders = new AtomicInteger(0);
        AtomicInteger totalAcquisitions = new AtomicInteger(0);

        // Act - both instances try to acquire simultaneously
        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                if (systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance1, TTL_SECONDS)) {
                    totalAcquisitions.incrementAndGet();
                    int holders = currentHolders.incrementAndGet();
                    // Update max concurrent holders atomically
                    maxConcurrentHolders.updateAndGet(max -> Math.max(max, holders));
                    try {
                        Thread.sleep(100); // Hold lock briefly
                    } finally {
                        currentHolders.decrementAndGet();
                        systemSettingsRepository.releaseLock(LOCK_KEY, instance1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                if (systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance2, TTL_SECONDS)) {
                    totalAcquisitions.incrementAndGet();
                    int holders = currentHolders.incrementAndGet();
                    // Update max concurrent holders atomically
                    maxConcurrentHolders.updateAndGet(max -> Math.max(max, holders));
                    try {
                        Thread.sleep(100); // Hold lock briefly
                    } finally {
                        currentHolders.decrementAndGet();
                        systemSettingsRepository.releaseLock(LOCK_KEY, instance2);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        t1.start();
        t2.start();
        startLatch.countDown(); // Release both threads

        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Both threads should complete");

        // Assert - verify mutual exclusion: max concurrent holders must be exactly 1
        assertTrue(totalAcquisitions.get() >= 1, "At least one instance should acquire the lock");
        assertEquals(1, maxConcurrentHolders.get(),
                "Only one instance should hold the lock at any time (mutual exclusion violated if > 1)");
    }

    @Test
    @DisplayName("Simulated crash: lock expires after TTL allowing next run")
    void simulatedCrash_lockExpiresAfterTtl() throws Exception {
        // Arrange - simulate instance 1 acquiring lock and "crashing" (not releasing)
        String instance1 = "instance-1-" + UUID.randomUUID();
        String instance2 = "instance-2-" + UUID.randomUUID();
        int shortTtl = 1; // 1 second TTL

        // Instance 1 acquires lock
        boolean acquired1 = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance1, shortTtl);
        assertTrue(acquired1, "Instance 1 should acquire lock");

        // Verify lock is held
        assertTrue(systemSettingsRepository.isLockHeld(LOCK_KEY));

        // Simulate crash - instance 1 never releases the lock
        // Wait for TTL to expire
        Thread.sleep(1500);

        // Act - instance 2 should be able to acquire after TTL expires
        boolean acquired2 = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instance2, TTL_SECONDS);

        // Assert
        assertTrue(acquired2, "Instance 2 should acquire lock after TTL expiry (simulated crash recovery)");
    }

    @Test
    @DisplayName("Lock extension (heartbeat) should work for same instance")
    void lockExtension_shouldWorkForSameInstance() throws Exception {
        // Arrange
        String instanceId = "instance-" + UUID.randomUUID();
        int shortTtl = 2; // 2 second TTL

        boolean acquired = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instanceId, shortTtl);
        assertTrue(acquired);

        // Wait 1 second (before TTL expires)
        Thread.sleep(1000);

        // Act - same instance extends the lock
        boolean extended = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instanceId, shortTtl);

        // Assert
        assertTrue(extended, "Same instance should be able to extend (heartbeat) the lock");

        // Wait another 1.5 seconds - without extension, TTL would have expired
        Thread.sleep(1500);

        // Lock should still be held (because we extended it)
        assertTrue(systemSettingsRepository.isLockHeld(LOCK_KEY), "Lock should still be held after extension");
    }

    @Test
    @DisplayName("Lock data should contain correct metadata")
    void lockData_shouldContainCorrectMetadata() throws Exception {
        // Arrange
        String instanceId = "instance-" + UUID.randomUUID();

        // Act
        boolean acquired = systemSettingsRepository.tryAcquireLock(LOCK_KEY, instanceId, TTL_SECONDS);
        assertTrue(acquired);

        // Assert - verify lock document structure
        Optional<Map<String, Object>> lockData = systemSettingsRepository.load("lock_" + LOCK_KEY);
        assertTrue(lockData.isPresent(), "Lock document should exist");

        Map<String, Object> data = lockData.get();
        assertEquals(instanceId, data.get("heldBy"), "heldBy should match instance ID");
        assertNotNull(data.get("acquiredAt"), "acquiredAt should be set");
        assertNotNull(data.get("expiresAt"), "expiresAt should be set");

        long expiresAt = ((Number) data.get("expiresAt")).longValue();
        assertTrue(expiresAt > System.currentTimeMillis(), "expiresAt should be in the future");
    }
}
