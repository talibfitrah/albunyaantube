package com.albunyaan.tube.search;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.integration.BaseIntegrationTest;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The catalog read against the Firestore emulator: what a real query keeps (F6) and what it skips (F4). */
class SearchIndexReadsIT extends BaseIntegrationTest {

    @Autowired
    private SearchIndex searchIndex;

    private void put(String collection, String id, Map<String, Object> fields) throws Exception {
        firestore.collection(collection).document(id).set(fields).get(10, TimeUnit.SECONDS);
    }

    /** The production path (N4): the reconcile queues replaceCatalog on the index's single writer thread; wait for it. */
    private void rebuild() throws Exception {
        searchIndex.reconcile();
        searchIndex.awaitWrites();
    }

    private List<String> channelIds(String query) throws Exception {
        return searchIndex.searchPage(query, "CHANNEL", 10, null, -1).items().stream().map(ContentItemDto::getId).toList();
    }

    @Test
    void anApprovedChannelWithoutCreatedAtIsIndexed() throws Exception {
        // F6: Firestore drops a document that lacks the orderBy field; the index's read must not order.
        put("channels", "no-created-at", Map.of("youtubeId", "UCnocreatedat00000000001", "name", "Noorcreated Lectures",
                "status", "APPROVED"));

        rebuild();

        assertEquals(List.of("UCnocreatedat00000000001"), channelIds("noorcreated"));
    }

    @Test
    void aDocumentThatCannotBeMappedIsSkippedAndTheRestIsIndexed() throws Exception {
        // F4: one bad document must not fail the whole catalog build.
        put("channels", "good", Map.of("youtubeId", "UCwellformed000000000001", "name", "Wellformed Channel",
                "status", "APPROVED", "createdAt", Timestamp.now()));
        put("channels", "bad", Map.of("youtubeId", "UCmalformed0000000000001", "name", "Malformed Channel",
                "status", "APPROVED", "createdAt", Timestamp.now(), "excludedItems", "not a map"));

        rebuild();

        assertEquals(List.of("UCwellformed000000000001"), channelIds("wellformed"));
    }
}
