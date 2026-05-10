package com.albunyaan.tube.integration;

import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.util.FirestoreTestHelper;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutionException;

/**
 * BACKEND-TEST-01: Base Integration Test
 *
 * Base class for integration tests with Firestore setup/teardown.
 * Tagged with @Tag("integration") to allow selective test execution.
 * Run with: ./gradlew test -Pintegration=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Import(IntegrationTestConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected Firestore firestore;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RestTemplate restTemplate;

    @Autowired
    protected CacheManager cacheManager;

    /**
     * Collections to clear before/after each test.
     * Subclasses can override this to add more collections.
     */
    protected String[] getCollectionsToClean() {
        return new String[]{
                "categories",
                "channels",
                "playlists",
                "videos",
                "users",
                "audit_logs"
        };
    }

    @BeforeEach
    public void setUpFirestore() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Clear in-memory caches first so freshly-seeded data is observed by services
        // (cacheManager is a singleton across the test context, so prior tests pollute it).
        clearAllCaches();
        // Clear all test collections before each test
        FirestoreTestHelper.clearCollections(firestore, getCollectionsToClean());
    }

    @AfterEach
    public void tearDownFirestore() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Clear all test collections after each test
        FirestoreTestHelper.clearCollections(firestore, getCollectionsToClean());
        // Clear caches to prevent later tests from observing this test's state via cache.
        clearAllCaches();
    }

    private void clearAllCaches() {
        if (cacheManager == null) {
            return;
        }
        for (String cacheName : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /**
     * Helper method to count documents in a collection.
     */
    protected int countDocuments(String collectionName) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return FirestoreTestHelper.countDocuments(firestore, collectionName);
    }

    /**
     * Helper method to check if a document exists.
     */
    protected boolean documentExists(String collectionName, String documentId)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return FirestoreTestHelper.documentExists(firestore, collectionName, documentId);
    }
}

