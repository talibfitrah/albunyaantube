package com.albunyaan.tube.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncRepositoryTest {

    @Test
    void pageSizeConstantIs500() {
        assertEquals(500, SyncRepository.SYNC_PAGE_SIZE);
    }

    @Test
    void collectionNamesAreStable() {
        assertEquals("subscriptions", SyncRepository.SUBS_COLL);
        assertEquals("playlists",     SyncRepository.PLAYLISTS_COLL);
        assertEquals("favorites",     SyncRepository.FAVORITES_COLL);
    }
}
