package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.service.YouTubeSearchRateLimitedException;
import com.albunyaan.tube.service.YouTubeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YouTubeSearchControllerTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private YouTubeSearchService youtubeSearchService;

    private YouTubeSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new YouTubeSearchController(
                channelRepository,
                playlistRepository,
                videoRepository,
                youtubeSearchService
        );
    }

    @Test
    void checkExisting_returnsExistingIdsByType() throws Exception {
        Channel ch = mock(Channel.class);
        Playlist pl = mock(Playlist.class);
        Video vid = mock(Video.class);
        when(channelRepository.findByYoutubeIds(List.of("UC1", "UC2")))
                .thenReturn(Map.of("UC1", ch));
        when(playlistRepository.findByYoutubeIds(List.of("PL1")))
                .thenReturn(Map.of("PL1", pl));
        when(videoRepository.findByYoutubeIds(List.of("VID1")))
                .thenReturn(Map.of("VID1", vid));

        YouTubeSearchController.ExistingContentRequest request =
                new YouTubeSearchController.ExistingContentRequest();
        request.setChannelIds(List.of("UC1", "UC2"));
        request.setPlaylistIds(List.of("PL1"));
        request.setVideoIds(List.of("VID1"));

        ResponseEntity<YouTubeSearchController.ExistingContentResponse> response =
                controller.checkExisting(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(Set.of("UC1"), response.getBody().getExistingChannels());
        assertEquals(Set.of("PL1"), response.getBody().getExistingPlaylists());
        assertEquals(Set.of("VID1"), response.getBody().getExistingVideos());
    }

    @Test
    void checkExisting_treatsNullIdListsAsEmpty() throws Exception {
        when(channelRepository.findByYoutubeIds(anyList())).thenReturn(Map.of());
        when(playlistRepository.findByYoutubeIds(anyList())).thenReturn(Map.of());
        when(videoRepository.findByYoutubeIds(anyList())).thenReturn(Map.of());

        YouTubeSearchController.ExistingContentRequest request =
                new YouTubeSearchController.ExistingContentRequest();
        request.setChannelIds(null);
        request.setPlaylistIds(null);
        request.setVideoIds(null);

        ResponseEntity<YouTubeSearchController.ExistingContentResponse> response =
                controller.checkExisting(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(Set.of(), response.getBody().getExistingChannels());
        assertEquals(Set.of(), response.getBody().getExistingPlaylists());
        assertEquals(Set.of(), response.getBody().getExistingVideos());
    }

    @Test
    void search_rateLimited_propagatesYouTubeSearchRateLimitedException() {
        when(youtubeSearchService.search(any(), any(), any()))
                .thenThrow(new YouTubeSearchRateLimitedException(60L, new RuntimeException("rate limited")));

        assertThrows(YouTubeSearchRateLimitedException.class,
                () -> controller.search("q", YouTubeContentType.ALL, null));
    }
}
