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
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), eq(500))).thenReturn(List.of());

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
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), eq(500))).thenReturn(rows);
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), eq(500))).thenReturn(List.of());
        for (RawRow r : rows) when(projector.projectSubscription(r)).thenReturn(r);

        SyncResponseDto resp = service.pull("u1", new SyncCursors(0L, 0L, 0L));

        assertEquals(500, resp.getSubscriptions().getItems().size());
        assertEquals(Long.valueOf(1000L + 499), resp.getSubscriptions().getNextCursor());
    }

    @Test
    void pullPassesEachRowThroughCorrectProjectorBranch() throws Exception {
        RawRow s = new RawRow("ch1", Map.of("deleted", false, "channelUrl","u","name","n","subscribedAt",1L), 10L);
        RawRow p = new RawRow("pl1", Map.of("deleted", false, "playlistUrl","u","name","n","savedAt",1L), 20L);
        RawRow v = new RawRow("v1",  Map.of("deleted", false, "title","t","channelName","c","durationSeconds",10L,"addedAt",1L), 30L);
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), eq(500))).thenReturn(List.of(s));
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), eq(500))).thenReturn(List.of(p));
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), eq(500))).thenReturn(List.of(v));
        when(projector.projectSubscription(s)).thenReturn(s);
        when(projector.projectPlaylist(p)).thenReturn(p);
        when(projector.projectFavorite(v)).thenReturn(v);

        service.pull("u1", new SyncCursors(0L, 0L, 0L));

        Mockito.verify(projector).projectSubscription(s);
        Mockito.verify(projector).projectPlaylist(p);
        Mockito.verify(projector).projectFavorite(v);
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
