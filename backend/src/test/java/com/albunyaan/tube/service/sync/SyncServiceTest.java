package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.dto.sync.*;
import com.albunyaan.tube.repository.SyncRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

class SyncServiceTest {

    private SyncRepository repo;
    private ArchiveProjector projector;
    private SyncService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(SyncRepository.class);
        projector = Mockito.mock(ArchiveProjector.class);
        service = new SyncService(repo, projector);
    }

    @Test
    void pullReturnsEmptyPagesWhenRepoEmpty() throws Exception {
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), isNull(), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());

        SyncResponseDto resp = service.pull("u1", new SyncCursors(0L, 0L, 0L));

        assertNotNull(resp.getSubscriptions().getItems());
        assertTrue(resp.getSubscriptions().getItems().isEmpty());
        assertNull(resp.getSubscriptions().getNextCursor());
    }

    @Test
    void pullSetsNextCursorWhenPageSaturates() throws Exception {
        // 500 rows = saturation → nextCursor = last row's updatedAt
        List<RawRow> rows = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> new RawRow("ch" + i, Map.of("deleted", false,
                        "channelUrl", "u" + i, "name", "n" + i, "subscribedAt", (long) i), 1000L + i))
                .toList();
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), isNull(), eq(500))).thenReturn(rows);
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());
        // SYNC-TAIL-01 + R7 P2 ArchiveProjector-batch fix: prod code calls
        // projector.projectSubscriptions(List<RawRow>) (batch), not the
        // per-row projectSubscription. Stub the batch method to passthrough.
        when(projector.projectSubscriptions(rows)).thenReturn(rows);

        SyncResponseDto resp = service.pull("u1", new SyncCursors(0L, 0L, 0L));

        assertEquals(500, resp.getSubscriptions().getItems().size());
        assertEquals(Long.valueOf(1000L + 499), resp.getSubscriptions().getNextCursor());
    }

    @Test
    void pullPartialPage_mintsCursorFromLastRow() throws Exception {
        // SYNC-TAIL-01 — partial-tail page (3 rows < PAGE_SIZE=500) must
        // still return a non-null cursor pointing past the last row.
        // Pre-fix this returned null and the client stalled on the tail.
        List<RawRow> rows = List.of(
                new RawRow("ch1", Map.of("deleted", false, "channelUrl","u","name","n","subscribedAt",1L), 100L),
                new RawRow("ch2", Map.of("deleted", false, "channelUrl","u","name","n","subscribedAt",1L), 200L),
                new RawRow("ch3", Map.of("deleted", false, "channelUrl","u","name","n","subscribedAt",1L), 300L));
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), isNull(), eq(500))).thenReturn(rows);
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());
        when(projector.projectSubscriptions(rows)).thenReturn(rows);

        SyncResponseDto resp = service.pull("u1", new SyncCursors(0L, 0L, 0L));

        assertEquals(3, resp.getSubscriptions().getItems().size());
        assertEquals(Long.valueOf(300L), resp.getSubscriptions().getNextCursor());
        assertEquals("ch3", resp.getSubscriptions().getNextCursorId());
    }

    @Test
    void pullEmptyPage_returnsNullCursor() throws Exception {
        // SYNC-TAIL-01 — only truly empty pages return null. Confirms the
        // "items.isEmpty() && nextCursor == null" contract clients use to
        // detect end-of-iteration.
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), isNull(), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), isNull(), eq(500))).thenReturn(List.of());

        SyncResponseDto resp = service.pull("u1", new SyncCursors(0L, 0L, 0L));

        assertEquals(0, resp.getSubscriptions().getItems().size());
        org.junit.jupiter.api.Assertions.assertNull(resp.getSubscriptions().getNextCursor());
    }

    @Test
    void pullPassesEachRowThroughCorrectProjectorBranch() throws Exception {
        RawRow s = new RawRow("ch1", Map.of("deleted", false, "channelUrl","u","name","n","subscribedAt",1L), 10L);
        RawRow p = new RawRow("pl1", Map.of("deleted", false, "playlistUrl","u","name","n","savedAt",1L), 20L);
        RawRow v = new RawRow("v1",  Map.of("deleted", false, "title","t","channelName","c","durationSeconds",10L,"addedAt",1L), 30L);
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), isNull(), eq(500))).thenReturn(List.of(s));
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), isNull(), eq(500))).thenReturn(List.of(p));
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), isNull(), eq(500))).thenReturn(List.of(v));
        // Prod uses batch projection (Cubic R7 P2 ArchiveProjector immutability + N+1 fix).
        when(projector.projectSubscriptions(List.of(s))).thenReturn(List.of(s));
        when(projector.projectPlaylists(List.of(p))).thenReturn(List.of(p));
        when(projector.projectFavorites(List.of(v))).thenReturn(List.of(v));

        service.pull("u1", new SyncCursors(0L, 0L, 0L));

        Mockito.verify(projector).projectSubscriptions(List.of(s));
        Mockito.verify(projector).projectPlaylists(List.of(p));
        Mockito.verify(projector).projectFavorites(List.of(v));
        Mockito.verifyNoMoreInteractions(projector);
    }

    @Test
    void upsertSubscriptionPersistsBodyAndEchoesUpdatedAt() throws Exception {
        var req = new PutSubscriptionRequest();
        req.setChannelUrl("u"); req.setName("n"); req.setSubscribedAt(50L);
        when(repo.upsert(eq("u1"), eq("subscriptions"), eq("ch1"), Mockito.anyMap()))
                .thenReturn(new RawRow("ch1", Map.of("deleted", false), 1234L));

        SubscriptionSyncDto out = service.upsertSubscription("u1", "ch1", req);

        assertEquals("ch1", out.getEntityId());
        assertFalse(out.isDeleted());
        assertEquals(1234L, out.getUpdatedAt());
    }

    @Test
    void tombstoneSubscriptionEchoesDeletedTrue() throws Exception {
        when(repo.tombstone(eq("u1"), eq("subscriptions"), eq("ch1")))
                .thenReturn(new RawRow("ch1", Map.of("deleted", true), 5678L));

        SubscriptionSyncDto out = service.tombstoneSubscription("u1", "ch1");

        assertEquals("ch1", out.getEntityId());
        assertTrue(out.isDeleted());
        assertEquals(5678L, out.getUpdatedAt());
    }
}
