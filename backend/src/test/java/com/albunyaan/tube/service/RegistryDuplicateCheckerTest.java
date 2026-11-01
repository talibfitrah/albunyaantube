package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistryDuplicateCheckerTest {

    private ChannelRepository channels;
    private PlaylistRepository playlists;
    private VideoRepository videos;
    private RegistryDuplicateChecker checker;

    @BeforeEach
    void setUp() {
        channels = mock(ChannelRepository.class);
        playlists = mock(PlaylistRepository.class);
        videos = mock(VideoRepository.class);
        checker = new RegistryDuplicateChecker(channels, playlists, videos);
    }

    @Test
    void noExisting_returnsEmpty() throws ExecutionException, InterruptedException, TimeoutException {
        when(channels.findByYoutubeId("UC123")).thenReturn(Optional.empty());
        var batch = checker.newBatch();
        assertTrue(batch.findExisting(YouTubeContentType.CHANNEL, "UC123").isEmpty());
    }

    @Test
    void existingPendingChannel_returnsMatch() throws ExecutionException, InterruptedException, TimeoutException {
        Channel c = new Channel("UC123");
        c.setId("doc-1");
        c.setStatus("PENDING");
        when(channels.findByYoutubeId("UC123")).thenReturn(Optional.of(c));
        var batch = checker.newBatch();

        var hit = batch.findExisting(YouTubeContentType.CHANNEL, "UC123");
        assertTrue(hit.isPresent());
        assertEquals("doc-1", hit.get().registryId());
        assertEquals("PENDING", hit.get().status());
    }

    @Test
    void rejectedPlaylist_returnsMatch_withRejectedStatus() throws ExecutionException, InterruptedException, TimeoutException {
        Playlist p = new Playlist("PL123");
        p.setId("doc-2");
        p.setStatus("REJECTED");
        when(playlists.findByYoutubeId("PL123")).thenReturn(Optional.of(p));
        var batch = checker.newBatch();

        var hit = batch.findExisting(YouTubeContentType.PLAYLIST, "PL123");
        assertEquals("REJECTED", hit.orElseThrow().status());
    }

    @Test
    void perBatchMemoization_doesNotRequeryRepo() throws ExecutionException, InterruptedException, TimeoutException {
        when(videos.findByYoutubeId("V123")).thenReturn(Optional.empty());
        var batch = checker.newBatch();

        batch.findExisting(YouTubeContentType.VIDEO, "V123");
        batch.findExisting(YouTubeContentType.VIDEO, "V123");

        verify(videos, times(1)).findByYoutubeId("V123");
    }

    @Test
    void separateBatches_doNotShareCache() throws ExecutionException, InterruptedException, TimeoutException {
        when(channels.findByYoutubeId("UC999")).thenReturn(Optional.empty());
        var batch1 = checker.newBatch();
        var batch2 = checker.newBatch();

        batch1.findExisting(YouTubeContentType.CHANNEL, "UC999");
        batch2.findExisting(YouTubeContentType.CHANNEL, "UC999");

        verify(channels, times(2)).findByYoutubeId("UC999");
    }

    @Test
    void markAsExisting_shadowsLaterFindExisting_withoutRepoCall() throws ExecutionException, InterruptedException, TimeoutException {
        // Repository returns empty — i.e. the row was not in Firestore before this batch.
        when(channels.findByYoutubeId("UCnew")).thenReturn(Optional.empty());
        var batch = checker.newBatch();

        // First lookup memoizes empty.
        assertTrue(batch.findExisting(YouTubeContentType.CHANNEL, "UCnew").isEmpty());

        // Production code wrote a new doc; record it in the batch.
        batch.markAsExisting(YouTubeContentType.CHANNEL, "UCnew", "doc-c-1", "PENDING");

        // Subsequent lookup returns the just-written entry, not the stale empty.
        var hit = batch.findExisting(YouTubeContentType.CHANNEL, "UCnew");
        assertTrue(hit.isPresent());
        assertEquals("doc-c-1", hit.get().registryId());
        assertEquals("PENDING", hit.get().status());

        // Repository was queried only once — the second findExisting hit the cache.
        verify(channels, times(1)).findByYoutubeId("UCnew");
    }

    /**
     * Cubic b26-r1: two REJECTED docs of a video (a pre-B11 duplicate). The match is the plain one; its sibling is a
     * take-down's ARCHIVED doc. A re-add into the match would serve the id beside it: held. The match itself archived
     * (a take-down record a re-add lifts, or an in-place archive save() keeps) is not.
     */
    @Test
    void aRejectedVideoBesideAnArchivedSiblingIsHeld() throws Exception {
        com.albunyaan.tube.model.Video plain = new com.albunyaan.tube.model.Video("ilgiwqpcOC8");
        plain.setId("a");
        plain.setStatus("REJECTED");
        com.albunyaan.tube.model.Video takenDown = new com.albunyaan.tube.model.Video("ilgiwqpcOC8");
        takenDown.setId("b");
        takenDown.setStatus("REJECTED");
        takenDown.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        when(videos.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.of(plain));
        when(videos.findAllByYoutubeId("ilgiwqpcOC8")).thenReturn(List.of(plain, takenDown));
        when(videos.findByYoutubeId("xc7keR2piUM")).thenReturn(Optional.of(takenDown));
        when(videos.findAllByYoutubeId("xc7keR2piUM")).thenReturn(List.of(takenDown));

        var batch = checker.newBatch();

        org.junit.jupiter.api.Assertions.assertTrue(batch.findExisting(YouTubeContentType.VIDEO, "ilgiwqpcOC8").get().held());
        org.junit.jupiter.api.Assertions.assertFalse(batch.findExisting(YouTubeContentType.VIDEO, "xc7keR2piUM").get().held());
    }
}
