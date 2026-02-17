package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PaginatedItemsResponse;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.PlaylistItemDto;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void getChannelDetails_returnsNotFoundWhenNull() throws Exception {
        when(youtubeService.getChannelDetailsDto("UC-MISSING")).thenReturn(null);

        ResponseEntity<ChannelDetailsDto> response = controller.getChannelDetails("UC-MISSING");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getChannelVideos_usesPaginatedDtoOverload() throws Exception {
        StreamItemDto item = new StreamItemDto();
        PaginatedItemsResponse<StreamItemDto> paginated = new PaginatedItemsResponse<>(List.of(item), null);
        when(youtubeService.getChannelVideosDtoPaginated("UC123", "token", "filter")).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<StreamItemDto>> response = controller.getChannelVideos("UC123", "token", "filter");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(paginated, response.getBody());
        verify(youtubeService).getChannelVideosDtoPaginated("UC123", "token", "filter");
    }

    @Test
    void getChannelShorts_returnsPaginatedResponse() throws Exception {
        StreamItemDto item = new StreamItemDto();
        item.setStreamType("SHORT");
        PaginatedItemsResponse<StreamItemDto> paginated = new PaginatedItemsResponse<>(List.of(item), "nextToken");
        when(youtubeService.getChannelShortsDtoPaginated("UC123", null)).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<StreamItemDto>> response = controller.getChannelShorts("UC123", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getItems().size());
        assertEquals("nextToken", response.getBody().getNextPageToken());
        verify(youtubeService).getChannelShortsDtoPaginated("UC123", null);
    }

    @Test
    void getChannelShorts_passesPageToken() throws Exception {
        PaginatedItemsResponse<StreamItemDto> paginated = new PaginatedItemsResponse<>(List.of(), null);
        when(youtubeService.getChannelShortsDtoPaginated("UC123", "page2")).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<StreamItemDto>> response = controller.getChannelShorts("UC123", "page2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(youtubeService).getChannelShortsDtoPaginated("UC123", "page2");
    }

    @Test
    void getChannelLiveStreams_returnsPaginatedResponse() throws Exception {
        StreamItemDto item = new StreamItemDto();
        item.setStreamType("LIVESTREAM");
        PaginatedItemsResponse<StreamItemDto> paginated = new PaginatedItemsResponse<>(List.of(item), null);
        when(youtubeService.getChannelLiveStreamsDtoPaginated("UC123", null)).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<StreamItemDto>> response = controller.getChannelLiveStreams("UC123", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getItems().size());
        verify(youtubeService).getChannelLiveStreamsDtoPaginated("UC123", null);
    }

    @Test
    void getChannelLiveStreams_passesPageToken() throws Exception {
        PaginatedItemsResponse<StreamItemDto> paginated = new PaginatedItemsResponse<>(List.of(), null);
        when(youtubeService.getChannelLiveStreamsDtoPaginated("UC123", "page2")).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<StreamItemDto>> response = controller.getChannelLiveStreams("UC123", "page2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(youtubeService).getChannelLiveStreamsDtoPaginated("UC123", "page2");
    }

    @Test
    void getChannelPlaylists_returnsPaginatedResponse() throws Exception {
        PlaylistItemDto item = new PlaylistItemDto();
        item.setId("PL123");
        PaginatedItemsResponse<PlaylistItemDto> paginated = new PaginatedItemsResponse<>(List.of(item), "nextToken");
        when(youtubeService.getChannelPlaylistsDtoPaginated("UC123", null)).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<PlaylistItemDto>> response = controller.getChannelPlaylists("UC123", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getItems().size());
        assertEquals("nextToken", response.getBody().getNextPageToken());
        verify(youtubeService).getChannelPlaylistsDtoPaginated("UC123", null);
    }

    @Test
    void getChannelPlaylists_passesPageToken() throws Exception {
        PaginatedItemsResponse<PlaylistItemDto> paginated = new PaginatedItemsResponse<>(List.of(), null);
        when(youtubeService.getChannelPlaylistsDtoPaginated("UC123", "page2")).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<PlaylistItemDto>> response = controller.getChannelPlaylists("UC123", "page2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(youtubeService).getChannelPlaylistsDtoPaginated("UC123", "page2");
    }

    @Test
    void getPlaylistDetails_returnsDto() throws Exception {
        PlaylistDetailsDto dto = new PlaylistDetailsDto();
        dto.setId("PL123");
        dto.setName("Test Playlist");
        when(youtubeService.getPlaylistDetailsDto("PL123")).thenReturn(dto);

        ResponseEntity<PlaylistDetailsDto> response = controller.getPlaylistDetails("PL123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(dto, response.getBody());
        verify(youtubeService).getPlaylistDetailsDto("PL123");
    }

    @Test
    void getPlaylistDetails_returnsNotFoundWhenNull() throws Exception {
        when(youtubeService.getPlaylistDetailsDto("PL-MISSING")).thenReturn(null);

        ResponseEntity<PlaylistDetailsDto> response = controller.getPlaylistDetails("PL-MISSING");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getPlaylistVideos_returnsPaginatedResponse() throws Exception {
        StreamItemDto item = new StreamItemDto();
        PaginatedItemsResponse<StreamItemDto> paginated = new PaginatedItemsResponse<>(List.of(item), "nextToken");
        when(youtubeService.getPlaylistVideosDtoPaginated("PL123", null, null)).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<StreamItemDto>> response = controller.getPlaylistVideos("PL123", null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getItems().size());
        assertEquals("nextToken", response.getBody().getNextPageToken());
        verify(youtubeService).getPlaylistVideosDtoPaginated("PL123", null, null);
    }

    @Test
    void getPlaylistVideos_passesPageTokenAndSearch() throws Exception {
        PaginatedItemsResponse<StreamItemDto> paginated = new PaginatedItemsResponse<>(List.of(), null);
        when(youtubeService.getPlaylistVideosDtoPaginated("PL123", "page2", "query")).thenReturn(paginated);

        ResponseEntity<PaginatedItemsResponse<StreamItemDto>> response = controller.getPlaylistVideos("PL123", "page2", "query");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(youtubeService).getPlaylistVideosDtoPaginated("PL123", "page2", "query");
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

    @Test
    void getVideoDetails_returnsDto() throws Exception {
        StreamDetailsDto dto = new StreamDetailsDto();
        dto.setId("vid-1");
        when(youtubeService.getVideoDetailsDto("vid-1")).thenReturn(dto);

        ResponseEntity<StreamDetailsDto> response = controller.getVideoDetails("vid-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(dto, response.getBody());
    }
}
