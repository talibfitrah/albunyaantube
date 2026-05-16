package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private YouTubeSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new YouTubeSearchController(
                channelRepository,
                playlistRepository,
                videoRepository
        );
    }

    @Test
    void checkExisting_returnsExistingIdsByType() throws Exception {
        when(channelRepository.findByYoutubeId("UC1")).thenReturn(Optional.of(mock(Channel.class)));
        when(channelRepository.findByYoutubeId("UC2")).thenReturn(Optional.empty());
        when(playlistRepository.findByYoutubeId("PL1")).thenReturn(Optional.of(mock(Playlist.class)));
        when(videoRepository.findByYoutubeId("VID1")).thenReturn(Optional.of(mock(Video.class)));

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
    void checkExisting_treatsNullIdListsAsEmpty() {
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
}
