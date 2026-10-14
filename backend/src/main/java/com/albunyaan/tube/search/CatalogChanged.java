package com.albunyaan.tube.search;

/**
 * An invalidation (F3): published after a catalog write, and also when the write's ack failed, since a
 * lost ack may hide a commit. The index re-reads the document by id. `type` is CHANNEL, PLAYLIST or
 * VIDEO; `docId` the Firestore document id.
 */
public record CatalogChanged(String type, String docId) {
}
