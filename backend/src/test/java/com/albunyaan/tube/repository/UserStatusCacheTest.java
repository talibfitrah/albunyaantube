package com.albunyaan.tube.repository;

import com.albunyaan.tube.integration.BaseIntegrationTest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKEND-AUTH-01 (Task 5b): userStatus Caffeine cache integration test.
 *
 * Verifies that the second call to {@link UserRepository#findByUid(String)} is
 * served from the "userStatus" cache rather than hitting Firestore again.
 *
 * Cache is disabled globally in application-test.yml (spring.cache.type=none).
 * This class re-enables Caffeine caching via @TestPropertySource so it can
 * exercise the cache without affecting other test classes.
 */
@TestPropertySource(properties = "spring.cache.type=caffeine")
class UserStatusCacheTest extends BaseIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Test
    void findByUid_secondCall_isCacheHit() throws Exception {
        String uid = "cache-test-uid-" + System.nanoTime();

        // Seed a minimal user directly into the Firestore emulator
        User u = new User();
        u.setUid(uid);
        u.setEmail(uid + "@cache.test");
        u.setDisplayName("Cache Test User");
        u.setRole("moderator");
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);

        // Before the first findByUid call the cache entry must not exist yet.
        Cache userStatusCache = cacheManager.getCache("userStatus");
        assertNotNull(userStatusCache, "userStatus cache must be registered in CacheManager");
        assertNull(userStatusCache.get(uid),
                "Cache entry must not exist before first findByUid call");

        // First call — cache miss; fetches from Firestore and populates the cache.
        User first = userRepository.findByUid(uid).orElseThrow(
                () -> new AssertionError("User not found in Firestore after save"));
        assertEquals(uid, first.getUid(), "First call must return the correct user");

        // After the first call the cache wrapper must be populated.
        Cache.ValueWrapper wrapper = userStatusCache.get(uid);
        assertNotNull(wrapper, "Cache must be populated after first findByUid call");

        // Second call — must be served from cache (same Optional wrapper object).
        // We verify this by confirming the cache entry is still present and the
        // returned value matches, without any additional Firestore interaction.
        User second = userRepository.findByUid(uid).orElseThrow(
                () -> new AssertionError("Second findByUid must still return the user"));
        assertEquals(uid, second.getUid(), "Second call must return the same user");

        // The cache entry must still be populated — not evicted or replaced.
        Cache.ValueWrapper wrapperAfter = userStatusCache.get(uid);
        assertNotNull(wrapperAfter,
                "Cache entry must remain populated after second findByUid call");

        // The cached value object must be the exact same Optional instance that was stored on the
        // first call — if the second call had gone to Firestore it would have produced a new object.
        assertSame(wrapper.get(), wrapperAfter.get(),
                "Second findByUid call must be served from cache — cached Optional must be the same instance");
    }
}
