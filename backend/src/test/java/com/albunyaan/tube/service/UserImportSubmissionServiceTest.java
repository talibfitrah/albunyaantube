package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.importflow.ImportDisposition;
import com.albunyaan.tube.dto.importflow.ImportItem;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserImportSubmissionServiceTest {

    @Mock ChannelRepository channels;
    @Mock PlaylistRepository playlists;
    @Mock VideoRepository videos;

    UserImportSubmissionService svc;

    @BeforeEach
    void setup() throws Exception {
        MockitoAnnotations.openMocks(this);
        // save() returns the passed entity unchanged — mirrors Firestore repo behaviour
        when(channels.save(any(Channel.class))).thenAnswer(i -> i.getArgument(0));
        when(playlists.save(any(Playlist.class))).thenAnswer(i -> i.getArgument(0));
        when(videos.save(any(Video.class))).thenAnswer(i -> i.getArgument(0));
        svc = new UserImportSubmissionService(channels, playlists, videos);
    }

    // ── CHANNEL ─────────────────────────────────────────────────────────────

    @Test
    void unknownChannelCreatesPendingEmptyCategoriesUserImport() throws Exception {
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.empty());

        ImportItem item = new ImportItem(YouTubeContentType.CHANNEL, "UC1", "Chan",
                "https://yt3.ggpht.com/thumb.jpg", null);
        ImportDisposition d = svc.submit(item, "uid-7");

        assertEquals(ImportDisposition.PENDING, d);

        ArgumentCaptor<Channel> cap = ArgumentCaptor.forClass(Channel.class);
        verify(channels).save(cap.capture());
        Channel saved = cap.getValue();

        assertEquals("UC1", saved.getYoutubeId());
        assertEquals("Chan", saved.getName());
        assertEquals("https://yt3.ggpht.com/thumb.jpg", saved.getThumbnailUrl());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("USER_IMPORT", saved.getSource());
        assertEquals("uid-7", saved.getSubmittedBy());
        assertTrue(saved.getCategoryIds() == null || saved.getCategoryIds().isEmpty());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void existingApprovedChannelReturnsApprovedNoDuplicate() throws Exception {
        Channel existing = new Channel();
        existing.setStatus("APPROVED");
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.CHANNEL, "UC1", "x", null, null), "uid-7");

        assertEquals(ImportDisposition.APPROVED, d);
        verify(channels, never()).save(any());
    }

    @Test
    void existingPendingChannelReturnsPending() throws Exception {
        Channel existing = new Channel();
        existing.setStatus("PENDING");
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.CHANNEL, "UC1", "x", null, null), "uid-7");

        assertEquals(ImportDisposition.PENDING, d);
        verify(channels, never()).save(any());
    }

    @Test
    void existingRejectedChannelReturnsRejected() throws Exception {
        Channel existing = new Channel();
        existing.setStatus("REJECTED");
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.CHANNEL, "UC1", "x", null, null), "uid-7");

        assertEquals(ImportDisposition.REJECTED, d);
        verify(channels, never()).save(any());
    }

    // ── PLAYLIST ─────────────────────────────────────────────────────────────

    @Test
    void unknownPlaylistCreatesPendingEmptyCategoriesUserImport() throws Exception {
        when(playlists.findByYoutubeId("PL1")).thenReturn(Optional.empty());

        ImportItem item = new ImportItem(YouTubeContentType.PLAYLIST, "PL1", "My Playlist",
                "https://i.ytimg.com/vi/abc/hqdefault.jpg", "UC1");
        ImportDisposition d = svc.submit(item, "uid-8");

        assertEquals(ImportDisposition.PENDING, d);

        ArgumentCaptor<Playlist> cap = ArgumentCaptor.forClass(Playlist.class);
        verify(playlists).save(cap.capture());
        Playlist saved = cap.getValue();

        assertEquals("PL1", saved.getYoutubeId());
        assertEquals("My Playlist", saved.getTitle());
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", saved.getThumbnailUrl());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("USER_IMPORT", saved.getSource());
        assertEquals("uid-8", saved.getSubmittedBy());
        assertTrue(saved.getCategoryIds() == null || saved.getCategoryIds().isEmpty());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void existingApprovedPlaylistReturnsApprovedNoDuplicate() throws Exception {
        Playlist existing = new Playlist();
        existing.setStatus("APPROVED");
        when(playlists.findByYoutubeId("PL1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.PLAYLIST, "PL1", "x", null, null), "uid-8");

        assertEquals(ImportDisposition.APPROVED, d);
        verify(playlists, never()).save(any());
    }

    @Test
    void existingRejectedPlaylistReturnsRejected() throws Exception {
        Playlist existing = new Playlist();
        existing.setStatus("REJECTED");
        when(playlists.findByYoutubeId("PL1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.PLAYLIST, "PL1", "x", null, null), "uid-8");

        assertEquals(ImportDisposition.REJECTED, d);
        verify(playlists, never()).save(any());
    }

    @Test
    void existingPendingPlaylistReturnsPending() throws Exception {
        Playlist existing = new Playlist();
        existing.setStatus("PENDING");
        when(playlists.findByYoutubeId("PL1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.PLAYLIST, "PL1", "x", null, null), "uid-8");

        assertEquals(ImportDisposition.PENDING, d);
        verify(playlists, never()).save(any());
    }

    // ── VIDEO ─────────────────────────────────────────────────────────────

    @Test
    void unknownVideoCreatesPendingEmptyCategoriesUserImport() throws Exception {
        when(videos.findByYoutubeId("VID1")).thenReturn(Optional.empty());

        ImportItem item = new ImportItem(YouTubeContentType.VIDEO, "VID1", "Cool Video",
                "https://i.ytimg.com/vi/VID1/hqdefault.jpg", "UC1");
        ImportDisposition d = svc.submit(item, "uid-9");

        assertEquals(ImportDisposition.PENDING, d);

        ArgumentCaptor<Video> cap = ArgumentCaptor.forClass(Video.class);
        verify(videos).save(cap.capture());
        Video saved = cap.getValue();

        assertEquals("VID1", saved.getYoutubeId());
        assertEquals("Cool Video", saved.getTitle());
        assertEquals("https://i.ytimg.com/vi/VID1/hqdefault.jpg", saved.getThumbnailUrl());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("USER_IMPORT", saved.getSource());
        assertEquals("uid-9", saved.getSubmittedBy());
        assertTrue(saved.getCategoryIds() == null || saved.getCategoryIds().isEmpty());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals("UC1", saved.getChannelId());
    }

    @Test
    void existingApprovedVideoReturnsApprovedNoDuplicate() throws Exception {
        Video existing = new Video();
        existing.setStatus("APPROVED");
        when(videos.findByYoutubeId("VID1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.VIDEO, "VID1", "x", null, null), "uid-9");

        assertEquals(ImportDisposition.APPROVED, d);
        verify(videos, never()).save(any());
    }

    @Test
    void existingRejectedVideoReturnsRejected() throws Exception {
        Video existing = new Video();
        existing.setStatus("REJECTED");
        when(videos.findByYoutubeId("VID1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.VIDEO, "VID1", "x", null, null), "uid-9");

        assertEquals(ImportDisposition.REJECTED, d);
        verify(videos, never()).save(any());
    }

    @Test
    void existingPendingVideoReturnsPending() throws Exception {
        Video existing = new Video();
        existing.setStatus("PENDING");
        when(videos.findByYoutubeId("VID1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.VIDEO, "VID1", "x", null, null), "uid-9");

        assertEquals(ImportDisposition.PENDING, d);
        verify(videos, never()).save(any());
    }

    // ── THUMBNAIL SANITIZATION ─────────────────────────────────────────────

    @Test
    void disallowedThumbnailHostIsNulledOut() throws Exception {
        when(channels.findByYoutubeId("UC2")).thenReturn(Optional.empty());

        ImportItem item = new ImportItem(YouTubeContentType.CHANNEL, "UC2", "Chan",
                "https://evil.example.com/thumb.jpg", null);
        svc.submit(item, "uid-7");

        ArgumentCaptor<Channel> cap = ArgumentCaptor.forClass(Channel.class);
        verify(channels).save(cap.capture());
        // sanitizeThumbnailUrl rejects non-YouTube CDN hosts — result must be null
        assertNull(cap.getValue().getThumbnailUrl());
    }

    @Test
    void nullThumbnailIsHandledGracefully() throws Exception {
        when(channels.findByYoutubeId("UC3")).thenReturn(Optional.empty());

        ImportItem item = new ImportItem(YouTubeContentType.CHANNEL, "UC3", "Chan", null, null);
        assertDoesNotThrow(() -> svc.submit(item, "uid-7"));
    }

    // ── Finding 3: PERSONAL re-import disposition ──────────────────────────────

    @Test
    void existingPersonalApprovedChannel_grantee_returnsApproved() throws Exception {
        // A personal-approved item is APPROVED for a user it was granted to.
        Channel existing = new Channel();
        existing.setStatus("APPROVED");
        existing.setVisibility("PERSONAL");
        existing.setPersonalGrants(java.util.List.of("uid-7"));
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.CHANNEL, "UC1", "x", null, null), "uid-7");

        assertEquals(ImportDisposition.APPROVED, d);
        verify(channels, never()).save(any());
    }

    @Test
    void existingPersonalApprovedChannel_nonGrantee_returnsPending() throws Exception {
        // A personal item must NOT auto-grant a DIFFERENT importer — they get PENDING, not the content.
        Channel existing = new Channel();
        existing.setStatus("APPROVED");
        existing.setVisibility("PERSONAL");
        existing.setPersonalGrants(java.util.List.of("someone-else"));
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.CHANNEL, "UC1", "x", null, null), "uid-7");

        assertEquals(ImportDisposition.PENDING, d);
        verify(channels, never()).save(any());
    }

    @Test
    void existingPersonalApprovedVideo_nonGrantee_returnsPending() throws Exception {
        Video existing = new Video();
        existing.setStatus("APPROVED");
        existing.setVisibility("PERSONAL");
        existing.setPersonalGrants(java.util.List.of("someone-else"));
        when(videos.findByYoutubeId("VID1")).thenReturn(Optional.of(existing));

        ImportDisposition d = svc.submit(
                new ImportItem(YouTubeContentType.VIDEO, "VID1", "x", null, null), "uid-9");

        assertEquals(ImportDisposition.PENDING, d);
        verify(videos, never()).save(any());
    }
}
