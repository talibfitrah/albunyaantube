package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.dto.StreamItemDto;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.YouTubeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YouTubeSearchControllerTest {

    @Mock
    private YouTubeService youtubeService;

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
                youtubeService,
                channelRepository,
                playlistRepository,
                videoRepository
        );
    }

    @Test
    void getChannelDetails_returnsDtoDirectly() throws Exception {
        ChannelDetailsDto dto = new ChannelDetailsDto();
        dto.setId("UC123");
        when(youtubeService.getChannelDetailsDto("UC123")).thenReturn(dto);

        ResponseEntity<ChannelDetailsDto> response = controller.getChannelDetails("UC123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(dto, response.getBody());
        verify(youtubeService).getChannelDetailsDto("UC123");
        verify(youtubeService, never()).mapToChannelDetailsDto(any());
    }

    @Test
    void getChannelVideos_usesDtoOverloadWithSearch() throws Exception {
        StreamItemDto item = new StreamItemDto();
        when(youtubeService.getChannelVideosDto("UC123", "token", "filter")).thenReturn(List.of(item));

        ResponseEntity<List<StreamItemDto>> response = controller.getChannelVideos("UC123", "token", "filter");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(item), response.getBody());
        verify(youtubeService).getChannelVideosDto("UC123", "token", "filter");
        verify(youtubeService, never()).mapToStreamItemDto(any());
    }

    @Test
    void getVideoDetails_returnsNotFoundWhenDtoMissing() throws Exception {
        when(youtubeService.getVideoDetailsDto("vid-1")).thenReturn(null);

        ResponseEntity<StreamDetailsDto> response = controller.getVideoDetails("vid-1");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody() == null);
        verify(youtubeService).getVideoDetailsDto("vid-1");
        verify(youtubeService, never()).mapToStreamDetailsDto(any());
    }
}
