package com.albunyaan.tube.util;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;

/**
 * BACKEND-TEST-01: Firestore Test Helper
 *
 * Utilities for integration tests with Firestore emulator.
 */
public class FirestoreTestHelper {

    private static final Logger log = LoggerFactory.getLogger(FirestoreTestHelper.class);

    /**
     * Clear all documents from a collection.
     * Useful for cleaning up test data between tests.
     */
    public static void clearCollection(Firestore firestore, String collectionName)
            throws ExecutionException, InterruptedException {
        var docs = firestore.collection(collectionName).get().get().getDocuments();
        log.debug("Clearing {} documents from collection: {}", docs.size(), collectionName);

        for (QueryDocumentSnapshot doc : docs) {
            doc.getReference().delete().get();
        }
    }

    /**
     * Clear all documents from multiple collections.
     */
    public static void clearCollections(Firestore firestore, String... collectionNames)
            throws ExecutionException, InterruptedException {
        for (String collectionName : collectionNames) {
            clearCollection(firestore, collectionName);
        }
    }

    /**
     * Count documents in a collection.
     */
    public static int countDocuments(Firestore firestore, String collectionName)
            throws ExecutionException, InterruptedException {
        return firestore.collection(collectionName).get().get().size();
    }

    /**
     * Check if a document exists.
     */
    public static boolean documentExists(Firestore firestore, String collectionName, String documentId)
            throws ExecutionException, InterruptedException {
        return firestore.collection(collectionName).document(documentId).get().get().exists();
    }

    /**
     * Delete a specific document.
     */
    public static void deleteDocument(Firestore firestore, String collectionName, String documentId)
            throws ExecutionException, InterruptedException {
        firestore.collection(collectionName).document(documentId).delete().get();
    }
}
