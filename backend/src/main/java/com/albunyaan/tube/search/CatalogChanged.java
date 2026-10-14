package com.albunyaan.tube.search;

import com.google.api.core.ApiFuture;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An invalidation (F3): published after a catalog write, and also when the write's ack failed, since a
 * lost ack may hide a commit. The index re-reads the document by id. `type` is CHANNEL, PLAYLIST or
 * VIDEO; `docId` the Firestore document id. `writeFailed`: the ack failed or timed out, so the write may
 * still commit after that re-read, and the index reads it again later (B2).
 */
public record CatalogChanged(String type, String docId, boolean writeFailed) {

    /**
     * Awaits a write's ack, then publishes its invalidation whatever the outcome: a failed ack may hide a commit
     * (F3), and a retried transaction that committed can look like a miss (N2). The re-read decides.
     */
    public static <T> T await(ApiFuture<T> write, long timeoutSeconds, ApplicationEventPublisher events,
                              String type, String docId)
            throws ExecutionException, InterruptedException, TimeoutException {
        boolean failed = true;
        try {
            T result = write.get(timeoutSeconds, TimeUnit.SECONDS);
            failed = false;
            return result;
        } finally {
            events.publishEvent(new CatalogChanged(type, docId, failed));
        }
    }
}
