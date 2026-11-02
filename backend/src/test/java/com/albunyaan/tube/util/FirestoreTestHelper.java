package com.albunyaan.tube.util;

import com.google.cloud.firestore.Firestore;
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
     * Clear all documents from a collection, subcollections included.
     * Firestore (and its emulator) never cascades a document delete into its subcollections, so a
     * per-document delete left users/{uid}/subscriptions etc. behind for later classes and later
     * runs on the same emulator. recursiveDelete also reaches subcollections of missing parents.
     */
    public static void clearCollection(Firestore firestore, String collectionName)
            throws ExecutionException, InterruptedException {
        log.debug("Recursively clearing collection: {}", collectionName);
        firestore.recursiveDelete(firestore.collection(collectionName)).get();
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

