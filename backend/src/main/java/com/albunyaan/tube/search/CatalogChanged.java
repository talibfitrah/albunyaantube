package com.albunyaan.tube.search;

import java.util.concurrent.ExecutionException;

/**
 * An invalidation (F3): published after a catalog write, and also when the write's ack failed, since a
 * lost ack may hide a commit. The index re-reads the document by id. `type` is CHANNEL, PLAYLIST or
 * VIDEO; `docId` the Firestore document id.
 */
public record CatalogChanged(String type, String docId) {

    /** A conditional write rejected by its own status or existence check inside the transaction: nothing was written. */
    public static boolean notWritten(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause instanceof IllegalStateException || cause instanceof IllegalArgumentException;
    }
}
