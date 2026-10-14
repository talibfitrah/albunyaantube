package com.albunyaan.tube.search;

/**
 * Published after a catalog write succeeds (spec §5.2). `type` is CHANNEL, PLAYLIST or VIDEO;
 * `docId` the Firestore document id; `entity` the written object, or null when the index must
 * re-read it (partial updates, deletes, bulk batches).
 */
public record CatalogChanged(String type, String docId, Object entity) {}
