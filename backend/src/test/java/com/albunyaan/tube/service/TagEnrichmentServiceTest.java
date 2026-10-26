package com.albunyaan.tube.service;

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
import static org.mockito.ArgumentMatchers.anyString;
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
}
