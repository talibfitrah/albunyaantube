package com.albunyaan.tube.integration;

import com.albunyaan.tube.util.FirestoreTestHelper;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.ExecutionException;

/**
 * BACKEND-TEST-01: Base Integration Test
 *
 * Base class for integration tests with Firestore setup/teardown.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected Firestore firestore;

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
    public void setUpFirestore() throws ExecutionException, InterruptedException {
        // Clear all test collections before each test
        FirestoreTestHelper.clearCollections(firestore, getCollectionsToClean());
    }

    @AfterEach
    public void tearDownFirestore() throws ExecutionException, InterruptedException {
        // Clear all test collections after each test
        FirestoreTestHelper.clearCollections(firestore, getCollectionsToClean());
    }

    /**
     * Helper method to count documents in a collection.
     */
    protected int countDocuments(String collectionName) throws ExecutionException, InterruptedException {
        return FirestoreTestHelper.countDocuments(firestore, collectionName);
    }

    /**
     * Helper method to check if a document exists.
     */
    protected boolean documentExists(String collectionName, String documentId)
            throws ExecutionException, InterruptedException {
        return FirestoreTestHelper.documentExists(firestore, collectionName, documentId);
    }
}
