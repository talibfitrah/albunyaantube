package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagEnrichmentServiceTest {

    @Mock private VideoRepository videoRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private YouTubeGateway youTubeGateway;
    @Mock private PublicContentCacheService publicContentCacheService;

    /** B17: the shared breaker no longer records a /player bot check, so the run itself stops asking after the first. */
    @Test
    void enrichVideos_stopsYouTubeTagFetchesAtTheFirstBotCheck() throws Exception {
        List<Video> videos = List.of(new Video("validatorT1"), new Video("validatorT2"), new Video("validatorT3"));
        videos.forEach(v -> v.setTitle("Tafsir lesson"));
        when(videoRepository.findAll()).thenReturn(videos);
        when(youTubeGateway.fetchStreamInfo(anyString())).thenThrow(new SignInConfirmNotBotException(
                "YouTube probably temporarily blocked anonymous watch access with this IP , got error LOGIN_REQUIRED: "
                        + "\"Sign in to confirm you're not a bot\""));
        TagEnrichmentService service = new TagEnrichmentService(videoRepository, channelRepository, playlistRepository,
                youTubeGateway, publicContentCacheService);

        TagEnrichmentService.EnrichmentResult result = service.enrichVideos(false, true);

        verify(youTubeGateway, times(1)).fetchStreamInfo(anyString());
        assertEquals(3, result.enriched, "every video still gets its local tags");
        assertEquals(0, result.errors);
    }

    /**
     * Cubic users5: the run loads every channel and playlist first, then spends minutes on YouTube. A report's exclusion
     * written meanwhile stays: the run writes its keywords onto the doc as stored now, never its stale copy whole.
     */
    @Test
    void enrichmentKeepsAnExclusionWrittenSinceTheRunReadTheDoc() throws Exception {
        Channel read = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        read.setId("c1");
        read.setName("Tafsir lessons");
        Channel stored = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        stored.setId("c1");
        stored.getExcludedItems().getVideos().add("ilgiwqpcOC8");
        Playlist readPlaylist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        readPlaylist.setId("p1");
        readPlaylist.setTitle("Tafsir lessons");
        Playlist storedPlaylist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        storedPlaylist.setId("p1");
        storedPlaylist.getExcludedVideoIds().add("ilgiwqpcOC8");
        when(channelRepository.findAll()).thenReturn(List.of(read));
        when(playlistRepository.findAll()).thenReturn(List.of(readPlaylist));
        org.mockito.Mockito.lenient().when(channelRepository.update(any(), any())).thenAnswer(inv -> {
            inv.<java.util.function.Consumer<Channel>>getArgument(1).accept(stored);
            return stored;
        });
        org.mockito.Mockito.lenient().when(playlistRepository.update(any(), any())).thenAnswer(inv -> {
            inv.<java.util.function.Consumer<Playlist>>getArgument(1).accept(storedPlaylist);
            return storedPlaylist;
        });
        TagEnrichmentService service = new TagEnrichmentService(videoRepository, channelRepository, playlistRepository,
                youTubeGateway, publicContentCacheService);

        service.enrichChannels(false, false);
        service.enrichPlaylists(false, false);

        verify(channelRepository, never()).save(any());
        verify(playlistRepository, never()).save(any());
        assertEquals(read.getKeywords(), stored.getKeywords());
        assertEquals(List.of("ilgiwqpcOC8"), stored.getExcludedItems().getVideos());
        assertEquals(readPlaylist.getKeywords(), storedPlaylist.getKeywords());
        assertEquals(List.of("ilgiwqpcOC8"), storedPlaylist.getExcludedVideoIds());
    }
}
