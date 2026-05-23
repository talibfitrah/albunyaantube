package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.registry.PreviewMetadata;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.model.VideoType;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests for {@link RegistrySubmissionWriter}.
 * Verifies the role-aware status normalisation + per-type field mapping that
 * both the bulk-submit pipeline and any future single-add rewire will rely on.
 */
class RegistrySubmissionWriterTest {

    private ChannelRepository channels;
    private PlaylistRepository playlists;
    private VideoRepository videos;
    private RegistrySubmissionWriter writer;

    @BeforeEach
    void setUp() {
        channels = mock(ChannelRepository.class);
        playlists = mock(PlaylistRepository.class);
        videos = mock(VideoRepository.class);
        writer = new RegistrySubmissionWriter(channels, playlists, videos);
    }

    @Test
    void writeChannel_moderatorPath_persistsPending() throws Exception {
        when(channels.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId("doc-c-1");
            return c;
        });
        var meta = new PreviewMetadata("UC123", "Some Channel", "thumb.jpg",
                null, null, 12345L, null, null, null);

        String id = writer.writeChannel(meta, List.of("cat1"), "PENDING", "moderator-uid", false);

        assertEquals("doc-c-1", id);
        ArgumentCaptor<Channel> cap = ArgumentCaptor.forClass(Channel.class);
        verify(channels).save(cap.capture());
        Channel saved = cap.getValue();
        assertEquals("UC123", saved.getYoutubeId());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("moderator-uid", saved.getSubmittedBy());
        assertNull(saved.getApprovedBy());
        assertEquals(List.of("cat1"), saved.getCategoryIds());
    }

    @Test
    void writeChannel_adminApproved_setsApprovedBy() throws Exception {
        when(channels.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId("doc-c-2");
            return c;
        });
        var meta = new PreviewMetadata("UC123", "Some Channel", "thumb.jpg",
                null, null, null, null, null, null);

        writer.writeChannel(meta, List.of("cat1"), "APPROVED", "admin-uid", true);

        ArgumentCaptor<Channel> cap = ArgumentCaptor.forClass(Channel.class);
        verify(channels).save(cap.capture());
        Channel saved = cap.getValue();
        assertEquals("APPROVED", saved.getStatus());
        assertEquals("admin-uid", saved.getApprovedBy());
    }

    @Test
    void writeVideo_propagatesVideoType() throws Exception {
        when(videos.save(any(Video.class))).thenAnswer(inv -> {
            Video v = inv.getArgument(0);
            v.setId("doc-v-1");
            return v;
        });
        var meta = new PreviewMetadata("vid123", "A Live", "thumb.jpg",
                "Some Channel", "UC123", null, null, null, 999L);

        writer.writeVideo(meta, VideoType.LIVE, List.of("cat1"), "PENDING", "u", false);

        ArgumentCaptor<Video> cap = ArgumentCaptor.forClass(Video.class);
        verify(videos).save(cap.capture());
        assertEquals(VideoType.LIVE, cap.getValue().getVideoType());
    }

    @Test
    void writeVideo_nullVideoType_defaultsToStandard() throws Exception {
        when(videos.save(any(Video.class))).thenAnswer(inv -> {
            Video v = inv.getArgument(0);
            v.setId("doc-v-2");
            return v;
        });
        var meta = new PreviewMetadata("vid123", "Plain", null,
                null, null, null, null, null, null);

        writer.writeVideo(meta, null, List.of("cat1"), "PENDING", "u", false);

        ArgumentCaptor<Video> cap = ArgumentCaptor.forClass(Video.class);
        verify(videos).save(cap.capture());
        assertEquals(VideoType.STANDARD, cap.getValue().getVideoType());
    }

    @Test
    void writePlaylist_moderatorPath_persistsPending() throws Exception {
        when(playlists.save(any(Playlist.class))).thenAnswer(inv -> {
            Playlist p = inv.getArgument(0);
            p.setId("doc-p-1");
            return p;
        });
        var meta = new PreviewMetadata("PL123", "Some Playlist", "thumb.jpg",
                "Channel", "UC1", null, 20L, null, null);

        writer.writePlaylist(meta, List.of("cat1"), "PENDING", "mod-uid", false);

        ArgumentCaptor<Playlist> cap = ArgumentCaptor.forClass(Playlist.class);
        verify(playlists).save(cap.capture());
        Playlist saved = cap.getValue();
        assertEquals("PL123", saved.getYoutubeId());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("mod-uid", saved.getSubmittedBy());
    }
}
