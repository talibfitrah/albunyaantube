package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ArchiveProjectorTest {

    private ChannelRepository channels;
    private PlaylistRepository playlists;
    private VideoRepository videos;
    private ArchiveProjector projector;

    @BeforeEach
    void setUp() {
        channels  = Mockito.mock(ChannelRepository.class);
        playlists = Mockito.mock(PlaylistRepository.class);
        videos    = Mockito.mock(VideoRepository.class);
        projector = new ArchiveProjector(channels, playlists, videos);
    }

    @Test
    void nonArchivedSubscriptionPassesThroughUnchanged() {
        when(channels.isArchivedById("UC1")).thenReturn(false);
        RawRow row = new RawRow("UC1", Map.of("deleted", false), 100L);
        assertSame(row, projector.projectSubscription(row));
    }

    @Test
    void archivedSubscriptionBecomesVirtualTombstone() {
        when(channels.isArchivedById("UC2")).thenReturn(true);
        RawRow row = new RawRow("UC2", Map.of("deleted", false, "name", "X"), 200L);
        RawRow out = projector.projectSubscription(row);
        assertEquals("UC2", out.id());
        assertTrue((Boolean) out.data().get("deleted"));
        assertEquals(200L, out.updatedAt());
    }

    @Test
    void existingRealTombstonePassesThroughUnchanged() {
        when(channels.isArchivedById("UC3")).thenReturn(true);  // even if archived, don't double-process
        RawRow row = new RawRow("UC3", Map.of("deleted", true), 300L);
        assertSame(row, projector.projectSubscription(row));
    }

    @Test
    void archivedPlaylistAndFavoriteSimilarlyTombstoned() {
        when(playlists.isArchivedById("PL1")).thenReturn(true);
        when(videos.isArchivedById("V1")).thenReturn(true);
        RawRow pl = new RawRow("PL1", Map.of("deleted", false), 1L);
        RawRow fv = new RawRow("V1",  Map.of("deleted", false), 2L);
        assertTrue((Boolean) projector.projectPlaylist(pl).data().get("deleted"));
        assertTrue((Boolean) projector.projectFavorite(fv).data().get("deleted"));
    }
}
